package io.eitheror.casino.provider.core

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
    outcome: String,
    nextRetryAt: Option[Long] = None,
    abandoned: Option[Abandonment] = None
):
  def scheduled: Boolean          = runId.nonEmpty
  def lastAnswered: Option[Attempt] = attempts.reverse.find(_.httpStatus.isDefined)

object Callback:
  val Pending       = "pending"
  val Answered      = "answered"
  val GaveUp        = "gave_up"
  val MalformedBody = "malformed_body"
  val Unreachable   = "unreachable"
  // The house provider's outcomes (spec 026 data-model, Callback): the brand accepted, refused for
  // good, never answered within the retry schedule's cap, or an operator gave up on it.
  val Acknowledged  = "acknowledged"
  val Refused       = "refused"
  val Exhausted     = "exhausted"
  val Abandoned     = "abandoned"

  /** The outcomes after which nothing is sent again. */
  val Final: Set[String] = Set(Answered, GaveUp, MalformedBody, Unreachable, Acknowledged, Refused, Exhausted, Abandoned)

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

/** Who gave up on a callback, why, and when (spec 026 FR-021). */
final case class Abandonment(by: String, reason: String, at: Long)

enum CallbackEvent:
  case Scheduled(runId: String, sessionId: String, brand: String, kind: String, route: String, body: String, step: Option[Int], at: Long)
  case AttemptRecorded(attempt: Attempt)
  case Ended(outcome: String)
  /** `Deliver` will send again at `at` (spec 026 R6). */
  case RetryPlanned(at: Long)
  case AbandonedBy(abandonment: Abandonment)
  case Discarded
