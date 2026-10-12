package io.eitheror.casino.simulator.core

/**
 * One request sent to a brand (FR-020): what was sent, under which key, when, how often, and what
 * came back. The body is the exact bytes signed, as text. An answer that names a person is stored
 * already redacted (FR-023).
 */
final case class Callback(
    callbackId: String,
    runId: String,
    sessionId: String,
    brand: String,
    kind: String,
    route: String,
    body: String,
    step: Option[Int],
    scheduledAt: Long,
    attempts: Vector[Attempt],
    outcome: String
):
  def scheduled: Boolean          = runId.nonEmpty
  def lastAnswered: Option[Attempt] = attempts.reverse.find(_.httpStatus.isDefined)

object Callback:
  val Pending       = "pending"
  val Answered      = "answered"
  val GaveUp        = "gave_up"
  val MalformedBody = "malformed_body"
  val Unreachable   = "unreachable"

  def empty(id: String): Callback = Callback(id, "", "", "", "", "", "", None, 0L, Vector.empty, Pending)

/** One send of a callback and what came of it. `error` is a transport failure or a wait that ran out. */
final case class Attempt(
    attempt: Int,
    keyId: String,
    signature: String,
    sentAt: Long,
    tookMillis: Long,
    httpStatus: Option[Int],
    status: Option[String],
    balance: Option[String],
    currency: Option[String],
    answer: Option[String],
    error: Option[String]
)

enum CallbackEvent:
  case Scheduled(runId: String, sessionId: String, brand: String, kind: String, route: String, body: String, step: Option[Int], at: Long)
  case AttemptRecorded(attempt: Attempt)
  case Ended(outcome: String)
  case Discarded
