package io.eitheror.casino.wallet.funding

/**
 * The clawback a cancel or a hold applies (spec 002 FR-015a), as `casino-service`'s withdrawal and
 * cancel-bonus routes compute it today, from the baseline the wallet records at award:
 *
 * {{{
 * deposits since award = max(0, total deposits now − total deposits at award)
 * clawback             = max(0, real now − real at award − deposits since award)
 * }}}
 *
 * then clamped to real balance where it is applied (the resolver does that). A record with no
 * baseline (one the migration carried over without the original's snapshot) claws back nothing
 * (FR-017, feature 244: player-favourable).
 */
object Clawback:

  final case class Baseline(realAtAward: Long, totalDepositsAtAward: Long)

  def of(baseline: Option[Baseline], realNow: Long, totalDepositsNow: Long): Long =
    baseline.fold(0L) { b =>
      val depositsSinceAward = math.max(0L, totalDepositsNow - b.totalDepositsAtAward)
      math.max(0L, realNow - b.realAtAward - depositsSinceAward)
    }

  /**
   * One clawback for a hold that forfeits several records: from the baseline of the earliest
   * awarded of them, which is the original's single activation baseline when one bonus was active
   * and covers every winning since bonus play began. Applied once (FR-023). When that record has
   * no baseline, nothing is clawed back (FR-017's player-favourable rule).
   */
  def ofEarliest(baselinesInAwardOrder: Seq[Option[Baseline]], realNow: Long, totalDepositsNow: Long): Long =
    baselinesInAwardOrder.headOption.fold(0L)(of(_, realNow, totalDepositsNow))
