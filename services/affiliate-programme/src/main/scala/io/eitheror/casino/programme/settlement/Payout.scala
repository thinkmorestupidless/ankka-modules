package io.eitheror.casino.programme.settlement

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.commission.Claim

import java.time.Instant

/** An approval a later close made stale: who approved what total, and when (FR-019). */
final case class Superseded(by: String, at: Instant, total: Long, supersededAt: Instant)

/**
 * One affiliate's payout at a brand in one currency for one period (data-model.md *Payout*), id
 * `<affiliate>/<brand>/<currency>/<period>`. `status` is none until a close opens it.
 */
final case class PayoutState(
    affiliate: String = "",
    brand: String = "",
    currency: String = "",
    period: String = "",
    status: Option[String] = None,
    figures: Claim.Figures = Claim.Figures(),
    approvedBy: Option[String] = None,
    approvedAt: Option[Instant] = None,
    rejectedBy: Option[String] = None,
    rejectedReason: Option[String] = None,
    paidBy: Option[String] = None,
    paidAt: Option[Instant] = None,
    supersededApprovals: Vector[Superseded] = Vector.empty,
    openedAt: Option[Instant] = None
):
  def exists: Boolean = status.isDefined
  def total: Long     = figures.total

final case class OpenPayout(affiliate: String, brand: String, currency: String, period: String, figures: Claim.Figures, at: Instant)
final case class ApprovePayout(by: String, at: Instant)
final case class RejectPayout(by: String, reason: Option[String], at: Instant)
final case class PayPayout(by: String, at: Instant)

object Payout extends EventSourcedEntity.Companion[Payout, PayoutState, Payout.Event](
      componentId = ComponentId("payout"),
      stateSerializer = PayoutCodecs.state,
      eventSerializer = PayoutCodecs.event
    ):
  import PayoutCodecs.given

  val Pending  = "pending"
  val Approved = "approved"
  val Rejected = "rejected"
  val Paid     = "paid"

  sealed trait Event
  final case class Opened(affiliate: String, brand: String, currency: String, period: String, figures: Claim.Figures, at: Instant) extends Event
  final case class FiguresChanged(figures: Claim.Figures, at: Instant) extends Event
  /** An approved payout a close changed: back to pending, the approval kept as superseded. */
  final case class Reverted(figures: Claim.Figures, superseded: Superseded) extends Event
  final case class PayoutApproved(by: String, at: Instant) extends Event
  final case class PayoutRejected(by: String, reason: Option[String], at: Instant) extends Event
  final case class PayoutPaid(by: String, at: Instant) extends Event

  def next(s: PayoutState, e: Event): PayoutState = e match
    case Opened(aff, brand, cur, period, figures, at) =>
      PayoutState(aff, brand, cur, period, Some(Pending), figures, openedAt = Some(at))
    case FiguresChanged(figures, _) => s.copy(figures = figures)
    case Reverted(figures, sup) =>
      s.copy(status = Some(Pending), figures = figures, approvedBy = None, approvedAt = None, supersededApprovals = s.supersededApprovals :+ sup)
    case PayoutApproved(by, at)         => s.copy(status = Some(Approved), approvedBy = Some(by), approvedAt = Some(at))
    case PayoutRejected(by, reason, _)  => s.copy(status = Some(Rejected), rejectedBy = Some(by), rejectedReason = reason)
    case PayoutPaid(by, at)             => s.copy(status = Some(Paid), paidBy = Some(by), paidAt = Some(at))

  /** The original's refusal (`S/OperatorRoutes.scala:3074-3090`), statuses as the programme spells them. */
  def mustBe(from: String, current: Option[String]): String = s"must be $from, currently ${current.getOrElse("none")}"

  def idOf(affiliate: String, brand: String, currency: String, period: String): String = s"$affiliate/$brand/$currency/$period"
  def entityId(affiliate: String, brand: String, currency: String, period: String): EntityId =
    EntityId(idOf(affiliate, brand, currency, period))

  def create(context: EventSourcedEntityContext) = new Payout(context)

  val open    = command("open")(_.open)
  val approve = command("approve")(_.approve)
  val reject  = command("reject")(_.reject)
  val pay     = command("paid")(_.pay)
  val get     = query("get")(_.get)

object PayoutCodecs:
  val state: Serializer[PayoutState]  = ProgrammeCodecs.serializer[PayoutState]("payout")
  val event: Serializer[Payout.Event] = ProgrammeCodecs.serializer[Payout.Event]("payout-event")
  given Serializer[OpenPayout]    = ProgrammeCodecs.serializer[OpenPayout]("open-payout")
  given Serializer[ApprovePayout] = ProgrammeCodecs.serializer[ApprovePayout]("approve-payout")
  given Serializer[RejectPayout]  = ProgrammeCodecs.serializer[RejectPayout]("reject-payout")
  given Serializer[PayPayout]     = ProgrammeCodecs.serializer[PayPayout]("pay-payout")

import Payout.*

/**
 * The payout's status machine (settlement.feature): a close opens it pending; pending → approved or
 * rejected; approved → paid. A close that changes an approved payout's total reverts it to pending and
 * keeps the approval as superseded (FR-019). A paid payout never changes. Anything else is refused,
 * naming the status.
 */
final class Payout(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[PayoutState, Payout.Event]:
  def emptyState: PayoutState = PayoutState()

  def applyEvent(event: Payout.Event): PayoutState = Payout.next(currentState, event)

  def open(c: OpenPayout): Effect[PayoutState] =
    val s = currentState
    s.status match
      case None => effects.persist(Opened(c.affiliate, c.brand, c.currency, c.period, c.figures, c.at)).thenReplyState
      case Some(Pending) =>
        if s.figures == c.figures then effects.reply(s) else effects.persist(FiguresChanged(c.figures, c.at)).thenReplyState
      case Some(Approved) =>
        if s.figures == c.figures then effects.reply(s)
        else
          val sup = Superseded(s.approvedBy.getOrElse(""), s.approvedAt.getOrElse(c.at), s.total, c.at)
          effects.persist(Reverted(c.figures, sup)).thenReplyState
      case current => effects.error(mustBe(s"$Pending or $Approved", current), ErrorCode.Conflict)

  def approve(c: ApprovePayout): Effect[PayoutState] =
    if currentState.status.contains(Pending) then effects.persist(PayoutApproved(c.by, c.at)).thenReplyState
    else effects.error(mustBe(Pending, currentState.status), ErrorCode.Conflict)

  def reject(c: RejectPayout): Effect[PayoutState] =
    if currentState.status.contains(Pending) then effects.persist(PayoutRejected(c.by, c.reason, c.at)).thenReplyState
    else effects.error(mustBe(Pending, currentState.status), ErrorCode.Conflict)

  def pay(c: PayPayout): Effect[PayoutState] =
    if currentState.status.contains(Approved) then effects.persist(PayoutPaid(c.by, c.at)).thenReplyState
    else effects.error(mustBe(Approved, currentState.status), ErrorCode.Conflict)

  def get: ReadOnlyEffect[PayoutState] = effects.reply(currentState)
