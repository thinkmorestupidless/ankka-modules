package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.ProgrammeCodecs.given
import io.eitheror.casino.programme.commission.*
import io.eitheror.casino.programme.periods.Periods

import java.time.{Instant, ZoneId}

/**
 * An affiliate's ledger at a brand in one currency (data-model.md *CommissionAccount*), id
 * `<affiliate>/<brand>/<currency>`. Every figure is folded from `sums`; payable facts stay in
 * `unsettled` until the payout that claims them is paid.
 */
final case class AccountState(
    sums: Sums = Sums(),
    unsettled: Vector[LedgerFact] = Vector.empty,
    claims: Map[String, Set[String]] = Map.empty,
    paid: Set[String] = Set.empty,
    written: Vector[String] = Vector.empty
):
  def has(id: String): Boolean = written.contains(id)

/** The rate an NGR fact is folded at: the plan's RevShare (or the membership's override of it), its window, and whether it pays. */
final case class Rate(revShareBp: Int, window: Window, payable: Boolean)

/** The brand's fees in basis points, applied per GGR fact. */
final case class FeeRates(admin: Int = 0, payment: Int = 0, provider: Int = 0)

/** An NGR input: `kind` is `ggr`, `bonus-cost` or `chargeback`; `factId` the brand's fact. */
final case class RecordNgr(factId: String, kind: String, amount: Long, occurredAt: Instant, rate: Rate, fees: FeeRates, zone: String, at: Instant)
final case class RecordCpa(id: String, amount: Long, occurredAt: Instant, zone: String, at: Instant)
final case class RecordPaymentFee(factId: String, deposit: Long, bp: Int, occurredAt: Instant, zone: String, at: Instant)
final case class RecordAdjustment(id: String, amount: Long, reason: String, by: String, occurredAt: Instant, claimedBy: Option[String],
    zone: String, at: Instant)
final case class RecomputeOverride(source: String, period: String, sourceEarnings: Long, bp: Int, zone: String, at: Instant)
final case class CloseAccount(payoutId: String, periodEnd: Instant, threshold: Long)

/** What a close claimed: the payout's figures, or nothing because they were below the threshold. */
final case class Closing(claimed: Boolean, figures: Claim.Figures, facts: Int)

