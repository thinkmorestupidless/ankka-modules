package io.eitheror.casino.reporting.intake

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}

import java.time.Instant

/** A payment's dispute: its amount and currency as opened, and how it closed. */
final case class Dispute(amount: Option[Long] = None, currency: Option[String] = None, openedAt: Option[Instant] = None,
    outcome: Option[String] = None, closedAt: Option[Instant] = None)

/**
 * One payment (data-model.md `Payment`, id `g<n>/<payment id>`): who, through which provider, how much, when it
 * started and how it ended (`captured`, `failed`, `expired`, `abandoned`), what was refunded and its dispute.
 */
final case class PaymentState(
    paymentId: String = "",
    player: String = "",
    provider: String = "",
    terminal: String = "",
    currency: String = "",
    amount: Long = 0,
    initiatedAt: Option[Instant] = None,
    outcome: Option[String] = None,
    endedAt: Option[Instant] = None,
    refunded: Long = 0,
    dispute: Option[Dispute] = None,
    seen: Vector[String] = Vector.empty
)

/** Every event names the payment, the player and the provider, so the day's fold needs no state. */
sealed trait PaymentEvent:
  def factId: String
  def paymentId: String
  def player: String
  def provider: String
  def at: Instant

final case class Initiated(factId: String, paymentId: String, player: String, provider: String, terminal: String,
    amount: Long, currency: String, at: Instant) extends PaymentEvent
final case class Captured(factId: String, paymentId: String, player: String, provider: String, amount: Long,
    currency: String, at: Instant) extends PaymentEvent
/** `failed` (with the provider's code), `expired` or `abandoned`. */
final case class Ended(factId: String, paymentId: String, player: String, provider: String, outcome: String, at: Instant,
    code: Option[String] = None) extends PaymentEvent
final case class Refunded(factId: String, paymentId: String, player: String, provider: String, amount: Long,
    currency: String, at: Instant) extends PaymentEvent
final case class DisputeOpened(factId: String, paymentId: String, player: String, provider: String, amount: Long,
    currency: String, reason: String, at: Instant) extends PaymentEvent
/**
 * A dispute's close. `amount` is the close's, else the opened dispute's; with neither it is absent and a lost
 * dispute counts as a chargeback by number with zero amount (research, *Found during implementation*, T009).
 * `currency` likewise, else the payment's.
 */
final case class DisputeClosed(factId: String, paymentId: String, player: String, provider: String, outcome: String,
    amount: Option[Long], currency: Option[String], at: Instant) extends PaymentEvent:
  def chargeback: Boolean = outcome == f.DisputeOutcome.Lost

