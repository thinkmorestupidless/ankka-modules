package io.eitheror.casino.reporting.intake

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}

import java.time.Instant

/**
 * One withdrawal request (data-model.md `Withdrawal`, id `g<n>/<payout id>`): who, through which gateway and
 * terminal, how much, when it was requested, approved and ended, and how (`completed`, `cancelled`, `failed`,
 * `reversed`). A request not ended is a pending hold (research R7).
 */
final case class WithdrawalState(
    payoutId: String = "",
    player: String = "",
    gateway: String = "",
    terminal: String = "",
    amount: Long = 0,
    currency: String = "",
    requestedAt: Option[Instant] = None,
    approvedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    outcome: Option[String] = None,
    seen: Vector[String] = Vector.empty
):
  def pending: Boolean = requestedAt.isDefined && outcome.isEmpty

/** Every event names the request, the player, the amount, the currency and the gateway, so the day's fold needs no state. */
sealed trait WithdrawalEvent:
  def factId: String
  def payoutId: String
  def player: String
  def amount: Long
  def currency: String
  def gateway: String
  def at: Instant

object WithdrawalEvent:
  final case class Requested(factId: String, payoutId: String, player: String, amount: Long, currency: String,
      gateway: String, terminal: String, at: Instant) extends WithdrawalEvent
  final case class Approved(factId: String, payoutId: String, player: String, amount: Long, currency: String,
      gateway: String, at: Instant) extends WithdrawalEvent
  /** `requestedAt`, when the request was seen, for the hours to completion (FR-011). */
  final case class Completed(factId: String, payoutId: String, player: String, amount: Long, currency: String,
      gateway: String, at: Instant, requestedAt: Option[Instant] = None) extends WithdrawalEvent
  final case class Cancelled(factId: String, payoutId: String, player: String, amount: Long, currency: String,
      gateway: String, at: Instant) extends WithdrawalEvent
  final case class Failed(factId: String, payoutId: String, player: String, amount: Long, currency: String,
      gateway: String, at: Instant) extends WithdrawalEvent
  final case class Reversed(factId: String, payoutId: String, player: String, amount: Long, currency: String,
      gateway: String, at: Instant) extends WithdrawalEvent

object Withdrawals:
  import WithdrawalEvent.*

  /** The gateway a fact names, else the one the request named, else its terminal. */
  private def gateway(s: WithdrawalState, named: Option[String], terminal: Option[String]): String =
    named.filter(_.nonEmpty).orElse(Option(s.gateway).filter(_.nonEmpty)).orElse(terminal).orElse(Option(s.terminal).filter(_.nonEmpty)).getOrElse("")

  def event(s: WithdrawalState, factId: String, fact: f.CasinoWithdrawalsFact): Option[WithdrawalEvent] =
    Option.unless(s.seen.contains(factId))(fact match
      case x: f.WithdrawalRequested =>
        Requested(factId, x.payoutId, x.playerId, x.amountCents.value, x.currency.code, gateway(s, x.gateway, Some(x.terminal)), x.terminal, x.occurredAt)
      case x: f.WithdrawalApproved =>
        Approved(factId, x.payoutId, x.playerId, x.amountCents.value, x.currency.code, gateway(s, x.gateway, x.terminal), x.occurredAt)
      case x: f.WithdrawalCompleted =>
        Completed(factId, x.payoutId, x.playerId, x.amountCents.value, x.currency.code, gateway(s, x.gateway, x.terminal), x.occurredAt, s.requestedAt)
      case x: f.WithdrawalCancelled =>
        Cancelled(factId, x.payoutId, x.playerId, x.amountCents.value, x.currency.code, gateway(s, x.gateway, x.terminal), x.occurredAt)
      case x: f.WithdrawalFailed =>
        Failed(factId, x.payoutId, x.playerId, x.amountCents.value, x.currency.code, gateway(s, x.gateway, x.terminal), x.occurredAt)
      case x: f.WithdrawalReversed =>
        Reversed(factId, x.payoutId, x.playerId, x.amountCents.value, x.currency.code, gateway(s, x.gateway, x.terminal), x.occurredAt))

  def apply(s0: WithdrawalState, e: WithdrawalEvent): WithdrawalState =
    val s = s0.copy(payoutId = e.payoutId, player = e.player, amount = e.amount, currency = e.currency,
      gateway = if e.gateway.nonEmpty then e.gateway else s0.gateway, seen = FactId.remember(s0.seen, e.factId))
    e match
      case r: Requested => s.copy(terminal = r.terminal, requestedAt = Some(r.at))
      case a: Approved  => s.copy(approvedAt = Some(a.at))
      case c: Completed => s.copy(endedAt = Some(c.at), outcome = Some("completed"))
      case c: Cancelled => s.copy(endedAt = Some(c.at), outcome = Some("cancelled"))
      case x: Failed    => s.copy(endedAt = Some(x.at), outcome = Some("failed"))
      case x: Reversed  => s.copy(endedAt = Some(x.at), outcome = Some("reversed"))

