package io.eitheror.casino.simulator.st8

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.simulator.Simulator
import io.eitheror.casino.simulator.api.Access
import io.eitheror.casino.simulator.core.*
import io.eitheror.casino.simulator.st8.GoJson.Value.*

import java.util.UUID

/**
 * The instruction routes (FR-014; `main.go:809-1024, 361-465`), with the stand-in's bodies, so the
 * original's tests drive the simulator unchanged. In the default scenario each instruction is sent
 * once and the brand's answer returned verbatim. Under a scripted scenario an instruction is the
 * step the scenario waits for: the scenario sends it, with its own schedule, and the first answer is
 * returned as the stand-in would return it.
 */
final class Instructions(sim: Simulator) extends HttpEndpoint("/test"):

  val acl: Acl = Access.public

  private def newId(): String = UUID.randomUUID().toString

  get("/session/{token}") { (token: String) =>
    sim.play.session(token).filter(_.launched) match
      case None => Http.error(404, "session not found")
      case Some(s) =>
        Http.json(200, GoJson.obj(
          "launchToken" -> Str(s.launchToken), "walletToken" -> Str(s.walletToken.getOrElse("")), "playerId" -> Str(s.playerId),
          "currency" -> Str(s.currency), "gameCode" -> Str(s.gameCode)
        ))
  }

  /** The session an instruction names, ready to send on: 404 unknown, 409 before the game page's check. */
  private def ready(body: Http.Body)(f: Session => Http.Answer): Http.Answer =
    sim.play.session(body.string("launchToken")).filter(_.launched) match
      case None                          => Http.error(404, "session not found")
      case Some(s) if s.walletToken.forall(_.isEmpty) => Http.error(409, "wallet token not set — game page not loaded yet")
      case Some(s)                       => f(s)

  postBody("/bet") { (text: String) =>
    Http.read(text, strings = Set("launchToken", "amount", "currency", "roundId", "transactionId", "gameCode")).fold(Http.badRequest) { body =>
      ready(body) { s =>
        val call = Call("debit", s.walletToken, Some(orNew(body.string("transactionId"))), Some(orNew(body.string("roundId"))),
          Some(or(body.string("currency"), s.currency)), Some(body.string("amount")), Some("debit"), Some(or(body.string("gameCode"), s.gameCode)), None, None, None)
        instruct(s, call)
      }
    }
  }

  postBody("/win") { (text: String) =>
    Http.read(text, strings = Set("launchToken", "roundId", "amount", "currency", "transactionId")).fold(Http.badRequest) { body =>
      ready(body) { s =>
        val call = Call("credit", s.walletToken, Some(orNew(body.string("transactionId"))), Some(body.string("roundId")),
          Some(or(body.string("currency"), s.currency)), Some(body.string("amount")), Some("credit"), Some(s.gameCode), None, None, None)
        instruct(s, call)
      }
    }
  }

  postBody("/lose") { (text: String) =>
    Http.read(text, strings = Set("launchToken", "roundId", "currency", "transactionId")).fold(Http.badRequest) { body =>
      ready(body) { s =>
        val call = Call("credit", s.walletToken, Some(orNew(body.string("transactionId"))), Some(body.string("roundId")),
          Some(or(body.string("currency"), s.currency)), Some("0.00"), Some("credit"), Some(s.gameCode), None, None, None)
        instruct(s, call)
      }
    }
  }

  postBody("/cancel") { (text: String) =>
    Http.read(text, strings = Set("launchToken", "roundId", "cancelId", "transactionId", "currency")).fold(Http.badRequest) { body =>
      ready(body) { s =>
        val call = Call("cancel", s.walletToken, Some(body.string("transactionId")), Some(body.string("roundId")),
          Some(or(body.string("currency"), s.currency)), None, None, None, Some(orNew(body.string("cancelId"))), None, None)
        instruct(s, call)
      }
    }
  }

  postBody("/free-spin") { (text: String) =>
    Http.read(text, strings = Set("launchToken", "winAmount", "roundId", "transactionId")).fold(Http.badRequest) { body =>
      ready(body) { s =>
        val round = orNew(body.string("roundId"))
        val spent =
          try Right(sim.client.forKeyValueEntity(EntityId(BonusBook.Id)).call(BonusBook.spend).invoke(BonusBook.Spend(s.playerId, s.gameCode, s.currency)))
          catch case _: CommandError => Left(Http.json(409, GoJson.strings("error" -> "no_free_rounds")))
        spent match
          case Left(refused) => refused
          case Right(free) =>
            val debit  = Call("debit", s.walletToken, Some(newId()), Some(round), Some(free.currency), Some("0.00"), Some("free_debit"), Some(s.gameCode), None, None, None)
            val credit = Call("credit", s.walletToken, Some(orNew(body.string("transactionId"))), Some(round), Some(free.currency), Some(body.string("winAmount")), Some("credit"), Some(s.gameCode), None, None, None)
            val wait   = Wiring.brands.answerWait(s.brand)
            val d = sim.play.send(s, debit, "stub", "current", wait, None)
            sim.play.end(d.callbackId, d)
            d.attempt.httpStatus match
              case None => Http.error(502, s"adapter free_debit error: ${d.attempt.error.getOrElse("no answer")}")
              case Some(_) =>
                val c = sim.play.send(sim.play.session(s.launchToken).getOrElse(s), credit, "stub", "current", wait, None)
                sim.play.end(c.callbackId, c)
                c.attempt.httpStatus match
                  case None => Http.error(502, s"adapter credit error: ${c.attempt.error.getOrElse("no answer")}")
                  case Some(_) =>
                    Http.json(200, GoJson.sorted(
                      "credit" -> Instructions.embedded(c.attempt), "debit" -> Instructions.embedded(d.attempt),
                      "remaining" -> Num(free.remaining.toString), "roundId" -> Str(round)
                    ))
      }
    }
  }

  get("/bonus/{id}") { (id: String) =>
    try
      val b = sim.client.forKeyValueEntity(EntityId(BonusBook.Id)).call(BonusBook.get).invoke(id)
      Http.json(200, GoJson.sorted(
        "bonusId" -> Str(b.bonusId), "count" -> Num(b.count.toString), "currency" -> Str(b.currency), "gameCodes" -> GoJson.strArr(b.gameCodes),
        "instances" -> GoJson.sorted(b.instances.toVector.map((p, i) => p -> GoJson.sorted("remaining" -> Num(i.remaining.toString), "status" -> Str(i.status)))*),
        "players" -> GoJson.strArr(b.players), "site" -> Str(b.site), "type" -> Str(b.bonusType), "value" -> Str(b.value)
      ))
    catch case _: CommandError => Http.error(404, "bonus not found")
  }

  /**
   * Sends an instruction. The default scenario sends once and proxies the answer verbatim; a scripted
   * one hands the instruction to the session's `ScenarioPlay` and answers with its first attempt.
   */
  private def instruct(s: Session, call: Call): Http.Answer =
    val wait = Wiring.brands.answerWait(s.brand)
    if s.scenario == "default" then
      val sent = sim.play.send(s, call, "stub", "current", wait, None)
      sim.play.end(sent.callbackId, sent)
      Instructions.verbatim(sent.attempt)
    else
      val callbackId = newId()
      val fields = Map(
        "transactionId" -> call.transactionId, "round" -> call.round, "currency" -> call.currency, "amount" -> call.amount,
        "gameCode" -> call.gameCode, "cancelId" -> call.cancelId
      ).collect { case (k, Some(v)) => k -> v }
      try
        sim.client.forWorkflow(EntityId(s.launchToken)).call(ScenarioPlay.instruct).invoke(Pending(call.kind, fields, callbackId))
        firstAttempt(callbackId, wait).fold(Http.error(502, "adapter error: the scenario sent nothing"))(Instructions.verbatim)
      catch case e: CommandError => Http.error(409, e.message)

  private def firstAttempt(callbackId: String, wait: scala.concurrent.duration.FiniteDuration): Option[Attempt] =
    val deadline = System.nanoTime() + (wait.toNanos + 10_000_000_000L)
    var found    = Option.empty[Attempt]
    while found.isEmpty && System.nanoTime() < deadline do
      found =
        try sim.client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.get).invoke().attempts.headOption
        catch case _: CommandError => None
      if found.isEmpty then Thread.sleep(50)
    found

  private def orNew(v: String): String          = if v.isEmpty then newId() else v
  private def or(v: String, other: String): String = if v.isEmpty then other else v

object Instructions:
  /** `proxyAdapterResponse`: the status and body verbatim; `502 adapter error: …` when the brand did not answer. */
  def verbatim(a: Attempt): Http.Answer =
    a.httpStatus match
      case Some(status) => Http.proxied(status, a.answer.getOrElse("").getBytes("UTF-8"))
      case None         => Http.error(502, s"adapter error: ${a.error.getOrElse("no answer")}")

  /** `readAdapterJSON`: the answer decoded and re-encoded, or the raw text when it is not JSON. */
  def embedded(a: Attempt): GoJson.Value =
    val text = a.answer.getOrElse("")
    scala.util.Try(com.fasterxml.jackson.databind.ObjectMapper().readTree(text)).toOption.filter(_ != null)
      .map(GoJson.fromJackson).getOrElse(Str(text))
