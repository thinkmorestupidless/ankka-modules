package io.eitheror.casino.rg.features

import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.it.{Callers, Reply}
import io.eitheror.casino.rg.it.Callers.Read

import java.time.{Duration, Instant}

/**
 * features/responsible-gambling/deposits.feature (US2, FR-007, FR-011). The deposit itself is the wallet's
 * (002): "no balance changes", "nothing is credited" and "recorded captured" are asserted here on the answer
 * the wallet reads, through its own decoder ([[Callers.wallet]]), and are 002's scenarios to hold end to end.
 */
class DepositsFeatures extends RgFeatures("deposits.feature"):

  private var used24 = 0L
  private var until: Instant = Instant.EPOCH
  private var answers: Vector[Reply] = Vector.empty
  private var walletRead: Read = Read.Allowed

  Given("a daily deposit limit of {string} and {string} deposited in the last {string} hours") { (v: String, used: String, hours: String) =>
    assertEquals(hours, "24")
    putLimit("DEPOSIT_DAILY", minor(v).toString): Unit
    used24 = minor(used)
  }

  When("a deposit of {string} arrives") { (amount: String) => last = mayDeposit(minor(amount), used24, used24, used24) }

  Then("it is refused as over the limit, naming the daily deposit limit, {string} used and {string} remaining") { (used: String, rem: String) =>
    val a = last.json
    assertEquals((a.text("decision"), a.text("period"), a.long("used"), a.long("remaining")),
      (Some("over_limit"), Some("daily"), Some(minor(used)), Some(minor(rem))))
    assertEquals(a.long("limit"), limitValue(status(), "DEPOSIT_DAILY"))
  }

  Then("no balance changes") { () => assertEquals(Callers.wallet(Some(last)), Read.Refused("over_limit")) }

  Then("it is accepted") { () => assertEquals(last.str("decision"), "allowed", last.text) }

  Then("it is refused") { () => assertEquals(last.str("decision"), "over_limit", last.text) }

  Given("a daily deposit limit of {string}, a weekly deposit limit of {string} and a monthly deposit limit of {string}") {
    (d: String, w: String, m: String) =>
      putLimit("DEPOSIT_DAILY", minor(d).toString): Unit
      putLimit("DEPOSIT_WEEKLY", minor(w).toString): Unit
      putLimit("DEPOSIT_MONTHLY", minor(m).toString): Unit
  }

  When("a deposit would pass any of the three") { () =>
    answers = Vector(
      mayDeposit(50_100),                           // passes all three
      mayDeposit(5_000, last7d = 26_000, last30d = 26_000), // within the day, past the week and not the month
      mayDeposit(5_000, last30d = 46_000)           // past the month only
    )
  }

  Then("it is refused naming the first limit it would pass, in that order") { () =>
    assertEquals(answers.map(a => (a.str("decision"), a.str("period"))),
      Vector(("over_limit", "daily"), ("over_limit", "weekly"), ("over_limit", "monthly")))
  }

  Given("the player is on a break until tomorrow") { () =>
    until = kit.now().plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    assertEquals(setBreak(until).status, 200)
  }

  When("any deposit arrives") { () => last = mayDeposit(1) }

  Then("it is refused as cooling off, naming the end of the break") { () =>
    assertEquals(last.str("decision"), "cooling_off")
    assertEquals(Instant.parse(last.str("endsAt")), until)
  }

  Given("the player is self-excluded") { () => assertEquals(excludePermanently().status, 200) }

  Then("it is refused as excluded") { () => assertEquals(last.str("decision"), "excluded") }

  Given("the player's break ended an hour ago") { () =>
    assertEquals(setBreak(kit.now().plus(Duration.ofHours(2))).status, 200)
    kit.advance(Duration.ofHours(3))
  }

  When("a deposit arrives") { () => last = mayDeposit(10_000) }

  Then("the break does not refuse it") { () => assertEquals(last.str("decision"), "allowed", last.text) }

  // ── unreachable: the wallet's rule (002 FR-022), held here through its decoder ──────────────────

  Given("responsible gambling cannot be reached") { () => () }

  When("the wallet asks it about a deposit") { () => walletRead = Callers.wallet(None) }

  Then("the wallet refuses the deposit as unavailable") { () => assert(walletRead.isInstanceOf[Read.Unavailable], walletRead.toString) }

  Then("nothing is credited") { () => assertNotEquals(walletRead, Read.Allowed) }

  Given("a deposit the wallet refused as unavailable") { () =>
    walletRead = Callers.wallet(None)
    assert(walletRead.isInstanceOf[Read.Unavailable])
  }

  Given("responsible gambling can be reached again") { () => () }

  When("the same deposit arrives again") { () => last = mayDeposit(10_000) }

  Then("the wallet asks responsible gambling about it again") { () => walletRead = Callers.wallet(Some(last)) }

  Then("the deposit is credited once") { () => assertEquals(walletRead, Read.Allowed) }

  // ── a refusal for a limit, as the cashier passes it on (players' and the wallet's) ───────────────

  Given("a deposit refused for a deposit limit") { () =>
    putLimit("DEPOSIT_DAILY", "10000"): Unit
    last = mayDeposit(10_001)
  }

  When("the cashier learns of it") { () => walletRead = Callers.playersDeposit(Some(last)) }

  Then("the payment is recorded captured and not credited, with the reason") { () =>
    assertEquals(walletRead, Read.Refused("over_limit"))
    assertEquals(Callers.wallet(Some(last)), Read.Refused("over_limit"))
  }

  Then("the player is told the remaining allowance in their currency") { () =>
    assertEquals((last.json.long("remaining"), last.json.text("period")), (Some(10_000L), Some("daily")))
  }
