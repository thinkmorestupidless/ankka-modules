package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.commission.{Claim, Earnings}
import io.eitheror.casino.programme.csv.Export
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.ledger.{AccountState, CommissionAccount, PayClaims, RecordAdjustment}
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.settlement.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** What the settlement routes accept and answer (contracts/routes.md O28, O40 to O42, M5), money in minor units. */
object SettlementAnswers:
  final case class StatusBody(status: Option[String] = None, overrideCommission: Option[Long] = None, reason: Option[String] = None)
  final case class AdjustmentBody(`type`: Option[String] = None, amount: Option[Long] = None, currency: Option[String] = None,
      reason: Option[String] = None)

  final case class Closed(period: String, created: Int, payouts: Vector[ClosedPayout])
  final case class Closing(workflowId: String, period: String)
  final case class Adjusted(adjustmentId: String, affiliateId: String, brandId: String, `type`: String, amount: Long, currency: String,
      reason: String, createdAt: Instant)

  /** A payout as the routes list it: its figures flat under the original's names, `payoutId` the one O42 takes. */
  final case class PayoutAnswer(
      payoutId: String,
      affiliateId: String,
      brandId: String,
      currency: String,
      period: String,
      status: String,
      cpaCommission: Long,
      revShareCommission: Long,
      overrideCommission: Long,
      adjustments: Long,
      totalPayable: Long,
      createdAt: Option[Instant],
      approvedBy: Option[String],
      approvedAt: Option[Instant],
      rejectedBy: Option[String],
      rejectionReason: Option[String],
      paidBy: Option[String],
      paidAt: Option[Instant],
      supersededApprovals: Vector[Superseded]
  )

  def of(p: PayoutState): PayoutAnswer =
    PayoutAnswer(Payouts.wireId(p.affiliate, p.currency, p.period), p.affiliate, p.brand, p.currency, p.period, p.status.getOrElse(""),
      p.figures.cpa, p.figures.revShare, p.figures.overrideAmount, p.figures.adjustments, p.total, p.openedAt, p.approvedBy, p.approvedAt,
      p.rejectedBy, p.rejectedReason, p.paidBy, p.paidAt, p.supersededApprovals)

  given JsonValueCodec[StatusBody]          = ProgrammeCodecs.wire[StatusBody]
  given JsonValueCodec[AdjustmentBody]      = ProgrammeCodecs.wire[AdjustmentBody]
  given JsonValueCodec[Closed]              = ProgrammeCodecs.wire[Closed]
  given JsonValueCodec[Closing]             = ProgrammeCodecs.wire[Closing]
  given JsonValueCodec[Adjusted]            = ProgrammeCodecs.wire[Adjusted]
  given JsonValueCodec[Vector[PayoutAnswer]] = ProgrammeCodecs.wire[Vector[PayoutAnswer]]
  given JsonValueCodec[PayoutAnswer]        = ProgrammeCodecs.wire[PayoutAnswer]

import SettlementAnswers.{*, given}

