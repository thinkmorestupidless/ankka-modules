package io.eitheror.casino.support.limits

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant

/** `TurnWindow` and `Budget` (T045; data-model.md, FR-024, R10) through ankka's unit kit. */
class LimitsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-11T23:59:30Z")

  test("a turn window takes the limit's turns in a minute, and refuses the next with when to try again"):
    val kit   = KeyValueEntityTestKit.of(TurnWindow, "goldvibe/p-1")
    val takes = (0 until 3).map(i => kit.call(TurnWindow.take)(TakeTurn(at.plusSeconds(i), 3)).replyValue)
    assertEquals(takes.distinct, Vector(Take.Allowed))
    assertEquals(kit.call(TurnWindow.take)(TakeTurn(at.plusSeconds(20), 3)).replyValue, Take.Refused(40L))
    assertEquals(kit.currentState.times.size, 3)

  test("a budget adds each turn's increase once, and is spent at the limit"):
    val kit = KeyValueEntityTestKit.of(Budget, "goldvibe/2026-11-11")
    kit.call(Budget.add)(AddTokens("s-1", 0L, 600L)): Unit
    kit.call(Budget.add)(AddTokens("s-1", 0L, 600L)): Unit
    val after = kit.call(Budget.add)(AddTokens("s-2", 0L, 400L)).replyValue
    assertEquals(after.tokens, 1000L)
    assert(kit.call(Budget.get).replyValue.spent(1000L))

  test("a budget is a UTC day's"):
    assertEquals(Budget.id("goldvibe", at).toString, "goldvibe/2026-11-11")
    assertEquals(Budget.id("goldvibe", at.plusSeconds(31)).toString, "goldvibe/2026-11-12")
    assertEquals(TurnWindow.id("goldvibe", "p-1").toString, "goldvibe/p-1")