object CommissionAccount extends EventSourcedEntity.Companion[CommissionAccount, AccountState, CommissionAccount.Event](
      componentId = ComponentId("commission-account"),
      stateSerializer = LedgerCodecs.state,
      eventSerializer = LedgerCodecs.event
    ):
  import LedgerCodecs.given

  sealed trait Event
  final case class FactWritten(fact: LedgerFact, reason: Option[String] = None, by: Option[String] = None) extends Event
  final case class FactsClaimed(payoutId: String, ids: Set[String], figures: Claim.Figures) extends Event
  final case class FactsReleased(payoutId: String) extends Event
  final case class PayoutPaid(payoutId: String) extends Event
  /** A source's earnings in a period moved: its ancestors' overrides are recomputed from it (FR-015). */
  final case class EarningsChanged(period: String, earnings: Long) extends Event

  val MaxWritten = 10_000

  def next(s: AccountState, e: Event): AccountState = e match
    case FactWritten(f, _, _) =>
      s.copy(
        sums = s.sums.add(f),
        unsettled = if f.kind.payable then s.unsettled :+ f else s.unsettled,
        claims = f.settledBy.fold(s.claims)(p => s.claims.updated(p, s.claims.getOrElse(p, Set.empty) + f.id)),
        written = (s.written :+ f.id).takeRight(MaxWritten)
      )
    case FactsClaimed(p, ids, _) =>
      s.copy(unsettled = s.unsettled.map(f => if ids(f.id) then f.copy(settledBy = Some(p)) else f), claims = s.claims.updated(p, ids))
    case FactsReleased(p) =>
      s.copy(unsettled = s.unsettled.map(f => if f.settledBy.contains(p) then f.copy(settledBy = None) else f), claims = s.claims - p)
    case PayoutPaid(p) =>
      s.copy(unsettled = s.unsettled.filterNot(_.settledBy.contains(p)), paid = s.paid + p, claims = s.claims - p)
    case EarningsChanged(_, _) => s

  private def fact(id: String, kind: FactType, amount: Long, occurredAt: Instant, zone: String, at: Instant, source: Option[String] = None,
      settledBy: Option[String] = None) =
    LedgerFact(id, kind, amount, occurredAt, at, Periods.periodOf(occurredAt, ZoneId.of(zone)), source, settledBy)

  /** The facts an NGR input writes: itself, its fees when it is GGR, and the RevShare delta when payable (FR-014, FR-016). */
  def ngr(s: AccountState, c: RecordNgr): Vector[FactWritten] =
    val kind = FactType.parse(c.kind).filter(t => t == FactType.Ggr || t == FactType.BonusCost || t == FactType.Chargeback)
    kind.fold(Vector.empty) { t =>
      val input = fact(s"${c.kind}/${c.factId}", t, c.amount, c.occurredAt, c.zone, c.at)
      val fees =
        if t != FactType.Ggr then Vector.empty
        else
          Vector(FactType.AdminFee -> c.fees.admin, FactType.ProviderFee -> c.fees.provider).collect {
            case (ft, bp) if bp != 0 => fact(s"${ft.wire}/${c.factId}", ft, Fees.on(c.amount, bp), c.occurredAt, c.zone, c.at)
          }
      val folded = (input +: fees).foldLeft(s.sums)(_.add(_))
      val revShare =
        if !c.rate.payable then None
        else
          val d = RevShare.delta(c.rate.window, folded, input.period, c.rate.revShareBp)
          Option.when(d != 0)(fact(s"revshare/${c.factId}", FactType.RevShare, d, c.occurredAt, c.zone, c.at))
      ((input +: fees) ++ revShare).map(FactWritten(_))
    }

  /** The facts a command writes, and `EarningsChanged` for every period whose payable earnings moved. */
  def withEarnings(s: AccountState, written: Vector[FactWritten]): Vector[Event] =
    val after   = written.foldLeft(s.sums)((sums, w) => sums.add(w.fact))
    val periods = written.collect { case w if w.fact.kind.payable && w.fact.kind != FactType.Override => w.fact.period }.distinct
    written ++ periods.map(p => EarningsChanged(p, Earnings.of(after, p)))

  /**
   * The override fact's date: now, or the period's last millisecond once the period has ended, so the
   * delta is dated inside its period (`S/AffiliateBackfill.scala:798-802`).
   */
  def overrideAt(period: String, zone: String, at: Instant): Instant =
    val last = Periods.periodEnd(period, ZoneId.of(zone)).minusMillis(1)
    if at.isAfter(last) then last else at

  def entityId(affiliate: String, brand: String, currency: String): EntityId = EntityId(s"$affiliate/$brand/$currency")

  override def snapshotEvery: Option[Int] = Some(500)

  def create(context: EventSourcedEntityContext) = new CommissionAccount(context)

  val recordNgr         = command("record-ngr")(_.recordNgr)
  val recordCpa         = command("record-cpa")(_.recordCpa)
  val recordPaymentFee  = command("record-payment-fee")(_.recordPaymentFee)
  val recordAdjustment  = command("record-adjustment")(_.recordAdjustment)
  val recomputeOverride = command("recompute-override")(_.recomputeOverride)
  val close             = command("close")(_.close)
  val markPaid          = command("mark-paid")(_.markPaid)
  val get               = query("get")(_.get)
  val sums              = query("sums")(_.sumsOf)

object LedgerCodecs:
  val state: Serializer[AccountState]             = ProgrammeCodecs.serializer[AccountState]("commission-account")
  val event: Serializer[CommissionAccount.Event]  = ProgrammeCodecs.serializer[CommissionAccount.Event]("commission-event")
  given Serializer[RecordNgr]         = ProgrammeCodecs.serializer[RecordNgr]("record-ngr")
  given Serializer[RecordCpa]         = ProgrammeCodecs.serializer[RecordCpa]("record-cpa")
  given Serializer[RecordPaymentFee]  = ProgrammeCodecs.serializer[RecordPaymentFee]("record-payment-fee")
  given Serializer[RecordAdjustment]  = ProgrammeCodecs.serializer[RecordAdjustment]("record-adjustment")
  given Serializer[RecomputeOverride] = ProgrammeCodecs.serializer[RecomputeOverride]("recompute-override")
  given Serializer[CloseAccount]      = ProgrammeCodecs.serializer[CloseAccount]("close-account")
  given Serializer[Closing]           = ProgrammeCodecs.serializer[Closing]("closing")
  given Serializer[Sums]              = Serializer.json[Sums]("programme.sums")
  given Serializer[String]            = com.thinkmorestupidless.ankka.core.Serializers.string

import CommissionAccount.*