object Payments:
  def event(s: PaymentState, factId: String, fact: f.PaymentsPaymentsFact): Option[PaymentEvent] =
    Option.unless(s.seen.contains(factId))(fact match
      case x: f.PaymentInitiated =>
        Initiated(factId, x.paymentId, x.playerId, x.paymentProvider, x.terminal, x.amountCents.value, x.currency.code, x.occurredAt)
      case x: f.PaymentCaptured =>
        Captured(factId, x.paymentId, x.playerId, x.paymentProvider, x.amountCents.value, x.currency.code, x.occurredAt)
      case x: f.PaymentFailed    => Ended(factId, x.paymentId, x.playerId, x.paymentProvider, "failed", x.occurredAt, Some(x.code))
      case x: f.PaymentExpired   => Ended(factId, x.paymentId, x.playerId, x.paymentProvider, "expired", x.occurredAt)
      case x: f.PaymentAbandoned => Ended(factId, x.paymentId, x.playerId, x.paymentProvider, "abandoned", x.occurredAt)
      case x: f.PaymentRefunded =>
        Refunded(factId, x.paymentId, x.playerId, x.paymentProvider, x.amountCents.value, x.currency.code, x.occurredAt)
      case x: f.PaymentDisputeOpened =>
        DisputeOpened(factId, x.paymentId, x.playerId, x.paymentProvider, x.amountCents.value, x.currency.code, x.reason, x.occurredAt)
      case x: f.PaymentDisputeClosed =>
        val opened = s.dispute
        DisputeClosed(factId, x.paymentId, x.playerId, x.paymentProvider, x.outcome,
          x.amountCents.map(_.value).orElse(opened.flatMap(_.amount)),
          x.currency.map(_.code).orElse(opened.flatMap(_.currency)).orElse(Option(s.currency).filter(_.nonEmpty)),
          x.occurredAt))

  def apply(s0: PaymentState, e: PaymentEvent): PaymentState =
    val s = s0.copy(paymentId = e.paymentId, player = e.player, provider = e.provider, seen = FactId.remember(s0.seen, e.factId))
    e match
      case i: Initiated => s.copy(terminal = i.terminal, amount = i.amount, currency = i.currency, initiatedAt = Some(i.at))
      case c: Captured =>
        s.copy(outcome = Some("captured"), endedAt = Some(c.at), amount = if s.amount == 0 then c.amount else s.amount,
          currency = if s.currency.isEmpty then c.currency else s.currency)
      case x: Ended    => s.copy(outcome = Some(x.outcome), endedAt = Some(x.at))
      case r: Refunded => s.copy(refunded = s.refunded + r.amount)
      case o: DisputeOpened =>
        s.copy(dispute = Some(s.dispute.getOrElse(Dispute()).copy(amount = Some(o.amount), currency = Some(o.currency), openedAt = Some(o.at))))
      case c: DisputeClosed =>
        s.copy(dispute = Some(s.dispute.getOrElse(Dispute()).copy(amount = c.amount, currency = c.currency,
          outcome = Some(c.outcome), closedAt = Some(c.at))))

/** A payment fact. The contract's codec; the entity stores only its events. */
final case class PaymentFact(factId: String, fact: f.PaymentsPaymentsFact)

final class Payment(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[PaymentState, PaymentEvent]:
  def emptyState: PaymentState = PaymentState()

  def applyEvent(event: PaymentEvent): PaymentState = Payments.apply(currentState, event)

  /** Whether the fact was new. */
  def record(c: PaymentFact): Effect[Boolean] =
    Payments.event(currentState, c.factId, c.fact).fold(effects.reply(false))(e => effects.persist(e).thenReply(_ => true))

  def get: ReadOnlyEffect[PaymentState] = effects.reply(currentState)

object Payment extends EventSourcedEntity.Companion[Payment, PaymentState, PaymentEvent](
      componentId = ComponentId("payment"),
      stateSerializer = ReportingCodecs.serializer[PaymentState]("payment"),
      eventSerializer = ReportingCodecs.serializer[PaymentEvent]("payment-event")
    ):
  given Serializer[PaymentFact] = Serializer.json[PaymentFact]("reporting.payment-fact")(using JsonCodecMaker.make[PaymentFact])
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, paymentId: String): String = Generation.id(generation, paymentId)

  def create(context: EventSourcedEntityContext) = new Payment(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

/** `payments.payments` into each payment's `Payment`. */
final class PaymentsFactsConsumer(client: ComponentClient, settings: Settings) extends Consumer[Option[f.PaymentsPaymentsFact], Nothing]:
  def onMessage(fact: Option[f.PaymentsPaymentsFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.PaymentsPayments.name, messageContext.subject)
      effects.ignore()
    case Some(p) =>
      client.forEventSourcedEntity(EntityId(Payment.id(settings.generation, p.paymentId))).call(Payment.record)
        .invoke(PaymentFact(FactId.of(f.PaymentsPayments, p), p)): Unit
      effects.done()

final class PaymentsFacts(settings: Settings) extends Consumer.Companion[PaymentsFactsConsumer, Option[f.PaymentsPaymentsFact], Nothing](
      Generation.componentId("payments-facts", settings.generation), Lenient.from(f.PaymentsPayments)):
  def create(ctx: ConsumerContext) = new PaymentsFactsConsumer(ctx.componentClient, settings)
