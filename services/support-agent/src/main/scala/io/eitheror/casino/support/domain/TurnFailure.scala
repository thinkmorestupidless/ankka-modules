package io.eitheror.casino.support.domain

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.eitheror.casino.support.conversation.Side

/**
 * What a turn that failed came to (contracts/routes.md's `error`; FR-021–FR-023): refused by one of the turn's
 * guardrails, on the player's message or on the reply, or not answered at all. ankka refuses as `Forbidden` with
 * `guardrail '<name>': <reason>`; this service's guardrails give a reply's refusal a reason starting "the reply", and
 * a check that could not be made (an unreachable judge) is never `Forbidden`, so it is unavailable, not a refusal.
 */
enum TurnFailure:
  case Refused(guardrail: String, side: Side)
  case Unavailable

object TurnFailure:
  private val Named = "guardrail '([^']+)': (.*)".r

  def of(failure: Throwable): TurnFailure =
    CommandError.from(failure) match
      case Some(CommandError(Named(name, reason), ErrorCode.Forbidden, _)) =>
        Refused(name, if reason.startsWith("the reply") then Side.Output else Side.Input)
      case _ => Unavailable

  /** What a player is told when a guardrail refused their message or the reply: the guardrail, never the value. */
  def reply(guardrail: String): String = s"I can't answer that message: it was refused by the $guardrail check."
