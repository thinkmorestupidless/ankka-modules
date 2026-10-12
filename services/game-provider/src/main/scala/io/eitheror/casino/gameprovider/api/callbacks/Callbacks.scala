package io.eitheror.casino.gameprovider.api.callbacks

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.clients.*
import io.eitheror.casino.gameprovider.config.ProviderConfigurations
import io.eitheror.casino.gameprovider.money.Amounts
import io.eitheror.casino.gameprovider.players.*
import io.eitheror.casino.gameprovider.players.BetLimits
import io.eitheror.casino.gameprovider.provider.st8.*
import io.eitheror.casino.gameprovider.rounds.*
import io.eitheror.casino.gameprovider.tickets.*

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

/** A callback's answer: an HTTP status and the JSON body (contracts/callbacks.md). */
final case class Answered(status: Int, body: ObjectNode)

object Answered:
  def word(w: String): Answered = Answered(200, St8Protocol.status(w))

/** Ends a handler early with an answer; caught where the handler is run. */
final class Early(val answer: Answered) extends RuntimeException(null, null, false, false)

/**
 * The provider's eight callbacks for one provider (contracts/callbacks.md, FR-010 to FR-020), once the
 * signature has verified. The money rules are here; the wallet, the round and the ticket are each asked
 * through their own client or entity. Every money callback is a bet workflow keyed by the provider's
 * transaction id (the cancel id for a cancel, research R18): this class resolves who and how much, starts
 * the workflow and answers from its outcome; a repeat is answered from the workflow without
 * `responsible-gambling` or the wallet (FR-011), and a call the wallet did not acknowledge is answered
 * `error`, recorded, and resumed on the provider's retry or an operator's replay (FR-020).
 */
