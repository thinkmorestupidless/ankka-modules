package io.eitheror.casino.support.conversation

import java.time.Instant

/** Why a conversation ended (FR-017): an approved exclusion, or an approved break until it ends. */
enum EndReason:
  case SelfExcluded
  case OnBreak(until: Instant)

/** Where a conversation stands (data-model.md `Conversation`). */
enum Status:
  case Open
  case Awaiting(approvalId: String, tool: String)
  case Ended(reason: EndReason)
  case Forgotten

/** What a tool's call came to, for its trail and its audit record. */
enum Outcome:
  case Ok
  case Refused(code: String)
  case Unavailable
  case TimedOut

  def wire: String = this match
    case Ok         => "ok"
    case Refused(_) => "refused"
    case Unavailable => "unavailable"
    case TimedOut   => "timed_out"

enum Side:
  case Input, Output

/**
 * The trail ankka's memory does not keep (research R6), for one session. It holds no message text and no
 * personal value: the arguments are redacted (`Redaction`), and the player id is the one the session is bound to.
 */
final case class ConversationState(
    playerId: String = "",
    configVersion: Int = 0,
    status: Status = Status.Open,
    breakUntil: Option[Instant] = None,
    lastActivity: Instant = Instant.EPOCH,
    tokensCounted: Long = 0L,
    turns: Int = 0,
    /** The approval the player approved and its tool has not yet answered: the write's key (research). */
    approved: Option[String] = None,
    startedAt: Option[Instant] = None,
    /** The last approval decided, so a second decision on it is `already_decided` (FR-015). */
    lastDecided: Option[String] = None,
    /** What the last approved tool came to, for the resume's `action_result` (research R3). */
    lastAction: Option[ActionDone] = None,
    /** The bonus funds the awaited or approved withdrawal's summary named as forfeited, if it named any (FR-013). */
    namedForfeit: Option[Long] = None
):
  def started: Boolean = playerId.nonEmpty

  /** Whether the conversation is closed at `at`: for good after an exclusion, until its end after a break (FR-017). */
  def endedAt(at: Instant): Boolean = status match
    case Status.Ended(EndReason.SelfExcluded)   => true
    case Status.Ended(EndReason.OnBreak(until)) => at.isBefore(until)
    case _                                      => false

  def awaiting: Option[(String, String)] = status match
    case Status.Awaiting(id, tool) => Some((id, tool))
    case _                         => None

/** An approved tool's call, as `action_result` reports it: its tool, whether the service applied it, and the detail. */
final case class ActionDone(approvalId: String, tool: String, success: Boolean, detail: String)

/** data-model.md's events, each named by its manifest's last part (`support.conversation.<name>`). */
enum ConversationEvent:
  case Started(playerId: String, configVersion: Int, at: Instant)
  case TurnTaken(at: Instant, tokensBefore: Long, tokensAfter: Long)
  case ToolCalled(callId: String, tool: String, arguments: Map[String, String], outcome: Outcome, detail: Option[String],
      approvalId: Option[String], at: Instant)
  case ApprovalRequested(approvalId: String, tool: String, arguments: Map[String, String], summary: String,
      expiresAt: Instant, at: Instant, forfeit: Option[Long] = None)
  case Decided(approvalId: String, tool: String, approved: Boolean, by: String, expired: Boolean, at: Instant)
  case GuardrailRejected(guardrail: String, side: Side, retried: Boolean, at: Instant)
  case HandedOff(at: Instant)
  case Ended(reason: EndReason, at: Instant)
  case Forgotten(at: Instant)

  def at: Instant

// ── commands ────────────────────────────────────────────────────────────────────────────────────

final case class Start(playerId: String, configVersion: Int, at: Instant)
final case class Turned(at: Instant, tokensAfter: Long)
final case class ToolCall(callId: String, tool: String, arguments: Map[String, String], outcome: Outcome, approvalId: Option[String],
    detail: Option[String], at: Instant)
final case class Requested(approvalId: String, tool: String, arguments: Map[String, String], summary: String,
    expiresAt: Instant, at: Instant, forfeit: Option[Long] = None)
final case class Decide(approvalId: String, approved: Boolean, by: String, expired: Boolean, at: Instant)
final case class Rejected(guardrail: String, side: Side, retried: Boolean, at: Instant)
final case class At(at: Instant)
final case class End(reason: EndReason, at: Instant)

/** A command's answer: the state, or the refusal's code (`session_not_yours`, `already_decided`, …). */
final case class Answer(state: ConversationState, refusal: Option[String] = None)
