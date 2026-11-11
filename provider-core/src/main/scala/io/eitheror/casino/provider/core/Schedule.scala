package io.eitheror.casino.provider.core

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

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

  private val mapper = ObjectMapper()

  /**
   * A schedule as a setting writes it (spec 026 R10, `PROVIDER_RETRY_SCHEDULE`): the scenario
   * schema's `schedule` object, where `wait` and `retryOn` may be left out, since the house provider
   * waits as each brand entry says and retries what its protocol retries. A malformed value is
   * refused naming the field, so a start with it fails saying what to fix.
   */
  def fromJson(text: String, answerWait: FiniteDuration, defaultRetryOn: Set[String]): Either[String, Schedule] =
    def duration(n: JsonNode, field: String, at: String): Either[String, FiniteDuration] =
      Option(n.get(field)).filter(_.isTextual).toRight(s"$at.$field is required: a duration such as 30s")
        .flatMap(v => Durations.parse(v.asText()).left.map(e => s"$at.$field: $e"))
    val known = Set("wait", "intervals", "cap", "retryOn")
    for
      root <- Try(mapper.readTree(text)).toOption.filter(n => n != null && n.isObject).toRight("the schedule is not a JSON object")
      _    <- root.fieldNames().asScala.find(f => !known.contains(f)).toLeft(()).left.map(f => s"the schedule has no field '$f'")
      wait <- if root.has("wait") then duration(root, "wait", "schedule") else Right(answerWait)
      list <- Option(root.get("intervals")).filter(_.isArray).toRight("schedule.intervals is required: an array, empty for no retry")
      intervals <- list.elements().asScala.toVector.zipWithIndex.foldLeft[Either[String, Vector[Interval]]](Right(Vector.empty)) {
        case (acc, (i, n)) =>
          val at = s"schedule.intervals[$n]"
          for
            done  <- acc
            every <- duration(i, "every", at).filterOrElse(_ > Duration.Zero, s"$at.every must be longer than nothing")
            runs  <- if i.has("for") then duration(i, "for", at).map(Some(_)) else Right(None)
          yield done :+ Interval(every, runs)
      }
      _    <- intervals.dropRight(1).find(_.runsFor.isEmpty).toLeft(()).left.map(_ => "only the last of schedule.intervals may leave out `for`")
      cap  <- duration(root, "cap", "schedule")
      retryOn <- Option(root.get("retryOn")).fold[Either[String, Set[String]]](Right(defaultRetryOn)) { r =>
        Either.cond(r.isArray && r.elements().asScala.forall(_.isTextual), r.elements().asScala.map(_.asText()).toSet,
          "schedule.retryOn must be an array of answer words")
      }
    yield Schedule(wait, intervals, cap, retryOn)
