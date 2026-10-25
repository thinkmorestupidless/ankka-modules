package io.eitheror.casino.wallet.api

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.eitheror.casino.wallet.entity.{Outcome, Refusals}

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

  /** What a flow started under a key holds: the first start's digest and amount, and its outcome once decided. */
  final case class Seen(digest: String, amount: Option[Long], outcome: Option[Outcome])

  /**
   * The answer to a command a flow carries out under the caller's key (spec 022 research R4): claim
   * the flow; then, if the flow was first started with a different command, `KEY_REUSED` naming the
   * first command's type and amount; otherwise the flow's outcome, awaited, or `pending`. A repeat
   * of the same command is answered with the first outcome, as a wallet answers a repeated key.
   */
  def answer(start: => Unit, read: => Seen, digest: String, commandType: String, key: String, bound: FiniteDuration = Bound): Outcome =
    claim(start)
    val first = read
    if first.digest != digest then
      val earlier = Outcome(commandType = commandType, amount = first.amount)
      Outcome(code = Some(Refusals.KeyReused), message = Some(Refusals.keyReused(key, earlier)), details = Map("key" -> key),
        commandType = commandType, amount = first.amount)
    else first.outcome.orElse(await(read.outcome, bound)).getOrElse(pending)

  /** The route's answer while a flow is still deciding: the caller retries and is answered from the flow. */
  val pending: Outcome = Outcome(code = Some("OUTCOME_PENDING"), message = Some("The outcome is still being decided; retry"))