/** The settlement routes' work (US4): closing a period, a payout's transitions, adjustments and the export. */
final class Settlement(api: Api):
  private val networks = Networks(api)

  /**
   * O40: starts the period's close, or joins the one running (FR-020), and answers what it wrote when it
   * ends; one still running after 10 s is answered `202` with its id, and goes on.
   */
  def close(brand: String, period: Option[String], by: String): Respond[Bytes] =
    period.filter(Periods.isPeriod) match
      case None => refuse(400, Codes.InvalidPeriod, "period is required, yyyy-MM")
      case Some(_) if !api.brand(brand).exists => refuse(404, Codes.BrandUnknown, brand)
      case Some(p) =>
        val run = api.components.forKeyValueEntity(CloseRun.entityId(brand, p)).call(CloseRun.begin).invoke().run
        val id  = Close.entityId(brand, p, run)
        val end = Periods.periodEnd(p, api.brand(brand).zone).minusMillis(1)
        val workflow = api.components.forWorkflow(id)
        val carried = if run <= 1 then Vector.empty
          else api.components.forWorkflow(Close.entityId(brand, p, run - 1)).call(Close.get).invoke().payouts
        workflow.call(Close.start).invoke(StartClose(brand, p, run, end, by, api.now(), carried)): Unit
        try
          val s = workflow.awaitEnd(Close, 10.seconds)
          ok(Closed(p, s.payouts.size, s.payouts))
        catch case e: CommandError if e.code == ErrorCode.Timeout => answer(202, Closing(id.toString, p))

  /** O41: the brand's payouts for a period. */
  def list(brand: String, period: Option[String]): Respond[Bytes] =
    period.filter(Periods.isPeriod) match
      case None => refuse(400, Codes.InvalidPeriod, "period is required, yyyy-MM")
      case Some(p) =>
        ok(api.views.forView(Payouts).ask(Payouts.byBrandAndPeriod, "brand" -> brand, "period" -> p).map(SettlementAnswers.of))

  /** M5: an affiliate's payouts at the brands they hold. */
  def of(affiliate: String, brands: Vector[String]): Respond[Bytes] =
    ok(api.views.forView(Payouts).ask(Payouts.byAffiliate, "affiliate" -> affiliate).filter(p => brands.contains(p.brand)).map(SettlementAnswers.of))

  /**
   * O42 (FR-019): pending → approved or rejected, approved → paid; anything else `409 status_transition`
   * naming the status. An approval's override figure is written as the adjustment the payout claims, the
   * difference from the override it claims, so its total stays the fold of what it claims.
   */
  def transition(brand: String, wireId: String, b: StatusBody, by: String): Respond[Bytes] =
    val target = b.status.map(_.toLowerCase)
    Payouts.fromWire(brand, wireId).map(id => id -> payout(id)).filter(_._2.exists) match
      case None => refuse(404, "payout_not_found", wireId)
      case Some(_) if target.isEmpty => invalid("status is required")
      case Some(_) if !target.exists(Set(Payout.Pending, Payout.Approved, Payout.Rejected, Payout.Paid)) => invalid(s"invalid status ${b.status.get}")
      case Some((id, p)) =>
        // Only a close moves a payout to pending (an approved one whose total changed): asked for here, it is refused as any other.
        val from = target.get match
          case Payout.Paid    => Some(Payout.Approved)
          case Payout.Pending => None
          case _              => Some(Payout.Pending)
        if !from.exists(p.status.contains) then
          refuse(409, Codes.StatusTransition, s"cannot move payout $wireId from ${p.status.getOrElse("none")} to ${target.get}")
        else
          val entity  = api.components.forEventSourcedEntity(id)
          val payoutId = id.toString
          val account = api.components.forEventSourcedEntity(CommissionAccount.entityId(p.affiliate, brand, p.currency))
          val after = target.get match
            case Payout.Approved =>
              b.overrideCommission.foreach(figure => overrideFigure(p, payoutId, figure, by))
              entity.call(Payout.approve).invoke(ApprovePayout(by, api.now()))
            case Payout.Rejected => entity.call(Payout.reject).invoke(RejectPayout(by, b.reason, api.now()))
            case _ =>
              account.call(CommissionAccount.payClaims).invoke(PayClaims(payoutId, p.figures)): Unit
              entity.call(Payout.pay).invoke(PayPayout(by, api.now()))
          networks.audit(p.affiliate, Some(brand), s"PAYOUT_${target.get.toUpperCase}", by,
            Map("payoutId" -> wireId, "period" -> p.period, "total" -> after.total.toString) ++ b.reason.map("reason" -> _))
          ok(SettlementAnswers.of(after))

  /** The override figure as an adjustment the payout claims, then the payout's figures brought up to date with it. */
  private def overrideFigure(p: PayoutState, payoutId: String, figure: Long, by: String): Unit =
    val account = api.components.forEventSourcedEntity(CommissionAccount.entityId(p.affiliate, p.brand, p.currency))
    val prefix  = s"override-figure/$payoutId/"
    val before  = account.call(CommissionAccount.get).invoke()
    val prior   = before.unsettled.filter(_.id.startsWith(prefix))
    val delta   = figure - p.figures.overrideAmount - prior.map(_.amount).sum
    if delta != 0 then
      val zone = api.brand(p.brand).zone
      val written = account.call(CommissionAccount.recordAdjustment).invoke(
        RecordAdjustment(s"$prefix${prior.size + 1}", delta, "override figure", by, Periods.periodEnd(p.period, zone).minusMillis(1),
          Some(payoutId), zone.getId, api.now(), Some(Earnings.OverrideFigure)))
      api.components.forEventSourcedEntity(EntityId(payoutId)).call(Payout.open)
        .invoke(OpenPayout(p.affiliate, p.brand, p.currency, p.period, claimedBy(written, payoutId), api.now())): Unit

  private def claimedBy(s: AccountState, payoutId: String): Claim.Figures = Claim.figures(s.unsettled.filter(_.settledBy.contains(payoutId)))

  /** O28 (FR-022): a signed adjustment at the brand in a currency, a payable fact the next close claims. */
  def adjust(brand: String, affiliate: String, b: AdjustmentBody, by: String): Respond[Bytes] =
    val kind = b.`type`.map(_.toUpperCase)
    (kind, b.amount, b.currency, b.reason.filter(_.trim.nonEmpty)) match
      case (None, _, _, _)                                    => invalid("type is required")
      case (Some(t), _, _, _) if t != "CREDIT" && t != "DEBIT" => invalid("type must be CREDIT or DEBIT")
      case (_, None, _, _)                                    => invalid("amount is required")
      case (_, Some(a), _, _) if a <= 0                       => invalid("amount must be positive, in minor units")
      case (_, _, c, _) if !c.exists(_.matches("[A-Z]{3}"))   => invalid("currency is required, ISO 4217")
      case (_, _, _, None)                                    => invalid("reason is required")
      case (Some(t), Some(a), Some(c), Some(reason)) =>
        val id     = s"adjustment/${UUID.randomUUID()}"
        val signed = if t == "CREDIT" then a else -a
        val now    = api.now()
        api.components.forEventSourcedEntity(CommissionAccount.entityId(affiliate, brand, c)).call(CommissionAccount.recordAdjustment)
          .invoke(RecordAdjustment(id, signed, reason, by, now, None, api.brand(brand).reportingZone, now)): Unit
        networks.audit(affiliate, Some(brand), "ADJUSTMENT_CREATED", by,
          Map("adjustmentId" -> id, "type" -> t, "amount" -> a.toString, "currency" -> c, "reason" -> reason))
        answer(201, Adjusted(id, affiliate, brand, t, a, c, reason, now))
      case _ => invalid("the body is not the route's shape")

  /** The export (FR-021): the approved payouts of the period with their payment details, the read audited. */
  def exported(brand: String, period: Option[String], by: String): Respond[Bytes] =
    period.filter(Periods.isPeriod) match
      case None => refuse(400, Codes.InvalidPeriod, "period is required, yyyy-MM")
      case Some(p) =>
        val approved = api.views.forView(Payouts).ask(Payouts.approved, "brand" -> brand, "period" -> p)
        val rows = approved.map { pay =>
          val identity = api.identity(pay.affiliate, api.account(pay.affiliate)).toOption.flatten
          Export.Row(pay.affiliate, identity.fold("")(i => i.displayName.getOrElse(i.name)), identity.fold("")(_.email),
            identity.flatMap(_.paymentMethod).getOrElse(""), brand, pay.currency, pay.period, pay.figures.cpa, pay.figures.revShare,
            pay.figures.overrideAmount, pay.figures.adjustments, pay.total, pay.approvedBy.getOrElse(""), pay.approvedAt)
        }
        networks.audit("", Some(brand), "payment_details_read", by,
          Map("period" -> p, "affiliates" -> approved.map(_.affiliate).distinct.mkString(",")))
        raw(200, "text/csv; charset=utf-8", Export.render(rows))

  private def payout(id: EntityId): PayoutState = api.components.forEventSourcedEntity(id).call(Payout.get).invoke()
