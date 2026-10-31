package io.eitheror.casino.payouts.limits

import io.eitheror.casino.contract.money.Currency

import java.time.{Duration, Instant, ZoneOffset}

/** A request the limits count (data-model.md `RecentRequest`): `counts` is false once it closed unpaid. */
final case class Counted(id: String, amount: Long, currency: Currency, createdAt: Instant, counts: Boolean = true)

/** A currency's two limits in minor units; zero or none is no limit (FR-005a). */
final case class Pair(weekly: Option[Long] = None, monthly: Option[Long] = None):
  def normalised: Pair = Pair(weekly.filter(_ > 0), monthly.filter(_ > 0))

enum Block(val limit: Long, val used: Long, val remaining: Long):
  case Weekly(l: Long, u: Long, r: Long)  extends Block(l, u, r)
  case Monthly(l: Long, u: Long, r: Long) extends Block(l, u, r)

/**
 * The original's test (`WithdrawalLimitChecker.scala:32, 78-110`), per currency: a request is refused when
 * `used + amount > limit`. Weekly is the rolling seven days before now, a request exactly seven days old
 * not counted (the original's strict `isAfter`); monthly is the UTC calendar month. The counting
 * statuses are pending review, approved, awaiting manual payout and completed, which `counts` records.
 * Weekly is checked first.
 */
object Limits:
  val Week: Duration = Duration.ofDays(7)

  /** Every request counted but those cancelled, rejected, failed or reversed. */
  val counting: Set[String] = Set("pending_review", "approved", "awaiting_manual_payout", "completed")

  def check(limits: Pair, recent: Vector[Counted], currency: Currency, amount: Long, now: Instant): Option[Block] =
    val pair    = limits.normalised
    val counted = recent.filter(r => r.counts && r.currency == currency)
    def over(limit: Long, used: Long) = used + amount > limit
    val weekStart = now.minus(Week)
    val weekly = pair.weekly.flatMap { limit =>
      val used = counted.filter(_.createdAt.isAfter(weekStart)).map(_.amount).sum
      Option.when(over(limit, used))(Block.Weekly(limit, used, math.max(0L, limit - used)))
    }
    weekly.orElse(pair.monthly.flatMap { limit =>
      val month = now.atZone(ZoneOffset.UTC)
      val used = counted.filter { r =>
        val at = r.createdAt.atZone(ZoneOffset.UTC)
        at.getYear == month.getYear && at.getMonthValue == month.getMonthValue
      }.map(_.amount).sum
      Option.when(over(limit, used))(Block.Monthly(limit, used, math.max(0L, limit - used)))
    })
