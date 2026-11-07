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
    written: Vector[String] = Vector.empty,
    /** The import wrote this account's history (FR-039): a second import writes nothing, however long the history. */
    imported: Boolean = false
):
  def has(id: String): Boolean = written.contains(id)

/** The rate an NGR fact is folded at: the plan's RevShare (or the membership's override of it), its window, and whether it pays. */
final case class Rate(revShareBp: Int, window: Window, payable: Boolean)

/** The brand's fees in basis points, applied per GGR fact. */
final case class FeeRates(admin: Int = 0, payment: Int = 0, provider: Int = 0)

/** An NGR input: `kind` is `ggr`, `bonus-cost` or `chargeback`; `factId` the brand's fact. */
final case class RecordNgr(factId: String, kind: String, amount: Long, occurredAt: Instant, rate: Rate, fees: FeeRates, zone: String, at: Instant)
final case class RecordCpa(id: String, amount: Long, occurredAt: Instant, zone: String, at: Instant)
/** `rate`: the plan's at the deposit, so the fee's NGR is folded into RevShare as any NGR fact's (FR-014); none in an old command. */
final case class RecordPaymentFee(factId: String, deposit: Long, bp: Int, occurredAt: Instant, zone: String, at: Instant,
    rate: Option[Rate] = None)
final case class RecordAdjustment(id: String, amount: Long, reason: String, by: String, occurredAt: Instant, claimedBy: Option[String],
    zone: String, at: Instant, source: Option[String] = None)
final case class RecomputeOverride(source: String, period: String, sourceEarnings: Long, bp: Int, zone: String, at: Instant)
final case class CloseAccount(payoutId: String, periodEnd: Instant, threshold: Long)

