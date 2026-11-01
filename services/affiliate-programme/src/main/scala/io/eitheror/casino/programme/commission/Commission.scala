package io.eitheror.casino.programme.commission

import java.time.Instant
import scala.math.BigDecimal.RoundingMode

/**
 * The ledger's fact types (ADR 0042, `S/CommissionEngine.scala:56-69`): NGR inputs, which are only ever
 * folded, and payable facts, which a payout claims.
 */
enum FactType(val wire: String):
  case Ggr         extends FactType("ggr")
  case BonusCost   extends FactType("bonus-cost")
  case Chargeback  extends FactType("chargeback")
  case AdminFee    extends FactType("admin-fee")
  case PaymentFee  extends FactType("payment-fee")
  case ProviderFee extends FactType("provider-fee")
  case Cpa         extends FactType("cpa")
  case RevShare    extends FactType("revshare")
  case Override    extends FactType("override")
  case Adjustment  extends FactType("adjustment")

  def ngrInput: Boolean = FactType.NgrInputs(this)
  def payable: Boolean  = FactType.Payable(this)

object FactType:
  val NgrInputs: Set[FactType] = Set(Ggr, BonusCost, Chargeback, AdminFee, PaymentFee, ProviderFee)
  val Payable: Set[FactType]   = Set(Cpa, RevShare, Override, Adjustment)

  def parse(wire: String): Option[FactType] = values.find(_.wire == wire)

/** How far back NGR is folded: the whole history (negative carryover) or the period alone. */
enum Window:
  case Lifetime, Period

object Window:
  def fromCarryover(negativeCarryover: Boolean): Window = if negativeCarryover then Lifetime else Period

/** One immutable fact of an affiliate's ledger at a brand, in one currency (data-model.md *Shared types*). */
final case class LedgerFact(
    id: String,
    kind: FactType,
    amount: Long,
    occurredAt: Instant,
    recordedAt: Instant,
    period: String,
    sourceAffiliate: Option[String] = None,
    settledBy: Option[String] = None
)

/**
 * Sums of amounts per period, type and source, and their counts: every fold the original made is a
 * function of these (`S/CommissionEngine.scala:127-170`).
 */
final case class Sums(cells: Map[Sums.Key, Sums.Cell] = Map.empty):
  def add(period: String, tpe: FactType, source: Option[String], amount: Long): Sums =
    val key = Sums.Key(period, tpe, source)
    Sums(cells.updated(key, cells.getOrElse(key, Sums.Cell.Zero) + Sums.Cell(amount, 1L)))

  def add(f: LedgerFact): Sums = add(f.period, f.kind, f.sourceAffiliate, f.amount)

  def forPeriod(period: String): Sums = Sums(cells.filter(_._1.period == period))

  def scoped(window: Window, period: String): Sums = window match
    case Window.Lifetime => this
    case Window.Period   => forPeriod(period)

  /** The amount of one type, optionally from one source only. */
  def amount(tpe: FactType, source: Option[String] = None): Long =
    cells.iterator.collect { case (k, c) if k.tpe == tpe && source.forall(s => k.source.contains(s)) => c.amount }.sum

  def count(tpe: FactType): Long = cells.iterator.collect { case (k, c) if k.tpe == tpe => c.count }.sum

  def periods: Set[String] = cells.keySet.map(_.period)

object Sums:
  final case class Key(period: String, tpe: FactType, source: Option[String])
  final case class Cell(amount: Long, count: Long):
    def +(o: Cell): Cell = Cell(amount + o.amount, count + o.count)
  object Cell:
    val Zero: Cell = Cell(0L, 0L)

  def of(facts: Iterable[LedgerFact]): Sums = facts.foldLeft(Sums())(_.add(_))

/** NGR: GGR less bonus costs, chargebacks and the three fees, folded over the window, never floored. */
object Ngr:
  def of(sums: Sums, window: Window, period: String): Long =
    val s = sums.scoped(window, period)
    s.amount(FactType.Ggr) - s.amount(FactType.BonusCost) - s.amount(FactType.Chargeback) -
      s.amount(FactType.AdminFee) - s.amount(FactType.PaymentFee) - s.amount(FactType.ProviderFee)

/** A source's commission earnings in a period, the base of its ancestors' overrides: CPA, RevShare and adjustments. */
object Earnings:
  def of(sums: Sums, period: String): Long =
    val p = sums.forPeriod(period)
    p.amount(FactType.Cpa) + p.amount(FactType.RevShare) + p.amount(FactType.Adjustment)

/** A rate of basis points applied HALF_UP, as the original's `BigDecimal` percent (`S/CommissionEngine.scala:487-513`). */
private def applyRate(amount: Long, bp: Int): Long =
  (BigDecimal(amount) * BigDecimal(bp) / BigDecimal(10_000)).setScale(0, RoundingMode.HALF_UP).toLongExact

object Commission:
  /** The commission on an NGR or earnings figure: floored at zero, never negative. */
  def onNgr(amount: Long, bp: Int): Long = if bp <= 0 then 0L else applyRate(math.max(0L, amount), bp)

/** A fee on one GGR fact: no floor, so a round the player won is a negative fee and the period's fees stay linear. */
object Fees:
  def on(amount: Long, bp: Int): Long = if bp == 0 then 0L else applyRate(amount, bp)

/** The RevShare to write now: the commission on the window's NGR less what was already written in it. */
object RevShare:
  def delta(window: Window, sums: Sums, period: String, bp: Int): Long =
    Commission.onNgr(Ngr.of(sums, window, period), bp) - sums.scoped(window, period).amount(FactType.RevShare)

/** The override to write now for one source and period: the percent of its earnings less what was written. */
object Override:
  def delta(earnings: Long, bp: Int, emitted: Long): Long = Commission.onNgr(earnings, bp) - emitted

/**
 * A close (ADR 0042 decisions 4 to 7, `S/CommissionEngine.scala:747-918`): every unsettled payable fact
 * that occurred by the period's end, whichever period it is dated in, plus what the payout claimed
 * before; below the threshold nothing is claimed and the facts carry forward.
 */
object Claim:
  final case class Figures(cpa: Long = 0L, revShare: Long = 0L, overrideAmount: Long = 0L, adjustments: Long = 0L):
    def total: Long = cpa + revShare + overrideAmount + adjustments

  def figures(facts: Iterable[LedgerFact]): Figures =
    def sum(t: FactType) = facts.iterator.filter(_.kind == t).map(_.amount).sum
    Figures(sum(FactType.Cpa), sum(FactType.RevShare), sum(FactType.Override), sum(FactType.Adjustment))

  /** Unsettled payable facts that occurred at or before `periodEnd`. */
  def candidates(facts: Iterable[LedgerFact], periodEnd: Instant): Vector[LedgerFact] =
    facts.iterator.filter(f => f.kind.payable && f.settledBy.isEmpty && !f.occurredAt.isAfter(periodEnd)).toVector

  enum Outcome:
    case Claimed(facts: Vector[LedgerFact], figures: Figures)
    case BelowThreshold(figures: Figures)

  /** `unsettled` is the account's unpaid payable facts; `priorClaims` the ids this payout claimed before. */
  def select(unsettled: Iterable[LedgerFact], periodEnd: Instant, priorClaims: Set[String], threshold: Long): Outcome =
    val (already, rest) = unsettled.toVector.partition(f => priorClaims(f.id))
    val inScope         = already ++ candidates(rest, periodEnd) // a fact another payout claimed is not a candidate
    val figures         = Claim.figures(inScope)
    if figures.total < threshold then Outcome.BelowThreshold(figures) else Outcome.Claimed(inScope, figures)
