package io.eitheror.casino.support.domain

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.eitheror.casino.support.conversation.Side

/** How a failed turn is read (T046): a guardrail's refusal by name and side, or unavailable. */
class TurnFailureSuite extends munit.FunSuite:
  private def forbidden(m: String) = CommandError(m, ErrorCode.Forbidden)

  test("a guardrail's refusal of the message names it, on the input"):
    assertEquals(TurnFailure.of(forbidden("guardrail 'other-player': the message names an email address")),
      TurnFailure.Refused("other-player", Side.Input))
    assertEquals(TurnFailure.of(forbidden("guardrail 'safeguarding': question 'expresses-self-harm'")),
      TurnFailure.Refused("safeguarding", Side.Input))
    assertEquals(TurnFailure.of(forbidden("guardrail 'max-input-length(2000)': input is 2400 characters, over the 2000 limit")),
      TurnFailure.Refused("max-input-length(2000)", Side.Input))

  test("a refusal of the reply is on the output"):
    assertEquals(TurnFailure.of(forbidden("guardrail 'softening': the reply softens a protective control with \"don't worry\"")),
      TurnFailure.Refused("softening", Side.Output))

  test("a judge that could not be reached, a model that declined, or any other failure is unavailable"):
    Seq(
      CommandError("guardrail 'override' could not be checked: connection refused", ErrorCode.Unavailable),
      CommandError("guardrail 'override' could not be checked: timed out", ErrorCode.Timeout),
      forbidden("test declined the request"),
      RuntimeException("boom")
    ).foreach(e => assertEquals(TurnFailure.of(e), TurnFailure.Unavailable, e.getMessage))

  test("a refusal wrapped once by a transport is still read"):
    val wrapped = RuntimeException("stream failed", forbidden("guardrail 'other-player': the reply names an internal address"))
    assertEquals(TurnFailure.of(wrapped), TurnFailure.Refused("other-player", Side.Output))

  test("the player is told which check refused, not what it found"):
    assertEquals(TurnFailure.reply("other-player"), "I can't answer that message: it was refused by the other-player check.")