/** Each command is idempotent on the fact id it writes: a fact redelivered writes nothing (FR-009). */
final class CommissionAccount(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[AccountState, CommissionAccount.Event]:
  def emptyState: AccountState = AccountState()

  def applyEvent(event: CommissionAccount.Event): AccountState = CommissionAccount.next(currentState, event)

  private def write(events: Vector[Event]): Effect[AccountState] =
    if events.isEmpty then effects.reply(currentState) else effects.persistAll(events).thenReplyState

  def recordNgr(c: RecordNgr): Effect[AccountState] =
    if currentState.has(s"${c.kind}/${c.factId}") then effects.reply(currentState)
    else if FactType.parse(c.kind).forall(t => !Set(FactType.Ggr, FactType.BonusCost, FactType.Chargeback)(t)) then
      effects.error(s"an NGR input is ggr, bonus-cost or chargeback, not ${c.kind}", ErrorCode.BadRequest)
    else write(withEarnings(currentState, ngr(currentState, c)))

  def recordCpa(c: RecordCpa): Effect[AccountState] =
    if currentState.has(c.id) then effects.reply(currentState)
    else write(withEarnings(currentState, Vector(FactWritten(LedgerFact(c.id, FactType.Cpa, c.amount, c.occurredAt, c.at,
      Periods.periodOf(c.occurredAt, ZoneId.of(c.zone)))))))

  /** The payment fee on a deposit, at the brand's percentage (FR-014). A rate of zero writes nothing. */
  def recordPaymentFee(c: RecordPaymentFee): Effect[AccountState] =
    val id = s"payment-fee/${c.factId}"
    if currentState.has(id) || c.bp == 0 then effects.reply(currentState)
    else
      write(Vector(FactWritten(LedgerFact(id, FactType.PaymentFee, Fees.on(c.deposit, c.bp), c.occurredAt, c.at,
        Periods.periodOf(c.occurredAt, ZoneId.of(c.zone))))))

  /** A manager's adjustment (FR-020), or the difference an approval's override figure makes, claimed by that payout. */
  def recordAdjustment(c: RecordAdjustment): Effect[AccountState] =
    if currentState.has(c.id) then effects.reply(currentState)
    else if c.claimedBy.exists(currentState.paid) then effects.error("paid", ErrorCode.Conflict)
    else
      val f = LedgerFact(c.id, FactType.Adjustment, c.amount, c.occurredAt, c.at, Periods.periodOf(c.occurredAt, ZoneId.of(c.zone)),
        settledBy = c.claimedBy)
      write(withEarnings(currentState, Vector(FactWritten(f, Some(c.reason), Some(c.by)))))

  /** The override for one source and period, written as the delta from what was written (FR-015). */
  def recomputeOverride(c: RecomputeOverride): Effect[AccountState] =
    val inPeriod = currentState.sums.forPeriod(c.period)
    val emitted  = inPeriod.amount(FactType.Override, Some(c.source))
    val delta    = Override.delta(c.sourceEarnings, c.bp, emitted)
    if delta == 0 then effects.reply(currentState)
    else
      val seq = inPeriod.cells.collect { case (k, cell) if k.tpe == FactType.Override && k.source.contains(c.source) => cell.count }.sum + 1
      val f = LedgerFact(s"override/${c.source}/${c.period}/$seq", FactType.Override, delta, overrideAt(c.period, c.zone, c.at), c.at,
        c.period, Some(c.source))
      write(Vector(FactWritten(f)))

  /**
   * Claims for the payout every unsettled payable fact up to the period's end plus its earlier claims;
   * below the threshold it claims nothing, and releases what the payout held (settlement.feature).
   */
  def close(c: CloseAccount): Effect[Closing] =
    val s = currentState
    if s.paid(c.payoutId) then effects.error("paid", ErrorCode.Conflict)
    else
      val prior = s.claims.getOrElse(c.payoutId, Set.empty)
      Claim.select(s.unsettled, c.periodEnd, prior, c.threshold) match
        case Claim.Outcome.Claimed(facts, figures) =>
          val ids   = facts.map(_.id).toSet
          val reply = Closing(claimed = true, figures, ids.size)
          if ids == prior then effects.reply(reply)
          else effects.persist(FactsClaimed(c.payoutId, ids, figures)).thenReply(_ => reply)
        case Claim.Outcome.BelowThreshold(figures) =>
          val reply = Closing(claimed = false, figures, 0)
          if prior.isEmpty then effects.reply(reply) else effects.persist(FactsReleased(c.payoutId)).thenReply(_ => reply)

  def markPaid(payoutId: String): Effect[AccountState] =
    if currentState.paid(payoutId) then effects.reply(currentState)
    else effects.persist(PayoutPaid(payoutId)).thenReplyState

  def get: ReadOnlyEffect[AccountState] = effects.reply(currentState)

  def sumsOf(period: String): ReadOnlyEffect[Sums] = effects.reply(currentState.sums.forPeriod(period))
