package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.GatewayCodecs
import io.eitheror.casino.pspgateway.providers.PayoutAnswer

import java.time.Instant

/**
 * What a payout is claimed with (data-model.md). The destination (IBAN, names, e-Transfer details, a
 * wallet address) goes to the provider and is not kept (FR-022).
 */
final case class PayoutClaim(
    brand: String,
    subBrandId: String,
    provider: Provider,
    terminalId: String,
    method: String,
    amount: Long,
    currency: Currency,
    webhookUrl: String,
    generated: Map[String, String] = Map.empty
)

/**
 * A payout session (FR-004), event sourced under the withdrawal request id. It is claimed before the
 * provider is called and answers a repeat with the recorded answer; an answer of `Completed` settles it
 * in the same command; `Reversed` may follow `Completed`, and nothing else follows a final outcome.
 */
final case class PayoutState(
    requestId: String,
    claim: Option[PayoutClaim] = None,
    answer: Option[PayoutAnswer] = None,
    outcome: Option[Outcome] = None,
    providerReference: Option[String] = None,
    providerStatus: Option[String] = None,
    settledAt: Option[Instant] = None,
    delivery: Delivery = Delivery.NotYet,
    attempts: Int = 0,
    redrives: Int = 0,
    redriving: Option[String] = None,
    updatedAt: Option[Instant] = None
):
  def exists: Boolean = claim.isDefined

  def receiver: Option[Receiver] = claim.map(c => Receiver.Payouts(c.brand, requestId))

  def deliverable: Boolean = outcome.isDefined && (delivery match
    case Delivery.Delivered(_, _)  => false
    case Delivery.Refused(_, _, _) => redriving.exists(_ != DepositSession.Timer)
    case _                         => true)

  /** Whether `next` may settle now: anything when unsettled, `Reversed` after `Completed`, else nothing. */
  def admits(next: Outcome): Boolean = (outcome, next) match
    case (None, _)                                  => true
    case (Some(Outcome.Completed), Outcome.Reversed) => true
    case _                                          => false

enum PayoutEvent:
  case Claimed(claim: PayoutClaim, at: Instant)
  case Answered(answer: PayoutAnswer, at: Instant)
  case Settled(outcome: Outcome, providerReference: Option[String], providerStatus: String, at: Instant)
  case DeliveryRecorded(delivery: Delivery, attempts: Int, at: Instant)
  case Redriven(by: String, at: Instant)

final case class ClaimPayout(claim: PayoutClaim, at: Instant)
final case class RecordAnswer(answer: PayoutAnswer, at: Instant)
final case class SettlePayout(outcome: Outcome, providerReference: Option[String], providerStatus: String, at: Instant)
final case class PayoutSettlement(settled: Boolean, state: PayoutState)

object PayoutCodecs:
  given Serializer[PayoutState]      = GatewayCodecs.serializer[PayoutState]("payout-state")
  given Serializer[PayoutEvent]      = GatewayCodecs.serializer[PayoutEvent]("payout-event")
  given Serializer[ClaimPayout]      = GatewayCodecs.serializer[ClaimPayout]("claim-payout")
  given Serializer[RecordAnswer]     = GatewayCodecs.serializer[RecordAnswer]("record-answer")
  given Serializer[SettlePayout]     = GatewayCodecs.serializer[SettlePayout]("settle-payout")
  given Serializer[PayoutSettlement] = GatewayCodecs.serializer[PayoutSettlement]("payout-settlement")

import PayoutCodecs.given
import DepositCodecs.given_Serializer_RecordDelivery
import DepositCodecs.given_Serializer_Redrive
import DepositCodecs.given_Serializer_Redriving