/** Paying `payoutId`: its claims are marked paid only while they fold to the `figures` the payout carries. */
final case class PayClaims(payoutId: String, figures: Claim.Figures)
/** Facts carried from the original (FR-039) as it recorded them, and the payouts among those claiming them that it paid. */
final case class ImportFacts(facts: Vector[LedgerFact], paid: Set[String])

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
  /** `newly` counts, by period, the facts this claim took that were unclaimed before (the `Unsettled` view's figure). */
  final case class FactsClaimed(payoutId: String, ids: Set[String], figures: Claim.Figures, newly: Map[String, Int] = Map.empty)
      extends Event
  /** `periods` counts, by period, the facts the payout held and gives back. */
  final case class FactsReleased(payoutId: String, periods: Map[String, Int] = Map.empty) extends Event
  final case class PayoutPaid(payoutId: String) extends Event
  /** A source's earnings in a period moved: its ancestors' overrides are recomputed from it (FR-015). */
  final case class EarningsChanged(period: String, earnings: Long) extends Event

  val MaxWritten = 10_000

  /** The reason an imported fact is written with. */
  val ImportReason = "import"

  def next(s: AccountState, e: Event): AccountState = e match
    case FactWritten(f, reason, _) =>
      s.copy(
        imported = s.imported || reason.contains(ImportReason),
        sums = s.sums.add(f),
        unsettled = if f.kind.payable then s.unsettled :+ f else s.unsettled,
        claims = f.settledBy.fold(s.claims)(p => s.claims.updated(p, s.claims.getOrElse(p, Set.empty) + f.id)),
        written = (s.written :+ f.id).takeRight(MaxWritten)
      )
    case FactsClaimed(p, ids, _, _) =>
      s.copy(unsettled = s.unsettled.map(f => if ids(f.id) then f.copy(settledBy = Some(p)) else f), claims = s.claims.updated(p, ids))
    case FactsReleased(p, _) =>
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
      ((input +: fees) ++ revShare(folded, input.period, c.rate, s"revshare/${c.factId}", c.occurredAt, c.zone, c.at)).map(FactWritten(_))
    }

  /** The RevShare delta NGR's change makes in `period`. As the original (`InternalRoutes.scala:227`): none at 0, which would claw back all that was written. */
  def revShare(folded: Sums, period: String, rate: Rate, id: String, occurredAt: Instant, zone: String, at: Instant): Option[LedgerFact] =
    if !rate.payable || rate.revShareBp <= 0 then None
    else
      val d = RevShare.delta(rate.window, folded, period, rate.revShareBp)
      Option.when(d != 0)(fact(id, FactType.RevShare, d, occurredAt, zone, at))

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
  val payClaims         = command("pay-claims")(_.payClaims)
  val importFacts       = command("import-facts")(_.importFacts)
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
  given Serializer[PayClaims]         = ProgrammeCodecs.serializer[PayClaims]("pay-claims")
  given Serializer[ImportFacts]       = ProgrammeCodecs.serializer[ImportFacts]("import-facts")
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
      val fee   = LedgerFact(id, FactType.PaymentFee, Fees.on(c.deposit, c.bp), c.occurredAt, c.at, Periods.periodOf(c.occurredAt, ZoneId.of(c.zone)))
      // The original recomputes after the fee (`InternalRoutes.scala:1062-1117`): a deposit after the period's last GGR moves RevShare too.
      val delta = c.rate.flatMap(r => CommissionAccount.revShare(currentState.sums.add(fee), fee.period, r, s"revshare/$id", c.occurredAt, c.zone, c.at))
      write(withEarnings(currentState, (fee +: delta.toVector).map(FactWritten(_))))

  /** A manager's adjustment (FR-020), or the difference an approval's override figure makes, claimed by that payout. */
  def recordAdjustment(c: RecordAdjustment): Effect[AccountState] =
    if currentState.has(c.id) then effects.reply(currentState)
    else if c.claimedBy.exists(currentState.paid) then effects.error("paid", ErrorCode.Conflict)
    else
      val f = LedgerFact(c.id, FactType.Adjustment, c.amount, c.occurredAt, c.at, Periods.periodOf(c.occurredAt, ZoneId.of(c.zone)),
        sourceAffiliate = c.source, settledBy = c.claimedBy)
      write(withEarnings(currentState, Vector(FactWritten(f, Some(c.reason), Some(c.by)))))

  /**
   * Facts imported as the original recorded them, in one write: each marked `import`, with the payout
   * that claimed it; then the paid payouts, so their facts leave the unsettled set. Nothing is folded
   * again and no override is recomputed: the original's overrides are among the facts. An account
   * imported once takes no second import, since `written` remembers only the latest ids.
   */
  def importFacts(c: ImportFacts): Effect[AccountState] =
    if currentState.imported then effects.reply(currentState)
    else
      val facts = c.facts.filterNot(f => currentState.has(f.id)).map(FactWritten(_, Some(ImportReason), None))
      val paid  = c.paid.toVector.sorted.filterNot(currentState.paid).map(CommissionAccount.PayoutPaid(_))
      write(facts ++ paid)

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
   * below the threshold it claims nothing, and what an earlier close claimed stays claimed.
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
          else
            val newly = facts.filterNot(f => prior(f.id)).groupMapReduce(_.period)(_ => 1)(_ + _)
            effects.persist(FactsClaimed(c.payoutId, ids, figures, newly)).thenReply(_ => reply)
        // As the original (`CommissionEngine.scala:884`): an earlier close's claims stand, and its payout with
        // them, so what it pays is what it claimed; the late facts wait for the next close. Releasing them
        // would leave a payable payout whose facts a later close claims again.
        case Claim.Outcome.BelowThreshold(figures) => effects.reply(Closing(claimed = false, figures, 0))

  /**
   * The account's half of paying (FR-019), made before the payout's: the claims are paid only if they still
   * fold to what the payout shows, so a close that claimed a late fact meanwhile is refused here (`409
   * claims_changed`) rather than the fact being stamped paid outside the paid total. Once paid, every close
   * of the payout is refused, so the payout's own `pay` that follows cannot be raced; a retry pays again.
   */
  def payClaims(c: PayClaims): Effect[AccountState] =
    val s = currentState
    if s.paid(c.payoutId) then effects.reply(s)
    else if Claim.figures(s.unsettled.filter(_.settledBy.contains(c.payoutId))) != c.figures then
      effects.error("claims_changed", ErrorCode.Conflict)
    else effects.persist(PayoutPaid(c.payoutId)).thenReplyState

  def markPaid(payoutId: String): Effect[AccountState] =
    if currentState.paid(payoutId) then effects.reply(currentState)
    else effects.persist(PayoutPaid(payoutId)).thenReplyState

  def get: ReadOnlyEffect[AccountState] = effects.reply(currentState)

  def sumsOf(period: String): ReadOnlyEffect[Sums] = effects.reply(currentState.sums.forPeriod(period))
