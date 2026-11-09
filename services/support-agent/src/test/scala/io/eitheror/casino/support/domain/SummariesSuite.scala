package io.eitheror.casino.support.domain

import io.eitheror.casino.contract.money.Currency
import org.scalacheck.{Gen, Prop}

import java.time.Instant

/** contracts/tools.md's summaries, as the original's suites check them (`test_write_tools.py`, `test_rg_tools.py`). */
class SummariesSuite extends munit.ScalaCheckSuite:
  private val gbp = Currency.unsafe("GBP")

  test("a withdrawal: the original's text exactly, in major units"):
    assertEquals(Summaries.withdrawal(5000L, gbp, None), "Withdraw 50.00 GBP from your account")

  test("major units at the currency's exponent: 2, 0 and 3"):
    assertEquals(Summaries.withdrawal(5L, Currency.unsafe("EUR"), None), "Withdraw 0.05 EUR from your account")
    assertEquals(Summaries.withdrawal(5000L, Currency.unsafe("JPY"), None), "Withdraw 5000 JPY from your account")
    assertEquals(Summaries.withdrawal(5000L, Currency.unsafe("KWD"), None), "Withdraw 5.000 KWD from your account")

  test("a withdrawal that forfeits bonus funds names the forfeit"):
    assertEquals(Summaries.withdrawal(5000L, gbp, Some(1250L)),
      "Withdraw 50.00 GBP from your account — this forfeits 12.50 GBP of bonus funds")
    assertEquals(Summaries.withdrawal(5000L, gbp, Some(0L)), "Withdraw 50.00 GBP from your account")

  test("closing a session names it"):
    assertEquals(Summaries.closeSession("gs-9"), "Close your active game session (session ID: gs-9)")

  test("a deposit limit explains the regulatory delay"):
    val s = Summaries.depositLimit("weekly", 10000L, gbp)
    assertEquals(s, "Set a weekly deposit limit of 100.00. Note: this limit will not take effect immediately due to " +
      "regulatory requirements — there is a mandatory cooling-off delay before it applies.")
    assert(s.toLowerCase.contains("regulatory") || s.toLowerCase.contains("delay"))

  test("a cool-off names its end and that it cannot be shortened"):
    val s = Summaries.coolOff(Instant.parse("2026-07-01T00:00:00Z"))
    assert(s.contains("2026-07-01") && s.contains("cannot be shortened"), s)

  test("a self-exclusion is permanent and cannot be reversed"):
    val s = Summaries.selfExclusion.toLowerCase
    assert(s.contains("permanent") && s.contains("cannot be reversed"), s)

  // FR-022's required statements hold for any argument, by construction (research R8).
  property("every cool-off summary names its end and that it cannot be shortened"):
    Prop.forAll(Gen.choose(0L, 4_000_000_000L)) { seconds =>
      val until = Instant.ofEpochSecond(seconds)
      val s     = Summaries.coolOff(until)
      s.contains(until.toString) && s.contains("cannot be shortened")
    }

  property("every withdrawal summary names the amount and the currency"):
    Prop.forAll(Gen.choose(0L, Long.MaxValue / 10), Gen.oneOf("GBP", "EUR", "JPY", "KWD", "USD")) { (minor, code) =>
      val c = Currency.unsafe(code)
      Summaries.withdrawal(minor, c, None).contains(s"${Summaries.major(minor, c)} $code")
    }
