package io.eitheror.casino.simulator.core

import scala.concurrent.duration.*

/**
 * How a callback is retried (FR-005): how long one attempt waits for an answer, the intervals
 * between attempts, the cap after which the callback gives up, and which answers are retried.
 *
 * Which answer words are retried is the protocol's to say (`retryOn`): the core names none
 * (FR-006). A transport failure or a wait that runs out is always retried.
 */
final case class Schedule(
    answerWait: FiniteDuration,
    intervals: Vector[Schedule.Interval],
    cap: FiniteDuration,
    retryOn: Set[String]
):

  /** No retry at all: the default scenario's, as the stub never retries. */
  def retries: Boolean = intervals.nonEmpty

  /**
   * The pause before the next attempt, given the time since the first attempt, or `None` when the
   * cap is reached or nothing is retried. An interval `{every, for}` runs for its `for` and the
   * next takes over; the last runs to the cap.
   */
  def nextInterval(elapsed: FiniteDuration): Option[FiniteDuration] =
    if intervals.isEmpty || elapsed >= cap then None
    else
      var start = Duration.Zero
      val chosen = intervals.find { i =>
        i.runsFor match
          case Some(length) =>
            val inside = elapsed < start + length
            start = start + length
            inside
          case None => true
      }
      chosen.orElse(intervals.lastOption).map(_.every).filter(next => elapsed + next <= cap)

  /** How many attempts the schedule makes at most, the first included, when every one is retried. */
  def maxAttempts: Int =
    var elapsed: FiniteDuration = Duration.Zero
    var attempts = 1
    var next     = nextInterval(elapsed)
    while next.isDefined do
      elapsed = elapsed + next.get
      attempts += 1
      next = nextInterval(elapsed)
    attempts

object Schedule:

  final case class Interval(every: FiniteDuration, runsFor: Option[FiniteDuration])

  /**
   * The spec's stated approximation of the provider's schedule (*Assumptions*; clarified 2026-10-09):
   * every 30 seconds for ten minutes, then every ten minutes, capped at eight hours.
   */
  def providerDefault(answerWait: FiniteDuration, retryOn: Set[String]): Schedule =
    Schedule(answerWait, Vector(Interval(30.seconds, Some(10.minutes)), Interval(10.minutes, None)), 8.hours, retryOn)

  /** One attempt, never retried. */
  def once(answerWait: FiniteDuration): Schedule = Schedule(answerWait, Vector.empty, Duration.Zero, Set.empty)
