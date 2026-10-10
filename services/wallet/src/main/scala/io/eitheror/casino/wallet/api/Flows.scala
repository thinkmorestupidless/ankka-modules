package io.eitheror.casino.wallet.api

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.eitheror.casino.wallet.entity.Outcome

import scala.concurrent.duration.*

/**
 * How a route waits for a workflow's outcome (research R17). ankka 0.11 has no call that awaits a
 * workflow's end, so the route starts it (or finds it started: a `Conflict` is the claim already
 * taken) and polls its state on the request's virtual thread until the outcome is present. One
 * place, so the await becomes one line when ankka gains it.
 */
object Flows:

  /** How long a route waits before answering `OUTCOME_PENDING`: the steps take milliseconds. */
  val Bound: FiniteDuration = 2.seconds

  /** The poll interval: a query to a workflow in memory, well under a millisecond. */
  val Interval: FiniteDuration = 1.milli

  /** `start` the flow, treating a `Conflict` as "already started"; anything else propagates. */
  def claim(start: => Unit): Unit =
    try start
    catch case e: CommandError if e.code == ErrorCode.Conflict => ()

  /** Polls `read` until it answers, or `bound` passes: then `None`, and the route says the outcome is pending. */
  def await(read: => Option[Outcome], bound: FiniteDuration = Bound): Option[Outcome] =
    val deadline = System.nanoTime() + bound.toNanos
    var found    = read
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(Interval.toMillis)
      found = read
    found

  /** The route's answer while a flow is still deciding: the caller retries and is answered from the flow. */
  val pending: Outcome = Outcome(code = Some("OUTCOME_PENDING"), message = Some("The outcome is still being decided; retry"))
