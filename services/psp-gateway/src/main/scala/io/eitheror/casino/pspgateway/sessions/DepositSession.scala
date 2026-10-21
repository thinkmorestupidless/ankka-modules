package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.GatewayCodecs

import java.time.Instant

/**
 * What the initiate claims a deposit with (data-model.md): the payment's facts, none personal and no
 * credential. `generated` is what the gateway made before the call (Cozeni's salt, Morphosis's token);
 * the receiver is fixed here and never read from a webhook (FR-017).
 */
final case class DepositClaim(
    provider: Provider,
    method: String,
    subBrandId: String,
    terminalId: Option[String],
    customerId: String,
    amount: Long,
    currency: Currency,
    returnUrl: String,
    webhookUrl: String,
    generated: Map[String, String] = Map.empty
):
  def receiver(paymentId: String): Receiver = Receiver.Merchant(paymentId)

/** What the provider's initiation gave: its reference, where the player goes, what later reads need. */
final case class Initiation(reference: Option[String], destination: Destination, providerData: Map[String, String], at: Instant)

/** A refused initiation as it was answered, so a repeat answers the same (contracts/merchant.md §1). */
final case class Refusal(status: Int, error: String, message: Option[String] = None, provider: Option[String] = None)

enum Stage:
  case Empty, Claimed, Initiated, InitiationFailed, Settled

/**
 * A deposit session (FR-008), event sourced under `merchant`'s payment id. It is claimed before the
 * provider is called, so a repeat initiate calls no provider; it settles once, so two webhooks cannot
 * both settle it (SC-002); its delivery to `merchant` is recorded attempt by attempt (FR-015, FR-016).
 */
final case class DepositState(
    paymentId: String,
    claim: Option[DepositClaim] = None,
    initiation: Option[Initiation] = None,
    refusal: Option[Refusal] = None,
    outcome: Option[Outcome] = None,
    providerStatus: Option[String] = None,
    settledAt: Option[Instant] = None,
    delivery: Delivery = Delivery.NotYet,
    attempts: Int = 0,
    redrives: Int = 0,
    /** Who asked for the re-drive not yet delivered: `timer` or an operator's id. */
    redriving: Option[String] = None,
    updatedAt: Option[Instant] = None
):
  def stage: Stage =
    if outcome.isDefined then Stage.Settled
    else if refusal.isDefined then Stage.InitiationFailed
    else if initiation.isDefined then Stage.Initiated
    else if claim.isDefined then Stage.Claimed
    else Stage.Empty

  def exists: Boolean = claim.isDefined

  /** Whether a delivery may be recorded now: settled, not delivered, and not refused unless re-driven. */
  def deliverable: Boolean = outcome.isDefined && (delivery match
    case Delivery.Delivered(_, _)  => false
    case Delivery.Refused(_, _, _) => redriving.exists(_ != DepositSession.Timer)
    case _                         => true)

  /** The 004 contract's status read (contracts/merchant.md §2). */
  def status: SessionStatus = SessionStatus.of(stage, outcome, delivery, updatedAt)

/**
 * `state` is `resolved` once there is an outcome or the initiation was refused. `undelivered` is true
 * while an outcome has not reached `merchant` (in flight or given up), so `merchant` waits another
 * deadline rather than expiring a payment the provider took; `refused` is additive (finding 7).
 */
final case class SessionStatus(state: String, outcome: Option[String], delivered: Boolean, undelivered: Boolean,
    refused: Boolean, updatedAt: Option[Instant])

object SessionStatus:
  def of(stage: Stage, outcome: Option[Outcome], delivery: Delivery, updatedAt: Option[Instant]): SessionStatus =
    val resolved = stage == Stage.Settled || stage == Stage.InitiationFailed
    val word = outcome.map {
      case Outcome.Captured(_)      => "captured"
      case Outcome.Failed(_, _, _)  => "failed"
      case o                        => o.word
    }
    val (delivered, undelivered, refused) = delivery match
      case Delivery.Delivered(_, _)          => (true, false, false)
      case Delivery.Refused(_, _, _)         => (false, false, true)
      case Delivery.Undelivered(_, _, _)     => (false, true, false)
      case Delivery.NotYet                   => (false, outcome.isDefined, false)
    SessionStatus(if resolved then "resolved" else "pending", word, delivered, undelivered, refused, updatedAt)

enum DepositEvent:
  case Claimed(claim: DepositClaim, at: Instant)
  case Initiated(initiation: Initiation)
  case InitiationFailed(refusal: Refusal, at: Instant)
  case Settled(outcome: Outcome, providerStatus: String, at: Instant)
  case DeliveryRecorded(delivery: Delivery, attempts: Int, at: Instant)
  case Redriven(by: String, at: Instant)

final case class Claim(claim: DepositClaim, at: Instant)
final case class RecordInitiation(initiation: Initiation)
final case class FailInitiation(refusal: Refusal, at: Instant)
final case class Settle(outcome: Outcome, providerStatus: String, at: Instant)
final case class RecordDelivery(delivery: Delivery, attempts: Int, at: Instant)
final case class Redrive(by: String, at: Instant)

/** A settle's answer: whether this one settled, and the state either way (`AlreadySettled` when not). */
final case class Settlement(settled: Boolean, state: DepositState)

/** A re-drive's answer: whether it was taken, and the delivery state either way (FR-016). */
final case class Redriving(redriven: Boolean, delivery: Delivery)