final class Callbacks(
    brand: String,
    provider: String,
    components: ComponentClient,
    views: ViewClient,
    wallet: WalletClient,
    players: PlayersClient,
    configs: ProviderConfigurations,
    now: () => Instant = () => Instant.now()
):

  /** Runs one callback. */
  def handle(callback: Callback, node: ObjectNode, raw: String): Answered =
    val body = CallbackBody(callback, node)
    try
      callback match
        case Callback.Check         => check(body)
        case Callback.Balance       => balance(body)
        case Callback.PlayerProfile => profile(body)
        case Callback.Debit         => debit(body, raw)
        case Callback.Credit        => credit(body, raw)
        case Callback.Cancel        => cancel(body, raw)
        case Callback.Buyin         => buyin(body, raw)
        case Callback.Payout        => payout(body, raw)
    catch
      case e: Early => e.answer
      case e: Exception =>
        Callbacks.log.warn(s"${callback.wire} failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        Answered.word(Status.Error)

  private def stop(answer: Answered): Nothing = throw Early(answer)
  private def stop(word: String): Nothing     = stop(Answered.word(word))

  // ── Shared reads ───────────────────────────────────────────────────────────────────────────────

  private def ticket(token: String): TicketState =
    components.forKeyValueEntity(EntityId(token)).call(Ticket.get).invoke()

  /** The session ticket a callback names: a checked ticket of this provider, else none. */
  private def sessionTicket(token: Option[String]): Option[TicketState] =
    token.map(ticket).filter(t => t.isChecked && t.provider == provider)

  private def known(player: String): KnownPlayerState =
    components.forKeyValueEntity(EntityId(player)).call(KnownPlayer.get).invoke()

  private def locked(player: String): Boolean =
    views.forView(PlayerStatus).get(player).exists(_.locked)

  /** FR-006: the session ticket's currency, else the player's, else the callback's, else EUR. */
  private def currencyFor(t: Option[TicketState], player: String, body: CallbackBody): String =
    t.map(_.currency).filter(_.nonEmpty)
      .orElse(known(player).currency)
      .orElse(body.currency)
      .filter(c => Currency.from(c).isRight)
      .getOrElse("EUR")

  private def amountOf(body: CallbackBody, currency: String): Long =
    Amounts.field(body.node, "amount", Currency.unsafe(currency)) match
      case Right(minor) => minor.value
      case Left(problem) =>
        stop(Answered(400, Json.obj("status" -> Status.Error, "error" -> "amount_not_minor_units", "amount" -> problem.amount)))

  private def render(balance: Long, currency: String) = Amounts.render(balance, Currency.unsafe(currency))

  private def answer(a: StoredAnswer): Answered =
    Answered(200, St8Protocol.money(a.status, a.balance.map(b => render(b, a.currency.getOrElse("EUR"))), a.currency))

  private def round(entityId: String) = components.forEventSourcedEntity(EntityId(entityId))

  private def opening(roundId: String, player: String, currency: String, t: Option[TicketState], gameCode: Option[String]) =
    RoundOpening(provider, roundId, player, currency, gameCode.orElse(t.map(_.gameCode)).filter(_.nonEmpty), t.map(_.sessionId).filter(_.nonEmpty), now())

  private def txKey(body: CallbackBody): String =
    body.transactionId.filter(_.length <= 200).getOrElse(stop(Status.Error))

  // ── check (FR-010) ─────────────────────────────────────────────────────────────────────────────

  private def check(body: CallbackBody): Answered =
    val token  = body.token.getOrElse(stop(Status.SessionExpired))
    val before = ticket(token)
    if before.status != TicketState.Issued || before.provider != provider || body.gameCode.exists(_ != before.gameCode) then
      stop(Status.SessionExpired)
    if locked(before.playerId) then stop(Status.PlayerLocked)
    val result = components.forKeyValueEntity(EntityId(token)).call(Ticket.check).invoke(CheckTicket(body.gameCode, now()))
    if !result.accepted then stop(Status.SessionExpired)
    val t = result.ticket
    components.forKeyValueEntity(EntityId(t.playerId)).call(KnownPlayer.makeKnown).invoke(MakeKnown(provider, Some(t.currency), now())): Unit
    val country = players.profile(t.playerId) match
      case ProfileAnswer.Found(p) => p.country.map(_.toUpperCase).getOrElse("MT")
      case _                      => "MT"
    val config = configs.get(provider)
    Answered(200, Json.obj(
      "status" -> Status.Ok, "token" -> token, "currency" -> t.currency, "game_code" -> t.gameCode, "country" -> country,
      "player_id" -> t.playerId,
      "site" -> Json.obj("id" -> config.map(_.site).getOrElse(brand), "lobby" -> config.map(_.lobbyUrl).getOrElse(""),
        "deposit" -> config.map(_.depositUrl).getOrElse(""))
    ))

  // ── balance (FR-017) ───────────────────────────────────────────────────────────────────────────

  private def balance(body: CallbackBody): Answered =
    val named = body.player.getOrElse(stop(Status.PlayerNotFound))
    val usable = body.token.filter(_.length >= 10)
    val (player, t) = usable match
      case Some(token) => sessionTicket(Some(token)).map(t => t.playerId -> Some(t)).getOrElse(stop(Status.SessionExpired))
      case None        => if known(named).knownTo(provider) then named -> None else stop(Status.SessionExpired)
    if locked(player) then stop(Status.PlayerLocked)
    val currency = currencyFor(t, player, body)
    wallet.balance(player, currency) match
      case Right(b) => Answered(200, St8Protocol.money(Status.Ok, Some(render(b.total, currency)), Some(currency)))
      case Left(_)  => Answered.word(Status.Error)

  // ── player_profile (FR-018) ────────────────────────────────────────────────────────────────────

  private def profile(body: CallbackBody): Answered =
    val player = body.player.getOrElse(stop(Status.PlayerNotFound))
    val k      = known(player)
    // Known to the provider (a check, or `rewards` making them known), or registered at the brand, which
    // the players topic has told this service: an id neither names is not the brand's (FR-018).
    if !k.knownTo(provider) && views.forView(PlayerStatus).get(player).isEmpty then stop(Status.PlayerNotFound)
    players.profile(player) match
      case ProfileAnswer.NotFound       => Answered.word(Status.PlayerNotFound)
      case ProfileAnswer.Unavailable(_) => Answered.word(Status.Error)
      case ProfileAnswer.Found(p) =>
        val currency = p.defaultCurrency.orElse(k.currency).getOrElse("EUR")
        Answered(200, Json.obj(
          "status" -> Status.Ok, "id" -> player,
          "jurisdiction" -> configs.get(provider).map(_.jurisdiction).getOrElse(""),
          "default_currency" -> currency,
          "reg_country" -> p.country.map(_.toUpperCase).getOrElse(""),
          "birth_date" -> p.dateOfBirth.getOrElse(""),
          "reg_date" -> p.registeredAt.map(Callbacks.calendarDay).getOrElse(""),
          "bet_limits" -> BetLimits.band(p.dailyDepositLimit, Currency.from(currency).getOrElse(Currency.unsafe("EUR")))
        ))

  // ── Money callbacks: one bet workflow per transaction (FR-011, FR-020, research R18) ────────────

  private def workflow(key: String) = components.forWorkflow(BetWorkflow.id(provider, key))

  /**
   * Starts the transaction's workflow and answers from its outcome. A start refused `Conflict` is a repeat:
   * it is answered from the first outcome, and a body that differs is recorded as a conflicting repeat; a
   * repeat of an unacknowledged transaction is the provider's retry, which resumes it.
   */
  private def run(job: BetJob): Answered =
    val started =
      try { workflow(job.key).call(BetWorkflow.start).invoke(job); true }
      catch case e: CommandError if e.code == ErrorCode.Conflict => false
    if !started then
      val first = workflow(job.key).call(BetWorkflow.status).invoke()
      first.job.foreach(conflict(_, job))
      if first.status == BetFlow.Unacknowledged then
        workflow(job.key).call(BetWorkflow.retry).invoke(Cleared(Cleared.ProviderRetry, None, None, now())): Unit
    Callbacks.await(components, provider, job.key).fold(Answered.word(Status.Error))(answer)

  private def conflict(first: BetJob, again: BetJob): Unit =
    val differences = Vector(
      Option.when(first.amount != again.amount && first.callback != "cancel")(s"amount ${first.amount} then ${again.amount}"),
      Option.when(first.kind != again.kind)(s"kind ${first.kind} then ${again.kind}"),
      Option.when(again.references.isDefined && first.references != again.references)(
        s"references ${first.references.getOrElse("")} then ${again.references.get}")
    ).flatten
    if differences.nonEmpty then
      round(first.roundEntity).call(Round.recordConflict).invoke(ConflictingRepeat(first.key, differences.mkString("; "), now())): Unit

  // ── debit (FR-012, FR-013) ─────────────────────────────────────────────────────────────────────

  private def debit(body: CallbackBody, raw: String): Answered =
    val key      = txKey(body)
    val t        = sessionTicket(body.token).getOrElse(stop(Status.SessionExpired))
    val player   = t.playerId
    val currency = currencyFor(Some(t), player, body)
    val amount   = amountOf(body, currency)
    val roundId  = body.round.getOrElse(key)
    run(BetJob(provider, Callback.Debit.wire, body.kind, key, roundId, Round.entityId(provider, roundId), player, currency, amount,
      body.gameCode.getOrElse(t.gameCode), t.sessionId, t.gameCategory, t.freeRoundsGrant, None, locked(player), raw,
      opening(roundId, player, currency, Some(t), body.gameCode)))

  // ── credit (FR-014) ────────────────────────────────────────────────────────────────────────────

  private def credit(body: CallbackBody, raw: String): Answered =
    val key      = txKey(body)
    val t        = sessionTicket(body.token).getOrElse(stop(Status.SessionExpired))
    val player   = t.playerId
    val currency = currencyFor(Some(t), player, body)
    val amount   = amountOf(body, currency)
    val roundId  = body.round.getOrElse(key)
    run(BetJob(provider, Callback.Credit.wire, body.kind, key, roundId, Round.entityId(provider, roundId), player, currency, amount,
      body.gameCode.getOrElse(t.gameCode), t.sessionId, t.gameCategory, None, None, accountLocked = false, raw,
      opening(roundId, player, currency, Some(t), body.gameCode)))

  // ── cancel (FR-015) ────────────────────────────────────────────────────────────────────────────

  private def cancel(body: CallbackBody, raw: String): Answered =
    val cancelId = body.cancelId.filter(_.length <= 200).getOrElse(stop(Status.Error))
    val refId    = body.transactionId.getOrElse(stop(Status.TransactionNotFound))
    val t        = sessionTicket(body.token)
    val player = t.map(_.playerId)
      .orElse(body.player.filter(p => known(p).knownTo(provider)))
      .getOrElse(stop(Status.SessionExpired))
    val currency = currencyFor(t, player, body)
    def notFound = Answered(200, St8Protocol.money(Status.TransactionNotFound,
      wallet.balance(player, currency).toOption.map(b => render(b.total, currency)), Some(currency)))
    // The referenced transaction is found by its own workflow, which names its round (the index it replaces).
    val referenced = workflow(refId).call(BetWorkflow.status).invoke().job.filter(_.player == player).getOrElse(stop(notFound))
    val open = RoundOpening(provider, referenced.roundId, player, currency, referenced.opening.gameCode, referenced.opening.sessionId, now())
    run(BetJob(provider, Callback.Cancel.wire, "cancel", cancelId, referenced.roundId, referenced.roundEntity, player, currency, 0,
      referenced.gameId, referenced.sessionId, None, None, Some(refId), accountLocked = false, raw, open))

  // ── buyin and payout (FR-016) ──────────────────────────────────────────────────────────────────

  private def buyin(body: CallbackBody, raw: String): Answered =
    val named    = body.player.getOrElse(stop(Status.PlayerNotFound))
    val key      = txKey(body)
    val t        = sessionTicket(body.token)
    val player   = t.map(_.playerId).getOrElse(named)
    val currency = currencyFor(t, player, body)
    val amount   = amountOf(body, currency)
    run(BetJob(provider, Callback.Buyin.wire, body.kind, key, key, Round.entityId(provider, key), player, currency, amount,
      body.gameCode.orElse(t.map(_.gameCode)).getOrElse(""), t.map(_.sessionId).getOrElse(""), t.flatMap(_.gameCategory), None, None,
      accountLocked = false, raw, opening(key, player, currency, t, body.gameCode)))

  private def payout(body: CallbackBody, raw: String): Answered =
    val named    = body.player.getOrElse(stop(Status.PlayerNotFound))
    val key      = txKey(body)
    val t        = sessionTicket(body.token)
    val player   = t.map(_.playerId).getOrElse(named)
    val currency = currencyFor(t, player, body)
    val amount   = amountOf(body, currency)
    run(BetJob(provider, Callback.Payout.wire, Kind.Payout, key, key, Round.entityId(provider, key), player, currency, amount,
      body.gameCode.orElse(t.map(_.gameCode)).getOrElse(""), t.map(_.sessionId).getOrElse(""), None, None, None,
      accountLocked = false, raw, opening(key, player, currency, t, body.gameCode)))

object Callbacks:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[Callbacks])

  /** How long a callback waits for its workflow's outcome before answering `error` (research R18). */
  val AwaitBound: FiniteDuration = 2.seconds

  /**
   * Polls a transaction's workflow 1 ms apart up to `within`: its outcome, or none when it is
   * unacknowledged, resolved, or still running at the bound (research R18; ankka has no await on a
   * workflow yet, so this is the one place to replace when it does).
   */
  def await(components: ComponentClient, provider: String, key: String, within: FiniteDuration = AwaitBound): Option[StoredAnswer] =
    val wf       = components.forWorkflow(BetWorkflow.id(provider, key))
    val deadline = System.nanoTime() + within.toNanos
    @tailrec def poll(): Option[StoredAnswer] =
      val state = wf.call(BetWorkflow.status).invoke()
      state.outcome match
        case Some(a) => Some(a)
        case None if state.status == BetFlow.Unacknowledged || state.status == BetFlow.Resolved => None
        case None if System.nanoTime() > deadline => None
        case None =>
          Thread.sleep(1)
          poll()
    poll()


  /** A registration time as the calendar day the provider wants (FR-018). */
  def calendarDay(text: String): String =
    Try(Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDate.toString)
      .orElse(Try(LocalDate.parse(text.take(10)).toString))
      .getOrElse(text.take(10))
