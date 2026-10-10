package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.entity.*

/** features/wallet/bets.feature (User Story 1). */
class BetsFeatures extends WalletFeatures("bets.feature"):

  private def betNamed: Bet =
    val k = lastBetKey.getOrElse(fail("no bet was placed"))
    (state.openBets ++ state.settledBets).find(_.key == k).getOrElse(fail(s"no bet $k"))

  Given("the wallet has a real balance of {string} and no restricted funds") { (r: String) => seeding(_.copy(real = long(r))) }


  Given("round {string} is open with a bet of {string}") { (round: String, a: String) =>
    // Turnover is pending so that a rollback that restored it would show.
    seeding(_.copy(real = 10000L, pending = 5000L))
    bet(long(a), round): Unit
  }

  /** One text, two uses: before any action it opens the round; after one it says the round is open. */
  Given("round {string} is open") { (round: String) =>
    if lastOutcome == null then
      seeding(_.copy(real = 10000L))
      bet(1000L, round): Unit
    else assert(state.openBetsOf(round).nonEmpty, state.openBets.toString)
  }

  Given("no open bet for round {string}") { (_: String) => () }

  When("the bet in round {string} is rolled back") { (round: String) =>
    act(run(walletKit.call(WalletEntity.rollbackBet)(RollbackBet(nextKey("rb"), round, None, now())))): Unit
  }

  When("a correction debit of {string} is placed allowing a negative balance") { (a: String) =>
    act(bet(long(a), "r-correction", allowNegative = true)): Unit
  }

  When("a win of {string} is credited twice for round {string} under the same key") { (a: String, round: String) =>
    val key = nextKey("win")
    firstOutcome = act(run(walletKit.call(WalletEntity.creditWin)(CreditWin(key, round, long(a), now())))).outcome
    act(run(walletKit.call(WalletEntity.creditWin)(CreditWin(key, round, long(a), now())))): Unit
  }

  Then("the bet records a real leg of {string} and no restricted funds legs") { (a: String) =>
    assertEquals((lastOutcome.realLeg, lastOutcome.recordLegs), (Some(long(a)), Vector.empty))
  }

  Then("round {string} is no longer open") { (round: String) => assertEquals(state.openBetsOf(round), Vector.empty) }

  Then("the bet is settled") { () => assertEquals(betNamed.status, BetStatus.Settled) }

  Then("the bet is voided") { () => assertEquals(betNamed.status, BetStatus.Voided) }

  Then("the bet is marked as a loss") { () => assertEquals(betNamed.status, BetStatus.Loss) }

  Then("the win's wallet type mirrors the bet's") { () =>
    assertEquals(lastOutcome.walletType, betOutcome.walletType)
    assert(lastOutcome.walletType.exists(_.nonEmpty), lastOutcome.toString)
  }

  Then("deposit turnover pending is not restored") { () =>
    assert(before.depositTurnoverPending < 5000L, "the bet should have reduced turnover pending")
    assertEquals(state.depositTurnoverPending, before.depositTurnoverPending)
  }

  Then("no round opens") { () => assertEquals(state.openBets, Vector.empty) }

  Then("the debit is funded entirely from real balance") { () =>
    assertEquals((lastOutcome.realLeg, lastOutcome.recordLegs), (Some(1000L), Vector.empty))
  }

  Then("no contribution is recorded") { () => assertEquals(betNamed.contributions, Vector.empty) }

  Then("the second call returns the balances the first call returned") { () =>
    assertEquals((lastOutcome.realBalance, lastOutcome.restrictedBalance), (firstOutcome.realBalance, firstOutcome.restrictedBalance))
    assertEquals(lastOutcome, firstOutcome)
  }

  Then("the second call writes nothing") { () => assertEquals(lastEvents, Vector.empty) }

  Then("the win is refused as {string}") { (phrase: String) => refusedAs(phrase) }

  Then("the bet is accepted") { () => assert(lastOutcome.accepted, lastOutcome.toString) }

  Then("the bet funds nothing") { () =>
    assertEquals((lastOutcome.realLeg.getOrElse(0L), lastOutcome.recordLegs), (0L, Vector.empty))
  }
