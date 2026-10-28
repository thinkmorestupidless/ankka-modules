package io.eitheror.casino.rg.profile

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.rg.profile.Limits.Decision

/** The original's `isMoreRestrictive` truth table (`RgRoutesSpec`, the six cases) and the staging of data-model.md. */
class LimitsSuite extends munit.FunSuite with LogCapturing:

  test("the original's truth table"):
    assertEquals(Limits.isMoreRestrictive(Some(100), None), false, "a removal")
    assertEquals(Limits.isMoreRestrictive(None, Some(100)), true, "a limit where none applies")
    assertEquals(Limits.isMoreRestrictive(Some(100), Some(50)), true, "tightening")
    assertEquals(Limits.isMoreRestrictive(Some(100), Some(500)), false, "loosening")
    assertEquals(Limits.isMoreRestrictive(Some(100), Some(100)), false, "the same value")

  test("a player's tighter change applies at once, a looser or equal one or a removal is staged"):
    assertEquals(Limits.decide(byOperator = false, None, Some(100)), Decision.ApplyNow)
    assertEquals(Limits.decide(byOperator = false, Some(100), Some(50)), Decision.ApplyNow)
    assertEquals(Limits.decide(byOperator = false, Some(100), Some(500)), Decision.Stage)
    assertEquals(Limits.decide(byOperator = false, Some(100), Some(100)), Decision.Stage)
    assertEquals(Limits.decide(byOperator = false, Some(100), None), Decision.Stage)
    assertEquals(Limits.decide(byOperator = false, None, None), Decision.Stage)

  test("an operator's change applies at once, whichever way it goes"):
    assertEquals(Limits.decide(byOperator = true, Some(100), Some(500)), Decision.ApplyNow)
    assertEquals(Limits.decide(byOperator = true, Some(100), None), Decision.ApplyNow)

  test("a value is a positive whole number"):
    assertEquals(Limits.parseValue("5000"), Some(5000L))
    assertEquals(Limits.parseValue(" 60 "), Some(60L))
    List("", "0", "-5", "12.5", "1e3", "abc", "9" * 19).foreach(v => assertEquals(Limits.parseValue(v), None, v))

  test("the seven types, a session limit carrying no currency"):
    assertEquals(LimitType.all.map(_.name).toSet,
      Set("DEPOSIT_DAILY", "DEPOSIT_WEEKLY", "DEPOSIT_MONTHLY", "LOSS_DAILY", "LOSS_WEEKLY", "LOSS_MONTHLY", "SESSION_MINUTES"))
    assertEquals(LimitType.parse("deposit_daily"), Some(LimitType.DepositDaily))
    assertEquals(LimitType.parse("WAGER_DAILY"), None)
    assertEquals(LimitKey.of(LimitType.SessionMinutes, Some("EUR")), LimitKey("SESSION_MINUTES", None))
    assertEquals(LimitKey.of(LimitType.DepositDaily, Some("EUR")).timer, "DEPOSIT_DAILY-EUR")