/** A withdrawal fact. The contract's codec; the entity stores only its events. */
final case class WithdrawalFact(factId: String, fact: f.CasinoWithdrawalsFact)

final class Withdrawal(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[WithdrawalState, WithdrawalEvent]:
  def emptyState: WithdrawalState = WithdrawalState()

  def applyEvent(event: WithdrawalEvent): WithdrawalState = Withdrawals.apply(currentState, event)

  /** Whether the fact was new. */
  def record(c: WithdrawalFact): Effect[Boolean] =
    Withdrawals.event(currentState, c.factId, c.fact).fold(effects.reply(false))(e => effects.persist(e).thenReply(_ => true))

  def get: ReadOnlyEffect[WithdrawalState] = effects.reply(currentState)

object Withdrawal extends EventSourcedEntity.Companion[Withdrawal, WithdrawalState, WithdrawalEvent](
      componentId = ComponentId("withdrawal"),
      stateSerializer = ReportingCodecs.serializer[WithdrawalState]("withdrawal"),
      eventSerializer = ReportingCodecs.serializer[WithdrawalEvent]("withdrawal-event")
    ):
  given Serializer[WithdrawalFact] =
    Serializer.json[WithdrawalFact]("reporting.withdrawal-fact")(using JsonCodecMaker.make[WithdrawalFact])
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, payoutId: String): String = Generation.id(generation, payoutId)

  def create(context: EventSourcedEntityContext) = new Withdrawal(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

/** `casino.withdrawals` into each request's `Withdrawal`. */
final class WithdrawalsFactsConsumer(client: ComponentClient, settings: Settings)
    extends Consumer[Option[f.CasinoWithdrawalsFact], Nothing]:
  private def payoutId(w: f.CasinoWithdrawalsFact): String = w match
    case x: f.WithdrawalRequested => x.payoutId
    case x: f.WithdrawalApproved  => x.payoutId
    case x: f.WithdrawalCompleted => x.payoutId
    case x: f.WithdrawalCancelled => x.payoutId
    case x: f.WithdrawalFailed    => x.payoutId
    case x: f.WithdrawalReversed  => x.payoutId

  def onMessage(fact: Option[f.CasinoWithdrawalsFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.CasinoWithdrawals.name, messageContext.subject)
      effects.ignore()
    case Some(w) =>
      client.forEventSourcedEntity(EntityId(Withdrawal.id(settings.generation, payoutId(w)))).call(Withdrawal.record)
        .invoke(WithdrawalFact(FactId.of(f.CasinoWithdrawals, w), w)): Unit
      effects.done()

final class WithdrawalsFacts(settings: Settings)
    extends Consumer.Companion[WithdrawalsFactsConsumer, Option[f.CasinoWithdrawalsFact], Nothing](
      Generation.componentId("withdrawals-facts", settings.generation), Lenient.from(f.CasinoWithdrawals)):
  def create(ctx: ConsumerContext) = new WithdrawalsFactsConsumer(ctx.componentClient, settings)
