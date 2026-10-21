package io.eitheror.casino.pspgateway.delivery

import java.time.Duration

/**
 * How an outcome is delivered (FR-015): up to 4 attempts, 250 ms after the first and doubling, all
 * within a 15 s budget, each call bounded at 10 s (`ankka.service-client.timeout`). The original's
 * `outcome_retry_internal_test.go` holds the same schedule for its call to merchant-service.
 */
object Backoff:
  val Attempts: Int     = 4
  val First: Duration   = Duration.ofMillis(250)
  val Budget: Duration  = Duration.ofSeconds(15)
  val PerCall: Duration = Duration.ofSeconds(10)

  /** The wait before attempt `n` (1-based): none before the first, then 250 ms, 500 ms, 1 s. */
  def delayBefore(n: Int): Duration =
    if n <= 1 then Duration.ZERO else First.multipliedBy(1L << (n - 2))

  /** Whether another attempt may start `elapsed` into the budget, after `made` attempts. */
  def another(made: Int, elapsed: Duration): Boolean =
    made < Attempts && elapsed.plus(delayBefore(made + 1)).compareTo(Budget) < 0

  /** What the receiver answered, as the deliverer reads it (contracts/merchant.md §3). */
  enum Reading:
    case Delivered, Retry, Refused

  /**
   * `2xx` delivered; no answer, `429` and every `5xx` (`503 OUTCOME_PENDING` among them) retried; any
   * other status a refusal, recorded and not retried. `None` is no answer: unreachable or past the bound.
   */
  def read(status: Option[Int]): Reading = status match
    case None                    => Reading.Retry
    case Some(s) if s / 100 == 2 => Reading.Delivered
    case Some(429)               => Reading.Retry
    case Some(s) if s / 100 == 5 => Reading.Retry
    case Some(_)                 => Reading.Refused

  def retryable(status: Option[Int]): Boolean = read(status) == Reading.Retry
