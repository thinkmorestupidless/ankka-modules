package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.funding.Status

/** features/wallet/deposits.feature (User Story 3). */
class DepositsFeatures extends WalletFeatures("deposits.feature"):

  private def depositRecord(reference: String): DepositRecord =
    state.recentDeposits.find(_.paymentReference == reference).getOrElse(fail(s"no deposit record $reference"))

  private def lastReference: String = lastEvents.collectFirst { case e: WalletEvent.DepositConfirmed => e.key }.getOrElse(fail("no deposit"))

  Given("a player with a wallet in {string} and a real balance of {string}") { (c: String, r: String) =>
    currency = c
    seeding(_.copy(real = long(r)))
  }

  Given("the brand's turnover multiplier is {string}") { (m: String) => brandTerms = TurnoverTerms(long(m), "gross_wagered") }

  When("a deposit of {string} under payment reference {string} is confirmed") { (a: String, ref: String) =>
    act(deposit(long(a), ref)): Unit
  }

  Then("a deposit record holds a required turnover of {string} with status {string}") { (t: String, status: String) =>
    val d = depositRecord(lastReference)
    assertEquals((d.requiredTurnover, d.status.toLowerCase), (long(t), status))
  }

  Then("deposit turnover pending is {string}") { (p: String) => assertEquals(state.depositTurnoverPending, long(p)) }

  Given("a player with a wallet in {string} whose deposit under payment reference {string} is confirmed") { (c: String, ref: String) =>
    currency = c
    firstOutcome = deposit(5000L, ref).outcome
  }

  When("a deposit under payment reference {string} arrives again") { (ref: String) => act(deposit(5000L, ref)): Unit }

  Then("the first answer is returned") { () => assertEquals(lastOutcome, firstOutcome) }

  Then("the deposit record is {string}") { (status: String) => assertEquals(depositRecord(lastReference).status.toLowerCase, status) }

  Then("deposit turnover pending does not change") { () =>
    assertEquals(state.depositTurnoverPending, before.depositTurnoverPending)
  }

  Given("a player with a wallet in {string} and deposit turnover pending of {string}") { (c: String, p: String) =>
    currency = c
    seeding(_.copy(pending = long(p)))
  }

  When("the player places a bet with a real leg of {string}") { (a: String) =>
    if !opened then seeding(s => s.copy(real = s.real + long(a)))
    act(bet(long(a), "r1")): Unit
    assertEquals(lastOutcome.realLeg, Some(long(a)))
  }

  When("the player places a bet funded only from restricted funds") { () =>
    addRecord(record("r1", 5000L))
    act(bet(2000L, "r1")): Unit
    assertEquals((lastOutcome.realLeg, lastOutcome.walletType), (Some(0L), Some("bonus")))
  }

  Given("a player with a wallet in {string} whose bet with a real leg of {string} reduced deposit turnover pending to {string}") {
    (c: String, leg: String, to: String) =>
      currency = c
      seeding(_.copy(real = long(leg), pending = long(to) + long(leg)))
      bet(long(leg), "r1"): Unit
      assertEquals(state.depositTurnoverPending, long(to))
  }

  When("that bet is rolled back") { () =>
    act(run(walletKit.call(WalletEntity.rollbackBet)(RollbackBet(nextKey("rb"), "r1", lastBetKey, now())))): Unit
  }

  Given("a player with a wallet in {string} and a restricted funds record awaiting its cap with deposits since conversion of {string}") {
    (c: String, d: String) =>
      currency = c
      addRecord(record("r1", 0L, status = Status.ConvertedPendingCap, edit = _.copy(depositsSinceRelease = long(d), remainingAllowance = 1000L)))
  }

  Then("the record's deposits since conversion is {string}") { (d: String) => assertEquals(focused.depositsSinceRelease, long(d)) }

  Given("a player with no wallet in {string}") { (c: String) =>
    currency = c
    noWallet = true
  }

  When("a deposit of {string} in {string} under payment reference {string} is confirmed") { (a: String, c: String, ref: String) =>
    assertEquals(c, currency)
    assert(!walletKit.currentState.created, "the wallet existed before the deposit")
    act(deposit(long(a), ref)): Unit
  }

  Then("the player has a wallet in {string} created with that deposit") { (c: String) =>
    assertEquals((state.currency, state.created, state.realBalance, state.depositOrdinal), (c, true, 1000L, 1))
  }
