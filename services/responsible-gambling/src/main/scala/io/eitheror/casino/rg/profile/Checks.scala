package io.eitheror.casino.rg.profile

import java.time.Instant

/**
 * A check's answer, in the shape its callers read (contracts/routes.md): the decision and, by decision,
 * the end of a break, or the period, limit, used and remaining of the limit a request passes.
 */
final case class CheckAnswer(
    decision: String,
    endsAt: Option[Instant] = None,
    period: Option[String] = None,
    limit: Option[Long] = None,
    used: Option[Long] = None,
    remaining: Option[Long] = None
):
  def allowed: Boolean = decision == Checks.Allowed

/** `may-play`'s answer, in game-provider's shape: allowed, or a reason with its figures. */
final case class PlayAnswer(
    allowed: Boolean,
    reason: Option[String] = None,
    coolingOffUntil: Option[Instant] = None,
    limitMinutes: Option[Long] = None,
    usedMinutes: Option[Long] = None,
    sessionLimitMinutes: Option[Long] = None,
    minutesUsed: Option[Long] = None
)

/** The windows' sums a caller carries: the last 24 hours, 7 days and 30 days. */
final case class Sums(last24h: Long, last7d: Long, last30d: Long):
  def of(period: String): Long = period match
    case "daily"  => last24h
    case "weekly" => last7d
    case _        => last30d

/**
 * The four checks (FR-007 to FR-010, FR-021), pure: each answers from the profile and the request's figures
 * in the original's order, excluded, then cooling off, then the limit. A limit is passed when
 * `amount > limit − used` (the original's `notify/deposit`); exactly the remainder is allowed.
 */
object Checks:
  val Allowed     = "allowed"
  val Excluded    = "excluded"
  val CoolingOff  = "cooling_off"
  val OverLimit   = "over_limit"
  val Spending    = "spending_limit"
  val Unavailable = "unavailable"

  private def gate(p: ProfileState, now: Instant): Option[CheckAnswer] =
    if p.excluded then Some(CheckAnswer(Excluded))
    else p.onBreak(now).map(b => CheckAnswer(CoolingOff, endsAt = Some(b.until)))

  def mayLogIn(p: ProfileState, now: Instant): CheckAnswer = gate(p, now).getOrElse(CheckAnswer(Allowed))

  def mayDeposit(p: ProfileState, now: Instant, currency: String, amount: Long, sums: Sums): CheckAnswer =
    gate(p, now).orElse(passed(p.moneyLimits(currency), LimitType.deposits, amount, sums, OverLimit)).getOrElse(CheckAnswer(Allowed))

  /** With a loss limit set and no sums carried the check cannot be answered, so it refuses (R6). */
  def mayBet(p: ProfileState, now: Instant, currency: String, amount: Long, losses: Option[Sums]): CheckAnswer =
    gate(p, now).getOrElse {
      val limits = p.moneyLimits(currency)
      if !LimitType.losses.exists(limits.contains) then CheckAnswer(Allowed)
      else losses match
        case None    => CheckAnswer(Unavailable)
        case Some(s) => passed(limits, LimitType.losses, amount, s, Spending).getOrElse(CheckAnswer(Allowed))
    }

  def mayPlay(p: ProfileState, now: Instant, minutesUsed: Long): PlayAnswer =
    if p.excluded then PlayAnswer(false, Some("self_excluded"))
    else p.onBreak(now) match
      case Some(b) => PlayAnswer(false, Some("cooling_off"), coolingOffUntil = Some(b.until))
      case None =>
        p.sessionLimit match
          case Some(l) if minutesUsed >= l =>
            PlayAnswer(false, Some("session_limit_reached"), limitMinutes = Some(l), usedMinutes = Some(minutesUsed))
          case l => PlayAnswer(true, sessionLimitMinutes = l, minutesUsed = Some(minutesUsed))

  /** The first of daily, weekly, monthly the amount passes. */
  private def passed(limits: Map[LimitType, Long], types: Vector[LimitType], amount: Long, sums: Sums, decision: String): Option[CheckAnswer] =
    types.iterator.flatMap { t =>
      limits.get(t).flatMap { limit =>
        val period = t.period.get
        val used   = sums.of(period)
        val left   = math.max(0L, limit - used)
        Option.when(amount > left)(CheckAnswer(decision, period = Some(period), limit = Some(limit), used = Some(used), remaining = Some(left)))
      }
    }.nextOption()
