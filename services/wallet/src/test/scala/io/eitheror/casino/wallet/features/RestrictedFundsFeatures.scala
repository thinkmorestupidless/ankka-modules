package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.WalletEvent.BetPlaced
import io.eitheror.casino.wallet.funding.{Leg, Status}

/** features/wallet/restricted-funds.feature (User Story 2). */
class RestrictedFundsFeatures extends WalletFeatures("restricted-funds.feature"):

  private def placedBet: Bet =
    lastEvents.collectFirst { case e: BetPlaced => e.bet }.getOrElse(fail(s"no bet was placed: $lastOutcome"))

  private def award(amount: Long, multiplier: Long): Result =
    run(walletKit.call(WalletEntity.award)(Award(nextKey("grant"), "bonus-1", amount, multiplier, 5000L, 0L, None, Rules(),
      maxConcurrentAwards, now())))

  private def realFor(amount: Long): Unit = if !opened && seed.real < amount then seeding(_.copy(real = 10000L))

  When("restricted funds of {string} are awarded with a wagering multiplier of {string}") { (a: String, m: String) =>
    act(award(long(a), long(m))): Unit
  }

  Then("one restricted funds record is open and active with a wagering requirement of {string} and release progress of {string}") {
    (req: String, progress: String) =>
      val open = state.openRecords
      assertEquals(open.map(r => (r.status, r.releaseRequirement, r.releasedProgress)), Vector((Status.Active, long(req), long(progress))))
  }

  Given("an active restricted funds record of {string} and a real balance of {string}") { (b: String, r: String) =>
    addRecord(record("r1", long(b)))
    seeding(_.copy(real = long(r)))
  }

  Given("the funding order is {string} with partial splits on") { (order: String) =>
    editSeedRecord(focusId)(_.copy(fundingOrder = order))
    switches = switches.copy(partialSplitsAllowed = true)
  }

  Given("the funding order is {string} with partial splits off") { (order: String) =>
    editSeedRecord(focusId)(_.copy(fundingOrder = order))
    switches = switches.copy(partialSplitsAllowed = false)
  }

  Then("the bet's real leg is {string} and its restricted funds leg is {string}") { (real: String, leg: String) =>
    assertEquals((lastOutcome.realLeg, lastOutcome.recordLegs), (Some(long(real)), Vector(Leg(focusId, long(leg)))))
  }

  Then("the record's open round count is {string}") { (n: String) => assertEquals(focused.openRoundCount, long(n)) }

  Then("the record funds all {string}") { (a: String) =>
    assertEquals((lastOutcome.realLeg, lastOutcome.recordLegs.map(_.amount).sum), (Some(0L), long(a)))
  }

  Given("active restricted funds records {string} of {string} at priority {string} and {string} of {string} at priority {string}") {
    (a: String, ab: String, ap: String, b: String, bb: String, bp: String) =>
      addRecord(record(a, long(ab), priority = long(ap)))
      addRecord(record(b, long(bb), priority = long(bp)))
  }

  When("the player places a bet of {string} funded from restricted funds") { (a: String) => act(bet(long(a), "r1")): Unit }

  Then("record {string} is drawn to {string} first") { (name: String, to: String) =>
    assertEquals(recordState(s"rf-$name").balance, long(to))
    assertEquals(lastOutcome.recordLegs.headOption.map(_.recordId), Some(s"rf-$name"))
  }

  Then("record {string} funds the remaining {string}") { (name: String, a: String) =>
    assertEquals(lastOutcome.recordLegs.find(_.recordId == s"rf-$name").map(_.amount), Some(long(a)))
  }

  Given("an active restricted funds record with a wagering requirement of {string} and release progress of {string} at a contribution rate of {string}") {
    (req: String, progress: String, rate: String) =>
      addRecord(record("r1", 2000L, edit = _.copy(releaseRequirement = long(req), releasedProgress = long(progress),
        contributionDefault = Some(long(rate)), maxConvertible = 5000L)))
  }

  When("the player places a bet of {string} funded from real balance") { (a: String) =>
    realFor(long(a))
    act(bet(long(a), "r1")): Unit
  }

  Then("the record converts: its balance is credited to real balance") { () =>
    val was = recordBefore(focusId)
    assertEquals(focused.status, Status.ConvertedPendingCap)
    assertEquals(state.realBalance, before.realBalance - lastOutcome.realLeg.getOrElse(0L) + was.balance)
  }

  Then("the record awaits its cap with its remaining allowance set to its maximum convertible amount") { () =>
    assertEquals((focused.status, focused.remainingAllowance), (Status.ConvertedPendingCap, recordBefore(focusId).maxConvertible))
  }

  Then("the bet reports the conversion") { () => assertEquals(lastOutcome.conversions, Vector(focusId)) }

  Given("a bet in round {string} funded half from real balance and half from a restricted funds record under win allocation {string}") {
    (round: String, allocation: String) =>
      addRecord(record("r1", 1000L, edit = _.copy(winAllocation = allocation, releaseRequirement = 100000L)))
      seeding(_.copy(real = 1000L))
      val placed = bet(2000L, round)
      assertEquals((placed.outcome.realLeg, placed.outcome.recordLegs), (Some(1000L), Vector(Leg(focusId, 1000L))))
  }

  Then("real balance receives {string} and the record receives {string}") { (real: String, rec: String) =>
    assertEquals(state.realBalance - before.realBalance, long(real))
    assertEquals(focused.balance - recordBefore(focusId).balance, long(rec))
  }

  Then("the record's open round count falls by one") { () =>
    assertEquals(recordBefore(focusId).openRoundCount - focused.openRoundCount, 1L)
  }

  Given("a bet in round {string} funded partly from a restricted funds record that has since converted") { (round: String) =>
    val converted = record("r1", 0L, status = Status.ConvertedPendingCap, edit = _.copy(remainingAllowance = 1000L, openRoundCount = 1L))
    addRecord(converted)
    seeding(s => s.copy(real = 500L, bets = s.bets :+ Bet("tx-old", round, "tx-old-id", 1000L, 500L, Vector(Leg(converted.id, 500L)),
      Vector.empty, "split", BetStatus.Open, "s1", "slot-a", t0)))
  }

  Then("the record's share is paid to real balance and not to the converted record") { () =>
    assertEquals(state.realBalance - before.realBalance, 1000L)
    assertEquals(focused.balance, recordBefore(focusId).balance)
  }

  Given("an active restricted funds record whose scope excludes the game {string} with violation mode {string}") {
    (game: String, mode: String) =>
      addRecord(record("r1", 2000L, edit = _.copy(scopeExclude = Set(s"game:$game"), scopeViolationMode = mode)))
      seeding(_.copy(real = 10000L))
  }

  When("the player places a bet of {string} on the game {string}") { (a: String, game: String) =>
    act(bet(long(a), "r1", game = game)): Unit
  }

  Given("an active restricted funds record with a maximum stake of {string} enforced as {string}") { (max: String, how: String) =>
    addRecord(record("r1", 2000L, edit = _.copy(maxStake = Some(long(max)), maxStakeEnforcement = how)))
    seeding(_.copy(real = 10000L))
  }

  Then("the bet is funded in full") { () =>
    assert(lastOutcome.accepted, lastOutcome.toString)
    assertEquals(lastOutcome.realLeg.getOrElse(0L) + lastOutcome.recordLegs.map(_.amount).sum, placedBet.amount)
  }

  Then("the bet's contribution base is {string}") { (base: String) =>
    // At the brand's rate of 100%, the record's contribution is its base.
    assertEquals(placedBet.contributions.find(_.recordId == focusId).map(_.amount), Some(long(base)))
  }

  Given("an active restricted funds record with a balance of {string} and one open round {string}") { (b: String, round: String) =>
    val r = record("r1", long(b), edit = _.copy(openRoundCount = 1L))
    addRecord(r)
    seeding(s => s.copy(bets = s.bets :+ Bet("tx-old", round, "tx-old-id", 100L, 0L, Vector(Leg(r.id, 100L)), Vector.empty,
      "bonus", BetStatus.Open, "s1", "slot-a", t0)))
  }

  When("the restricted funds are forfeited") { () =>
    act(run(walletKit.call(WalletEntity.forfeit)(ForfeitAward(nextKey("forfeit"), None, now())))): Unit
  }

  Then("the record's balance is {string}") { (b: String) => assertEquals(focused.balance, long(b)) }

  Given("the clawback computed from the record's baseline is {string}") { (c: String) => clawbacks += focusId -> long(c) }

  Given("the clawback computed from the award's baseline is {string}") { (c: String) =>
    // The award's record is no longer active; the wallet still holds its baseline.
    val gone = record("old", 0L, status = Status.Forfeited)
    addRecord(gone)
    clawbacks += gone.id -> long(c)
  }

  When("the award is cancelled") { () =>
    act(run(walletKit.call(WalletEntity.cancel)(CancelAward(nextKey("cancel"), None, now())))): Unit
  }

  Then("the clawback applied is {string}") { (c: String) => assertEquals(lastOutcome.clawbackApplied, Some(long(c))) }

  Then("no record changes") { () => assertEquals(state.records, before.records) }

  Given("a restricted funds record awaiting its cap") { () =>
    addRecord(record("r1", 0L, status = Status.ConvertedPendingCap, edit = _.copy(remainingAllowance = 1000L)))
  }

  When("the pending cap is closed") { () =>
    act(run(walletKit.call(WalletEntity.closePendingCap)(ClosePendingCap(nextKey("close"), now())))): Unit
  }

  Then("the record is closed") { () => assertEquals(focused.status, Status.Closed) }

  Given("no restricted funds record awaiting its cap") { () => () }

  Given("active restricted funds records {string} as primary and {string}") { (a: String, b: String) =>
    addRecord(record(a, 1000L, priority = 0L))
    addRecord(record(b, 1000L, priority = 1L))
  }

  When("the award of {string} is made primary") { (name: String) =>
    act(run(walletKit.call(WalletEntity.switchPrimary)(SwitchPrimary(nextKey("switch"), s"grant-$name", now())))): Unit
  }

  Then("record {string} sorts first on the next bet") { (name: String) =>
    assertEquals(state.openRecords.headOption.map(_.id), Some(s"rf-$name"))
  }

  Given("a forfeited restricted funds record {string}") { (name: String) => addRecord(record(name, 0L, status = Status.Forfeited)) }

  Given("no restricted funds record for award {string}") { (_: String) => () }

  Given("the brand allows {string} concurrent award and one restricted funds record is active") { (n: String) =>
    maxConcurrentAwards = Some(n.toInt)
    addRecord(record("r1", 1000L))
  }

  Given("the brand sets no limit on concurrent awards and one restricted funds record is active") { () =>
    maxConcurrentAwards = None
    addRecord(record("r1", 1000L))
  }

  When("a second award of {string} arrives") { (a: String) => act(award(long(a), 1L)): Unit }

  Then("it is accepted") { () =>
    assert(lastOutcome.accepted, lastOutcome.toString)
    assertEquals(state.openRecords.count(_.status == Status.Active), 2)
  }

  Given("a restricted balance of {string} with no open restricted funds record") { (r: String) =>
    seeding(_.copy(restrictedWithoutRecords = long(r), real = 10000L))
  }
