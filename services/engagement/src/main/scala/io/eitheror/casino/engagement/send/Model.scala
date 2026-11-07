package io.eitheror.casino.engagement.send

import com.thinkmorestupidless.ankka.core.Personal

import java.time.Instant

/** A field of a send's data: personal, since a send route's carries links and addresses (R15). */
final case class DataField(name: String, value: Personal[String])

/**
 * What a send is asked to do (data-model.md `Send`). `trigger` is the event's name for a fact, the `CrmEvent` key for
 * the send route, `identify` for an identify with no event (FR-010) and `erasure` for the deletion (R15). `event` is
 * the name tracked, if any; `transactional` the `CrmEvent` key whose transactional id a configuration may set.
 * `channel` is `crm` for what the CRM's campaigns read and `email` for a send the route asked for. `calls` are the
 * kinds the send makes, in order (R5): a fact's `identify`, `track`, `transactional`; an identify's `identify`; the
 * route's `transactional`; erasure's `delete`.
 */
final case class SendRequest(
    key: String,
    playerId: String,
    trigger: String,
    event: Option[String],
    sendClass: String,
    channel: String,
    factId: String,
    occurredAt: Instant,
    transactional: Option[String] = None,
    recipient: Option[Personal[String]] = None,
    data: Vector[DataField] = Vector.empty,
    calls: Vector[String] = Vector(Kind.Identify, Kind.Track, Kind.Transactional)
):
  def routed: Boolean = channel == SendRequest.Email

object SendRequest:
  val Crm   = "crm"
  val Email = "email"

object Kind:
  val Identify      = "identify"
  val Track         = "track"
  val Transactional = "transactional"
  val Delete        = "delete"

/**
 * What an audit record says of a send (contracts/facts.md): `refused` for FR-011's refusal, `retried` and `discarded` for
 * an operator's act. `at` is the update that made it, so the audit consumer writes the record for that update only.
 */
final case class Note(kind: String, seq: Int, by: Option[String], at: Instant)

/** One call of a send: `identify`, `track`, `transactional` or `delete`, its attempts, and what it came to. */
final case class Call(kind: String, attempts: Int, outcome: String, status: Option[Int], lastAnswer: Option[String], at: Instant)

/**
 * A send's record (FR-007): the request, each call, and the outcome — `pending` while it runs, then one of
 * `Outcome.all`. `step` is the call in hand, which a retry of a parked send resumes; `parkAt` the attempts at it
 * after which it parks (four, or one more than it had when an operator retried it).
 */
final case class SendState(
    request: Option[SendRequest] = None,
    calls: Vector[Call] = Vector.empty,
    outcome: String = Outcome.Pending,
    reason: Option[String] = None,
    step: Option[String] = None,
    parkAt: Int = 0,
    parkedAt: Option[Instant] = None,
    discardedBy: Option[String] = None,
    discardReason: Option[Personal[String]] = None,
    updatedAt: Option[Instant] = None,
    note: Option[Note] = None
):
  /** The note `kind` made by this update at `at`, numbered after the last. */
  def noted(kind: String, by: Option[String], at: Instant): SendState =
    copy(note = Some(Note(kind, note.fold(1)(_.seq + 1), by, at)), updatedAt = Some(at))

  /** The note made by the update that produced this state, if any. */
  def freshNote: Option[Note] = note.filter(n => updatedAt.contains(n.at))

  def attemptsAt(kind: String): Int = calls.find(_.kind == kind).fold(0)(_.attempts)

  /** The call recorded, replacing an earlier record of the same kind. */
  def record(c: Call): SendState = copy(calls = calls.filterNot(_.kind == c.kind) :+ c, updatedAt = Some(c.at))

  def ended: Boolean = outcome != Outcome.Pending && outcome != Outcome.Parked

object Outcome:
  val Pending             = "pending"
  val Accepted            = "accepted"
  val Refused             = "refused"
  val SkippedStale        = "skipped_stale"
  val NoConfiguration     = "no_configuration"
  val NoAddress           = "no_address"
  val MarketingSuppressed = "marketing_suppressed"
  val AccountClosed       = "account_closed"
  val Parked              = "parked"
  val Discarded           = "discarded"

  val all: Vector[String] = Vector(Pending, Accepted, Refused, SkippedStale, NoConfiguration, NoAddress, MarketingSuppressed,
    AccountClosed, Parked, Discarded)

/** An operator's act on a parked send (FR-025). */
final case class Retry(operator: String)
final case class Discard(operator: String, reason: String)