final class PayoutSession(context: EventSourcedEntityContext) extends EventSourcedEntity[PayoutState, PayoutEvent]:

  def emptyState: PayoutState = PayoutState(context.entityId)

  def applyEvent(event: PayoutEvent): PayoutState = PayoutSession.fold(currentState, event)

  private def known[R](f: PayoutState => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState) else effects.error("SESSION_NOT_FOUND", ErrorCode.NotFound)

  /**
   * Only on an empty session. A repeat replies with the current state; a repeat naming another brand
   * is refused, since a request id is unique only within a brand and the gateway is shared.
   */
  def claim(cmd: ClaimPayout): Effect[PayoutState] =
    currentState.claim match
      case None                                     => effects.persist(PayoutEvent.Claimed(cmd.claim, cmd.at)).thenReplyState
      case Some(c) if c.brand != cmd.claim.brand    => effects.error("REQUEST_OF_ANOTHER_BRAND", ErrorCode.Conflict)
      case Some(_)                                  => effects.reply(currentState)

  /** Once. `Completed` settles in the same command, so `payouts` is told by the deliverer as for a webhook. */
  def answered(cmd: RecordAnswer): Effect[PayoutState] =
    known { s =>
      if s.answer.isDefined then effects.reply(s)
      else cmd.answer match
        case PayoutAnswer.Completed(ref) =>
          effects.persist(PayoutEvent.Answered(cmd.answer, cmd.at),
            PayoutEvent.Settled(Outcome.Completed, ref, "completed", cmd.at)).thenReplyState
        case _ => effects.persist(PayoutEvent.Answered(cmd.answer, cmd.at)).thenReplyState
    }

  def settle(cmd: SettlePayout): Effect[PayoutSettlement] =
    known { s =>
      if s.admits(cmd.outcome) then
        effects.persist(PayoutEvent.Settled(cmd.outcome, cmd.providerReference, cmd.providerStatus, cmd.at))
          .thenReply(PayoutSettlement(true, _))
      else effects.reply(PayoutSettlement(false, s))
    }

  def recordDelivery(cmd: RecordDelivery): Effect[PayoutState] =
    known(s => if !s.deliverable then effects.reply(s)
      else effects.persist(PayoutEvent.DeliveryRecorded(cmd.delivery, cmd.attempts, cmd.at)).thenReplyState)

  def redrive(cmd: Redrive): Effect[Redriving] =
    known { s =>
      if DepositSession.redrivable(s.outcome.isDefined, s.delivery, cmd.by) then
        effects.persist(PayoutEvent.Redriven(cmd.by, cmd.at)).thenReply(n => Redriving(true, n.delivery))
      else effects.reply(Redriving(false, s.delivery))
    }

  def get: ReadOnlyEffect[PayoutState] = effects.reply(currentState)

object PayoutSession extends EventSourcedEntity.Companion[PayoutSession, PayoutState, PayoutEvent](
      componentId = ComponentId("payout-session"),
      stateSerializer = PayoutCodecs.given_Serializer_PayoutState,
      eventSerializer = PayoutCodecs.given_Serializer_PayoutEvent
    ):
  def create(context: EventSourcedEntityContext) = new PayoutSession(context)

  val claim          = command("claim")(_.claim)
  val answered       = command("answered")(_.answered)
  val settle         = command("settle")(_.settle)
  val recordDelivery = command("record-delivery")(_.recordDelivery)
  val redrive        = command("redrive")(_.redrive)
  val get            = query("get")(_.get)

  def fold(s: PayoutState, event: PayoutEvent): PayoutState = event match
    case PayoutEvent.Claimed(c, at)  => s.copy(claim = Some(c), updatedAt = Some(at))
    case PayoutEvent.Answered(a, at) =>
      val ref = a match
        case PayoutAnswer.Initiated(r) => r
        case PayoutAnswer.Completed(r) => r
        case _                         => None
      s.copy(answer = Some(a), providerReference = ref.orElse(s.providerReference), updatedAt = Some(at))
    case PayoutEvent.Settled(o, ref, status, at) =>
      s.copy(outcome = Some(o), providerReference = ref.orElse(s.providerReference), providerStatus = Some(status),
        settledAt = Some(at), delivery = Delivery.NotYet, redriving = None, updatedAt = Some(at))
    case PayoutEvent.DeliveryRecorded(d, n, at) => s.copy(delivery = d, attempts = s.attempts + n, redriving = None, updatedAt = Some(at))
    case PayoutEvent.Redriven(by, at)           => s.copy(redrives = s.redrives + 1, redriving = Some(by), updatedAt = Some(at))
