package io.eitheror.casino.provider.edge.games

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.provider.core.*
import io.eitheror.casino.provider.edge.registry.{Game, GameEntity, GameIndex}
import io.eitheror.casino.provider.st8.{GoJson, Http, St8Protocol}
import io.eitheror.casino.provider.st8.GoJson.Value.*

import java.time.Instant
import java.util.UUID

/**
 * What a game service calls on the house provider (`contracts/game-contract.md`): its registration,
 * the launch ticket's check, and its rounds' stakes, settles and cancels. The game never reaches a
 * brand: every call to a brand is the provider's, signed, through the core's `Play` and `Deliver`.
 *
 * Admitted as the service class, by name: a route naming a code or a session answers `forbidden`
 * when the caller is not the code's owner. On a developer's machine every caller is `local`, and
 * owns what it registers. Amounts are minor units; the wire's decimal strings are rendered here by
 * the currency's exponent.
 */
final class GamesEndpoint(client: ComponentClient, clock: () => Instant = () => Instant.now()) extends HttpEndpoint("/v1/games"):

  val acl: Acl = Routes.serviceNamed("games")

  private val play = Play(client)

  /** The caller's service name: `service:games/<name>` admitted by the ACL, or `local`. */
  private def owner: String = principal.subject.stripPrefix("service:games/")

  private def game(code: String): Option[Game] =
    try Some(client.forKeyValueEntity(EntityId(code)).call(GameEntity.get).invoke())
    catch case _: CommandError => None

  // ---- registration (T023) --------------------------------------------------------------------

  putBody("/registry/{code}") { (code: String, text: String) =>
    Http.read(text, strings = Set("name", "category", "deprecationDate", "pageAddress"),
      stringArrays = Set("bonusTypes", "features", "themes", "restrictedTerritories", "prohibitedTerritories"), booleans = Set("demo")) match
      case None => Http.error(400, "the registration is not an object of the contract's fields")
      case Some(b) =>
        val missing = Vector("name", "category", "pageAddress").filter(b.string(_).isEmpty)
        if missing.nonEmpty then Http.error(400, s"${missing.head} is required")
        else if !b.string("pageAddress").matches("https?://[^?#\\s]+") then Http.error(400, "pageAddress must be an http(s) address without a query")
        else
          val now = clock()
          val g = Game(code, owner, b.string("name"), b.string("category"), Game.Developer, b.strings("bonusTypes"), b.strings("features"),
            b.strings("themes"), b.strings("restrictedTerritories"), b.strings("prohibitedTerritories"),
            Option(b.string("deprecationDate")).filter(_.nonEmpty), Option(b.raw.get("demo")).exists(_.asBoolean(false)), b.string("pageAddress"), now, now)
          try
            val held = client.forKeyValueEntity(EntityId(code)).call(GameEntity.register).invoke(g)
            client.forKeyValueEntity(EntityId(GameIndex.Id)).call(GameIndex.add).invoke(code): Unit
            Http.json(200, GoJson.strings("code" -> held.code, "owner" -> held.owner))
          catch case e: CommandError if e.message.startsWith(GameEntity.CodeTaken) =>
            Http.json(409, GoJson.obj("error" -> Str(GameEntity.CodeTaken), "owner" -> Str(game(code).map(_.owner).getOrElse(""))))
  }

  delete("/registry/{code}") { (code: String) =>
    try
      client.forKeyValueEntity(EntityId(code)).call(GameEntity.deregister).invoke(owner): Unit
      client.forKeyValueEntity(EntityId(GameIndex.Id)).call(GameIndex.drop).invoke(code): Unit
      Http.json(200, GoJson.strings("code" -> code))
    catch case e: CommandError => Http.error(403, e.message)
  }

  // ---- sessions (T024) ------------------------------------------------------------------------

  /**
   * The session a route names, held to the game service that owned its code at launch: 404 for a
   * token no session holds, 403 for another game's. The owner is the session's own, so a code
   * deregistered or removed while a session is open keeps that session served by its owner, and a
   * service that registers the freed code is not admitted to it (contracts/game-contract.md). A
   * session launched before the owner was recorded falls back to the code's current owner.
   */
  private def owned(token: String)(f: Session => Http.Answer): Http.Answer =
    play.session(token).filter(_.launched) match
      case None => Http.error(404, "session not found")
      case Some(s) if !ownedBy(s) => Http.error(403, s"forbidden: the session is not a game of $owner")
      case Some(s) => f(s)

  private def ownedBy(s: Session): Boolean =
    if s.owner.nonEmpty then s.owner == owner else game(s.gameCode).exists(_.owner == owner)

  /** A guarded plan the session refused under its round's rule: the contract's 409 with the rule's word. */
  private def refusedByRound[A](body: => Http.Answer): Http.Answer =
    try body
    catch
      case e: CommandError if Session.Refusal.All.exists(w => e.message.startsWith(w + ":")) =>
        Http.json(409, GoJson.strings("error" -> e.message.takeWhile(_ != ':')))

  private def sessionJson(s: Session, withRounds: Boolean): GoJson.Value =
    val base = Vector(
      "brand" -> Str(s.brand), "playerId" -> Str(s.playerId), "currency" -> Str(s.currency), "gameCode" -> Str(s.gameCode),
      "demo" -> Bool(s.demo), "language" -> Str(s.language), "sessionTicket" -> Str(s.walletToken.getOrElse(""))
    )
    val rounds = Option.when(withRounds)("rounds" -> Arr(s.roundOrder.flatMap(s.rounds.get).map { r =>
      GoJson.obj("roundId" -> Str(r.roundId), "state" -> Str(r.state), "free" -> Bool(r.free),
        "debits" -> GoJson.strArr(r.debits), "credits" -> GoJson.strArr(r.credits), "cancels" -> GoJson.strArr(r.cancels))
    }))
    Obj(base ++ rounds.toVector)

  post("/sessions/{token}/check") { (token: String) =>
    owned(token) { s =>
      if s.checked then Http.json(200, sessionJson(s, withRounds = false))
      else if s.refused then Http.json(200, GoJson.strings("refused" -> St8Protocol.Words.SessionExpired))
      else if s.demo then
        // A demo session is the game's alone: the brand is never called (contract, rules).
        val after = client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.check).invoke("")
        Http.json(200, sessionJson(after, withRounds = false))
      else
        val sent = play.send(s, St8Protocol.check(token), "stub", "current", Wiring.brands.answerWait(s.brand), None)
        play.end(sent.callbackId, sent)
        sent.reading.filter(r => sent.attempt.httpStatus.contains(200) && Wiring.protocol.accepted(r)) match
          case Some(reading) =>
            val after = client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.check).invoke(reading.token.getOrElse(""))
            Http.json(200, sessionJson(after, withRounds = false))
          case None =>
            val word = sent.reading.flatMap(_.status).filter(_.nonEmpty).getOrElse(St8Protocol.Words.SessionExpired)
            client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.refuseCheck).invoke(word): Unit
            Http.json(200, GoJson.strings("refused" -> word))
    }
  }

  get("/sessions/{token}") { (token: String) =>
    owned(token)(s => Http.json(200, sessionJson(s, withRounds = true)))
  }

  // ---- rounds (T025) --------------------------------------------------------------------------

  private def amountOf(text: String): Either[Http.Answer, Long] =
    Http.read(text).flatMap(b => Option(b.raw.get("amount")).filter(_.isIntegralNumber).map(_.asLong())) match
      case Some(a) if a >= 0 => Right(a)
      case _                 => Left(Http.error(400, "amount is required: a whole number of minor units, 0 or more"))

  private def wire(minor: Long, s: Session): String = Amounts.render(minor, Currency.unsafe(s.currency))

  /** A balance the brand answered, in minor units when it reads as the session's currency. */
  private def balanceJson(balance: Option[String], s: Session): Vector[(String, GoJson.Value)] =
    balance.toVector.map { b =>
      "balance" -> Amounts.parse(b, Currency.unsafe(s.currency)).fold(_ => Str(b), m => Num(m.value.toString))
    }

  private def checkedSession(token: String)(f: Session => Http.Answer): Http.Answer =
    owned(token) { s =>
      if !s.checked then Http.json(409, GoJson.strings("error" -> "not_checked"))
      else f(s)
    }

  private def deliver(s: Session, callbackId: String, made: Int, roundId: Option[String]): Unit =
    client.forWorkflow(EntityId(callbackId)).call(Deliver.start)
      .invoke(Deliver.Start(callbackId, s.launchToken, Wiring.now(), made, roundId)): Unit

  postBody("/sessions/{token}/rounds/{roundId}/stake") { (token: String, roundId: String, text: String) =>
    checkedSession(token) { s =>
      val kind = Http.read(text, strings = Set("kind")).map(_.string("kind")).filter(_.nonEmpty).getOrElse("debit")
      amountOf(text) match
        case Left(refused) => refused
        case Right(_) if !Set("debit", "free_debit").contains(kind) => Http.error(400, "kind is debit or free_debit")
        case Right(_) if s.round(roundId).isDefined => Http.json(409, GoJson.strings("error" -> "round_used"))
        case Right(_) if s.demo => Http.json(200, GoJson.strings("status" -> "demo"))
        case Right(amount) =>
          // A free entry is sent as 0.00; the amount the game names is its own record (contract, rules).
          val onWire = if kind == "free_debit" then wire(0, s) else wire(amount, s)
          val tx     = UUID.randomUUID().toString
          val call   = Call("debit", s.walletToken, Some(tx), Some(roundId), Some(s.currency), Some(onWire), Some(kind), Some(s.gameCode), None, None, None)
          refusedByRound {
            val sent = play.send(s, call, "stub", "current", Wiring.brands.answerWait(s.brand), None, callbackId = tx, awaitingAnswer = true, guarded = true)
            sent.reading match
              case Some(r) if Wiring.protocol.accepted(r) =>
                play.end(tx, Callback.Acknowledged)
                answered(s, roundId, accepted = true)
                Http.json(200, Obj(Vector("status" -> Str(St8Protocol.Words.Ok), "callbackId" -> Str(tx)) ++ balanceJson(r.balance, s)))
              case Some(r) if r.malformedBody || !r.retryable =>
                play.end(tx, Callback.Refused)
                answered(s, roundId, accepted = false)
                Http.json(200, Obj(Vector("status" -> Str(r.status.getOrElse(Callback.Refused)), "callbackId" -> Str(tx)) ++ balanceJson(r.balance, s)))
              case _ =>
                deliver(s, tx, made = 1, roundId = Some(roundId))
                Http.json(202, GoJson.strings("status" -> Callback.Pending, "callbackId" -> tx))
          }
    }
  }

  private def answered(s: Session, roundId: String, accepted: Boolean): Unit =
    client.forEventSourcedEntity(EntityId(s.launchToken)).call(SessionEntity.answered)
      .invoke(SessionEvent.RoundAnswered(roundId, accepted)): Unit

  postBody("/sessions/{token}/rounds/{roundId}/settle") { (token: String, roundId: String, text: String) =>
    checkedSession(token) { s =>
      amountOf(text) match
        case Left(refused) => refused
        case Right(amount) =>
          s.round(roundId).map(_.state) match
            case Some(Round.Pending) => Http.json(409, GoJson.strings("error" -> "pending"))
            case Some(Round.Open) if !s.demo =>
              val tx   = UUID.randomUUID().toString
              val call = Call("credit", s.walletToken, Some(tx), Some(roundId), Some(s.currency), Some(wire(amount, s)), Some("credit"), Some(s.gameCode), None, None, None)
              refusedByRound {
                play.plan(s, call, "stub", None, tx, guarded = true): Unit
                deliver(s, tx, made = 0, roundId = None)
                Http.json(202, GoJson.strings("status" -> "accepted", "callbackId" -> tx))
              }
            case _ => Http.json(409, GoJson.strings("error" -> "round_not_open"))
    }
  }

  post("/sessions/{token}/rounds/{roundId}/cancel") { (token: String, roundId: String) =>
    checkedSession(token) { s =>
      s.round(roundId).filter(r => (r.state == Round.Open || r.state == Round.Pending) && r.debits.nonEmpty) match
        case Some(r) if !s.demo =>
          val cancelId = UUID.randomUUID().toString
          val call = Call("cancel", s.walletToken, r.debits.lastOption, Some(roundId), Some(s.currency), None, None, None, Some(cancelId), None, None)
          refusedByRound {
            play.plan(s, call, "stub", None, cancelId, guarded = true): Unit
            deliver(s, cancelId, made = 0, roundId = None)
            Http.json(202, GoJson.strings("status" -> "accepted", "callbackId" -> cancelId, "cancelId" -> cancelId))
          }
        case _ => Http.json(409, GoJson.strings("error" -> "no_open_debit"))
    }
  }

  get("/callbacks/{id}") { (id: String) =>
    val held =
      try Some(client.forEventSourcedEntity(EntityId(id)).call(CallbackEntity.get).invoke())
      catch case _: CommandError => None
    held.filter(_.scheduled) match
      case None => Http.error(404, "callback not found")
      case Some(c) => owned(c.sessionId) { s =>
        val state =
          if c.outcome == Callback.Pending then if c.attempts.isEmpty then "planned" else "sent"
          else c.outcome
        val answer = c.lastAnswered.map(a => "answer" -> Obj(Vector("status" -> Str(a.status.getOrElse(""))) ++ balanceJson(a.balance, s)))
        val retry  = c.nextRetryAt.filter(_ => c.outcome == Callback.Pending).map(at => "nextRetryAt" -> Str(Instant.ofEpochMilli(at).toString))
        Http.json(200, Obj(Vector("state" -> Str(state), "attempts" -> Num(c.attempts.size.toString)) ++ answer.toVector ++ retry.toVector))
      }
  }
