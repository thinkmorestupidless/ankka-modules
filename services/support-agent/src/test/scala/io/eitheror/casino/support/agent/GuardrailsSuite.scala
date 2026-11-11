package io.eitheror.casino.support.agent

import io.eitheror.casino.support.Settings
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.domain.OwnIds

/** A turn's guardrails (T044; FR-021–FR-023, research R8): which run, in what order, and what each refuses. */
class GuardrailsSuite extends munit.FunSuite:
  private val own   = "0b6f3c2e-6a51-4d6e-9a3f-1f6c0e2d9b11"
  private val other = "7c1d0a4b-2e3f-4a5b-8c6d-9e0f1a2b3c4d"
  private val body  = ConfigBody(maxInputChars = 40)

  private def names(b: ConfigBody, s: Settings) = Guardrails.of(b, s, OwnIds.only(own)).map(_.name)

  test("with no judge: the length, other-player and softening, in that order"):
    assertEquals(names(body, Settings()), Vector("max-input-length(40)", "other-player", "softening"))

  test("with a judge: override after other-player, and safeguarding for a real-money brand"):
    val judged = Settings(judge = Some("jev"))
    assertEquals(names(body, judged), Vector("max-input-length(40)", "other-player", "override", "softening"))
    assertEquals(names(body, judged.copy(realMoney = true)),
      Vector("max-input-length(40)", "other-player", "override", "safeguarding", "softening"))

  test("safeguarding for a brand with no real money that opted in"):
    assert(names(body.copy(safeguardingOptIn = true), Settings(judge = Some("jev"))).contains("safeguarding"))

  test("the length is the brand's"):
    val length = Guardrails.of(body, Settings(), OwnIds.only(own)).head
    assert(length.checkInput("x" * 40).isRight)
    assert(length.checkInput("x" * 41).isLeft)

  test("other-player refuses a turn naming another player and a reply naming an internal address, never the value"):
    val g = Guardrails.otherPlayer(OwnIds.only(own))
    assertEquals(g.checkInput(s"what did $other win?"), Left("the message names another player's id"))
    assertEquals(g.checkInput(s"my id is $own"), Right(()))
    assertEquals(g.checkOutput("I checked wallet.goldvibe.svc.cluster.local"), Left("the reply names an internal address"))
    assertEquals(g.checkOutput("Your email is ann@example.com"), Right(()))

  test("softening checks a reply, never a turn"):
    val g = Guardrails.softening
    assert(g.checkOutput("Your break is set. Don't worry, you can always change it.").isLeft)
    assertEquals(g.checkOutput("Your break is in effect until 2026-12-01. It cannot be shortened."), Right(()))
    assertEquals(g.checkInput("Don't worry about my break"), Right(()))
