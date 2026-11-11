package io.eitheror.casino.reporting.figures

import io.eitheror.casino.contract.facts.*

/**
 * What a money fact means for the brand's figures (FR-005; research R4). Every amount is minor units of one
 * currency; nothing here converts.
 */
object Figures:

  /** A fact's part of GGR on its own day: stakes, wins and rollbacks, each positive. */
  final case class GgrLeg(stake: Long, win: Long, rollback: Long)

  /**
   * GGR's leg of a fact: a bet's stake, a win's payout, a provider credit's amount (a win paid outside a round,
   * FR-005), a rollback's return; a loss settles its round with nothing. Spec 022's families and every other
   * family count nothing.
   */
  def ggrLeg(fact: MoneyTransactionsFact): Option[GgrLeg] = fact match
    case f: BetPlaced          => Some(GgrLeg(f.amountCents.value, 0L, 0L))
    case f: WinCredited        => Some(GgrLeg(0L, f.amountCents.value, 0L))
    case f: ProviderCredited   => Some(GgrLeg(0L, f.amountCents.value, 0L))
    case f: BetRolledBack      => Some(GgrLeg(0L, 0L, f.amountCents.value))
    case _: RoundSettledAsLoss => Some(GgrLeg(0L, 0L, 0L))
    case _                     => None

  /** The deposit pool's leg kinds, as attribution's `RealMoney.Leg`. */
  object PoolLeg:
    val Stake    = "stake"
    val Win      = "win"
    val Rollback = "rollback"
    val Loss     = "loss"

  /**
   * The deposit pool's leg of a fact, as attribution's `RealMoney.counted` exactly, so SC-005 holds: the deposit
   * parts of a bet, a win and a rollback, a loss with nothing; a provider credit counts nothing. It counts on its
   * round's day, which the caller resolves.
   */
  def poolLeg(fact: MoneyTransactionsFact): Option[(String, String, Long)] = fact match
    case f: BetPlaced          => Some((PoolLeg.Stake, f.roundId, part(f.depositPartCents)))
    case f: WinCredited        => Some((PoolLeg.Win, f.roundId, part(f.depositPartCents)))
    case f: BetRolledBack      => Some((PoolLeg.Rollback, f.roundId, part(f.depositPartCents)))
    case f: RoundSettledAsLoss => Some((PoolLeg.Loss, f.roundId, 0L))
    case _                     => None

  private def part(p: Option[io.eitheror.casino.contract.money.MinorUnits]): Long = p.fold(0L)(_.value)

  /** A pool leg's signed effect on the deposit pool's GGR. */
  def poolGgr(kind: String, amount: Long): Long = kind match
    case PoolLeg.Stake    => amount
    case PoolLeg.Win      => -amount
    case PoolLeg.Rollback => -amount
    case _                => 0L

  /** GGR: stakes, less what rollbacks returned, less wins. */
  def ggr(stakes: Long, wins: Long, rollbacks: Long): Long = stakes - rollbacks - wins

  /** NGR before the programme's fees (FR-005, spec 013 FR-012). */
  def ngr(ggr: Long, bonusCost: Long, chargebacks: Long): Long = ggr - bonusCost - chargebacks

  /** The basis every report that shows NGR names. */
  val NgrBasis = "before programme fees"

  /**
   * The original's trend arrow (`DashboardRoutes.scala:239-243`): both zero flat; yesterday zero, or today above
   * 1.02 times yesterday, up; below 0.98 times, down; otherwise flat.
   */
  def trend(today: Long, yesterday: Long): String =
    val t = BigDecimal(today)
    val y = BigDecimal(yesterday)
    if y == 0 && t == 0 then "flat"
    else if y == 0 || t > y * BigDecimal("1.02") then "up"
    else if t < y * BigDecimal("0.98") then "down"
    else "flat"

  /** A rate in percent to one place, `None` (rendered `null`) with no attempts (research R16). */
  def ratePct(done: Long, attempts: Long): Option[BigDecimal] =
    Option.when(attempts > 0)((BigDecimal(done) * 100 / BigDecimal(attempts)).setScale(1, BigDecimal.RoundingMode.HALF_UP))

  /** The bonus report's conversion rate: the truncated whole percent, 0 with no grants, as the original. */
  def conversionRatePct(converted: Int, total: Int): Int = if total > 0 then converted * 100 / total else 0

  /** Hours to one place, `None` with nothing to average. */
  def averageHours(totalSeconds: Long, count: Long): Option[BigDecimal] =
    Option.when(count > 0)((BigDecimal(totalSeconds) / 3600 / BigDecimal(count)).setScale(1, BigDecimal.RoundingMode.HALF_UP))

  /**
   * Mean wagering progress over active records with a requirement, as the original (`DashboardRoutes.scala:819-831`):
   * each record's `round(progress / requirement × 100)`, then the integer mean; 0 with none.
   */
  def averageProgressPct(records: Seq[(Long, Long)]): Int =
    records.collect { case (progress, requirement) if requirement > 0 =>
      (BigDecimal(progress) / BigDecimal(requirement) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt
    } match
      case Nil  => 0
      case pcts => pcts.sum / pcts.length