object DepositCodecs:
  given Serializer[DepositState]    = GatewayCodecs.serializer[DepositState]("deposit-state")
  given Serializer[DepositEvent]    = GatewayCodecs.serializer[DepositEvent]("deposit-event")
  given Serializer[Claim]           = GatewayCodecs.serializer[Claim]("claim-deposit")
  given Serializer[RecordInitiation] = GatewayCodecs.serializer[RecordInitiation]("record-initiation")
  given Serializer[FailInitiation]  = GatewayCodecs.serializer[FailInitiation]("fail-initiation")
  given Serializer[Settle]          = GatewayCodecs.serializer[Settle]("settle")
  given Serializer[Settlement]      = GatewayCodecs.serializer[Settlement]("settlement")
  given Serializer[RecordDelivery]  = GatewayCodecs.serializer[RecordDelivery]("record-delivery")
  given Serializer[Redrive]         = GatewayCodecs.serializer[Redrive]("redrive")
  given Serializer[Redriving]       = GatewayCodecs.serializer[Redriving]("redriving")
  given Serializer[SessionStatus]   = GatewayCodecs.serializer[SessionStatus]("session-status")

import DepositCodecs.given

final class DepositSession(context: EventSourcedEntityContext) extends EventSourcedEntity[DepositState, DepositEvent]:

  def emptyState: DepositState = DepositState(context.entityId)

  def applyEvent(event: DepositEvent): DepositState = DepositSession.fold(currentState, event)

  private def known[R](f: DepositState => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState) else effects.error("SESSION_NOT_FOUND", ErrorCode.NotFound)

  /** Only on an empty session; a second claim replies with the current state and persists nothing (FR-008). */
  def claim(cmd: Claim): Effect[DepositState] =
    if currentState.exists then effects.reply(currentState)
    else effects.persist(DepositEvent.Claimed(cmd.claim, cmd.at)).thenReplyState

  def initiated(cmd: RecordInitiation): Effect[DepositState] =
    known(s => if s.stage != Stage.Claimed then effects.reply(s) else effects.persist(DepositEvent.Initiated(cmd.initiation)).thenReplyState)

  /** Only from claimed, and final: the session settles nothing after it (FR-024). */
  def initiationFailed(cmd: FailInitiation): Effect[DepositState] =
    known(s => if s.stage != Stage.Claimed then effects.reply(s) else effects.persist(DepositEvent.InitiationFailed(cmd.refusal, cmd.at)).thenReplyState)

  /** From claimed or initiated, once (SC-002). */
  def settle(cmd: Settle): Effect[Settlement] =
    known { s =>
      s.stage match
        case Stage.Claimed | Stage.Initiated =>
          effects.persist(DepositEvent.Settled(cmd.outcome, cmd.providerStatus, cmd.at)).thenReply(Settlement(true, _))
        case Stage.InitiationFailed => effects.error("INITIATION_FAILED", ErrorCode.Conflict)
        case _                      => effects.reply(Settlement(false, s))
    }

  /** Only after settling; ignored once delivered, or refused and not re-driven by an operator. */
  def recordDelivery(cmd: RecordDelivery): Effect[DepositState] =
    known(s => if !s.deliverable then effects.reply(s)
      else effects.persist(DepositEvent.DeliveryRecorded(cmd.delivery, cmd.attempts, cmd.at)).thenReplyState)

  /** By the timer while undelivered; by an operator while undelivered or refused (FR-016). */
  def redrive(cmd: Redrive): Effect[Redriving] =
    known { s =>
      if DepositSession.redrivable(s.outcome.isDefined, s.delivery, cmd.by) then
        effects.persist(DepositEvent.Redriven(cmd.by, cmd.at)).thenReply(n => Redriving(true, n.delivery))
      else effects.reply(Redriving(false, s.delivery))
    }

  def get: ReadOnlyEffect[DepositState] = effects.reply(currentState)

  def status: ReadOnlyEffect[SessionStatus] =
    if currentState.exists then effects.reply(currentState.status) else effects.error("SESSION_NOT_FOUND", ErrorCode.NotFound)

object DepositSession extends EventSourcedEntity.Companion[DepositSession, DepositState, DepositEvent](
      componentId = ComponentId("deposit-session"),
      stateSerializer = DepositCodecs.given_Serializer_DepositState,
      eventSerializer = DepositCodecs.given_Serializer_DepositEvent
    ):
  def create(context: EventSourcedEntityContext) = new DepositSession(context)

  /** The re-drive's `by` when the `Redrive` timer asks. */
  val Timer = "timer"

  val claim            = command("claim")(_.claim)
  val initiated        = command("initiated")(_.initiated)
  val initiationFailed = command("initiation-failed")(_.initiationFailed)
  val settle           = command("settle")(_.settle)
  val recordDelivery   = command("record-delivery")(_.recordDelivery)
  val redrive          = command("redrive")(_.redrive)
  val get              = query("get")(_.get)
  val status           = query("status")(_.status)

  def redrivable(settled: Boolean, delivery: Delivery, by: String): Boolean =
    settled && (delivery match
      case Delivery.Undelivered(_, _, _) => true
      case Delivery.Refused(_, _, _)     => by != Timer
      case _                             => false)

  def fold(s: DepositState, event: DepositEvent): DepositState = event match
    case DepositEvent.Claimed(c, at)               => s.copy(claim = Some(c), updatedAt = Some(at))
    case DepositEvent.Initiated(i)                 => s.copy(initiation = Some(i), updatedAt = Some(i.at))
    case DepositEvent.InitiationFailed(r, at)      => s.copy(refusal = Some(r), updatedAt = Some(at))
    case DepositEvent.Settled(o, status, at)       => s.copy(outcome = Some(o), providerStatus = Some(status), settledAt = Some(at), updatedAt = Some(at))
    case DepositEvent.DeliveryRecorded(d, n, at)   => s.copy(delivery = d, attempts = s.attempts + n, redriving = None, updatedAt = Some(at))
    case DepositEvent.Redriven(by, at)             => s.copy(redrives = s.redrives + 1, redriving = Some(by), updatedAt = Some(at))
