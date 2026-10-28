package io.eitheror.casino.wallet.funding

import Clawback.Baseline
import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The clawback of spec 002 FR-015a (tasks T009): deposits since award = max(0, deposits now −
 * deposits at award); clawback = max(0, real now − real at award − deposits since award); clamped
 * to real balance where applied. Expected values are worked from that formula and from the
 * features' figures, never read off the code.
 */
class ClawbackSuite extends munit.FunSuite with LogCapturing:

  test("FR-015a: winnings since the award less deposits since the award") {
    // real 1000 → 3000, deposits 5000 → 6000: 3000 − 1000 − 1000
    assertEquals(Clawback.of(Some(Baseline(realAtAward = 1000, totalDepositsAtAward = 5000)), realNow = 3000, totalDepositsNow = 6000), 1000L)
  }

  test("FR-015a: deposits since the award are floored at zero") {
    // deposits total cannot fall, but a smaller figure must not inflate the clawback: 2500 − 1000 − 0
    assertEquals(Clawback.of(Some(Baseline(1000, 5000)), realNow = 2500, totalDepositsNow = 4000), 1500L)
  }

  test("FR-015a: a player who lost since the award is clawed back nothing") {
    assertEquals(Clawback.of(Some(Baseline(1000, 0)), realNow = 500, totalDepositsNow = 0), 0L)
  }

  test("FR-015a: real growth that deposits explain is not clawed back") {
    assertEquals(Clawback.of(Some(Baseline(1000, 0)), realNow = 3000, totalDepositsNow = 2000), 0L)
  }

  test("FR-017: a record with no baseline claws back nothing") {
    assertEquals(Clawback.of(None, realNow = 100000, totalDepositsNow = 0), 0L)
  }

  test("lifecycle.feature: a player cancelling a reward with an activation baseline claws back winnings since activation less deposits since") {
    // awarded at real 300 with 1000 deposited; now real 1000 with 1200 deposited: 1000 − 300 − 200
    assertEquals(Clawback.of(Some(Baseline(300, 1000)), realNow = 1000, totalDepositsNow = 1200), 500L)
  }

  test("lifecycle.feature: a player cancelling a reward with no activation baseline claws back nothing") {
    assertEquals(Clawback.of(None, realNow = 1000, totalDepositsNow = 1200), 0L)
  }

  test("FR-023: one clawback for several records comes from the earliest awarded record's baseline") {
    val earliest = Some(Baseline(1000, 0))
    val later    = Some(Baseline(0, 0))
    assertEquals(Clawback.ofEarliest(Seq(earliest, later), realNow = 3000, totalDepositsNow = 0), 2000L)
  }

  test("FR-017: when the earliest record has no baseline, nothing is clawed back") {
    assertEquals(Clawback.ofEarliest(Seq(None, Some(Baseline(0, 0))), realNow = 3000, totalDepositsNow = 0), 0L)
    assertEquals(Clawback.ofEarliest(Seq.empty, realNow = 3000, totalDepositsNow = 0), 0L)
  }

  test("restricted-funds.feature: cancelling an award forfeits its record and applies the clawback clamped to real balance") {
    val rec  = Record(id = "r1", grantRef = "g1", status = Status.Active, balance = 2000)
    val plan = Resolver.cancelRecord(Wallet(realBalance = 300), Vector(rec), clawback = 500).fold(d => fail(d.toString), identity)
    assertEquals(plan.closedRecords, Vector("r1"))
    assertEquals(plan.recordUpdates.map(u => (u.status, u.balance)), Vector((Status.Forfeited, 0L)))
    assertEquals(plan.clawbackApplied, 300L)
    assertEquals(plan.newRealBalance, 0L)
  }

  test("restricted-funds.feature: cancelling with no active record still applies the clawback to real balance") {
    val plan = Resolver.cancelRecord(Wallet(realBalance = 300), Vector.empty, clawback = 500).fold(d => fail(d.toString), identity)
    assert(plan.closedRecords.isEmpty && plan.recordUpdates.isEmpty, plan.toString)
    assertEquals(plan.clawbackApplied, 300L)
    assertEquals(plan.newRealBalance, 0L)
  }
