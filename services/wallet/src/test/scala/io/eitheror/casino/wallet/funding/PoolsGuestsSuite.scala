package io.eitheror.casino.wallet.funding

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The original's `internal/funding/pools_guests_test.go` (spec 025 T018, T024): every resolver that
 * moves real balance splits the movement across the pools, and refuses `POOLS_INVARIANT` rather
 * than plan from, or to, a pair that does not sum to real balance. Each test is named
 * `pools_guests_test.go/<Go test name>`; expected values are the Go test's.
 *
 * The original's pool assertions all live in this one file (its `placebet_test.go`, `creditwin_test.go`
 * and the other tables assert none), so they are ported here as one suite rather than spread across
 * the table suites.
 *
 * Not ported, each for a reason:
 *  - every `…_UnstampedUnchanged` case and `TestReturnPools_UnstampedAndIdempotentWriteNothing`'s
 *    unstamped half: there is no unstamped wallet (spec 025 clarification 2026-10-28).
 *  - `TestCreditWinPools_UnknownSplitUsesSettlementRatio`: every bet records its parts here; the
 *    no-parts case is `PoolsSuite`'s `win bet leg zero`.
 *  - `TestRealSplitFromParams`: the original's guest ABI could not tell an absent part from 0; here
 *    a bet's parts are a field of the bet, so there is nothing to derive.
 *  - The return, reversal and real adjustment cases run in the entity, which owns those movements
 *    here; their arithmetic is `Pools.restored` and `Pools.debit`, asserted below, and their events
 *    in `PoolsFeatures`.
 */
class PoolsGuestsSuite extends munit.FunSuite with LogCapturing:

  private def stamped(real: Long, dep: Long, conv: Long, restricted: Long = 0L): Wallet =
    Wallet(realBalance = real, restrictedBalance = restricted, pools = Some(Pools(dep, conv)))

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long, maxConvertible: Long,
      openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def pendingCapRecord(id: String, depositsSinceRelease: Long, remainingAllowance: Long, priority: Long): Record =
    Record(id = id, status = Status.ConvertedPendingCap, depositsSinceRelease = depositsSinceRelease,
      remainingAllowance = remainingAllowance, priority = priority)

  private def right[A](e: Either[Deny, A]): A = e.fold(d => fail(s"unexpected deny: $d"), identity)

  private def wantPools(got: Option[Pools], dep: Long, conv: Long, newReal: Long)(using munit.Location): Unit =
    assertEquals(got, Some(Pools(dep, conv)))
    assertEquals(got.get.total, newReal, "invariant broken: the pools do not sum to the new real balance")

  private def wantPoolsDeny[A](e: Either[Deny, A])(using munit.Location): Unit =
    assertEquals(e.left.map(_.code), Left(Codes.PoolsInvariant), s"want ${Codes.PoolsInvariant}, got $e")

  private val flags = Flags(defaultRate = 100)

  // ── place_bet ────────────────────────────────────────────────────────────

  test("pools_guests_test.go/TestPlaceBetPools_ConvertedFirst") {
    val plan = right(Resolver.placeBet(stamped(10000, 5000, 5000), Vector.empty, Stake(1000), flags))
    wantPools(plan.newPools, 5000, 4000, plan.newRealBalance)
    assertEquals(plan.parts, Parts(0, 1000))
  }

  test("pools_guests_test.go/TestPlaceBetPools_SpillsToDeposit") {
    val plan = right(Resolver.placeBet(stamped(10000, 5000, 5000), Vector.empty, Stake(6000), flags))
    wantPools(plan.newPools, 4000, 0, plan.newRealBalance)
    assertEquals(plan.parts, Parts(1000, 5000))
  }

  test("pools_guests_test.go/TestPlaceBetPools_ConversionCreditedBeforeStake") {
    val rec  = activeRecord("rf/1", 3000, 0, 0, 0, 0)
    val plan = right(Resolver.placeBet(stamped(1000, 1000, 0, restricted = 3000), Vector(rec), Stake(500), flags))
    assertEquals((plan.converting.size, plan.newRealBalance), (1, 3500L))
    wantPools(plan.newPools, 1000, 2500, 3500)
    assertEquals(plan.parts, Parts(0, 500))
  }

  test("pools_guests_test.go/TestPlaceBetPools_RecordFundedBetMovesNoPool") {
    val rec  = activeRecord("rf/1", 500, 1000, 0, 0, 0)
    val plan = right(Resolver.placeBet(stamped(100, 100, 0, restricted = 500), Vector(rec), Stake(300), flags.copy(partialSplits = true)))
    assertEquals(plan.realLeg, 100L)
    wantPools(plan.newPools, 0, 0, plan.newRealBalance)
    assertEquals(plan.parts, Parts(100, 0))
  }

  test("pools_guests_test.go/TestPlaceBetPools_DriftedPairRefused") {
    wantPoolsDeny(Resolver.placeBet(stamped(500, 200, 200), Vector.empty, Stake(100), flags))
  }

  test("pools_guests_test.go/TestPlaceBetPools_NoAllowNegativeOverdrawRefused") {
    val deny = Resolver.placeBet(stamped(100, 100, 0), Vector.empty, Stake(500), flags)
    assertEquals(deny.left.map(_.code), Left(Codes.InsufficientFunds))
  }

  test("pools_guests_test.go/TestPlaceBetPools_AllowNegativeOverdrawChargesConverted") {
    val plan = right(Resolver.placeBet(stamped(500, 300, 200), Vector.empty, Stake(800), flags.copy(allowNegative = true)))
    assertEquals(plan.parts, Parts(300, 500))
    wantPools(plan.newPools, 0, -300, plan.newRealBalance)
    assertEquals(plan.newRealBalance, -300L)
  }

  test("pools_guests_test.go/TestPlaceBetPools_AllowNegativeFromNegativeConverted") {
    val plan = right(Resolver.placeBet(stamped(-100, 200, -300), Vector.empty, Stake(50), flags.copy(allowNegative = true)))
    assertEquals(plan.parts, Parts(50, 0))
    wantPools(plan.newPools, 150, -300, plan.newRealBalance)
  }

  test("pools_guests_test.go/TestPlaceBetPools_AllowNegativeDriftStillRefused") {
    wantPoolsDeny(Resolver.placeBet(stamped(500, 200, 200), Vector.empty, Stake(800), flags.copy(allowNegative = true)))
  }

  // ── credit_win ───────────────────────────────────────────────────────────

  test("pools_guests_test.go/TestCreditWinPools_BetRatio") {
    val legs = Legs(real = 6000, realParts = Some(Parts(1000, 5000)))
    val plan = right(Resolver.creditWin(stamped(4000, 4000, 0), Vector.empty, legs, 3000))
    wantPools(plan.newPools, 4500, 2500, plan.newRealBalance)
    assertEquals(plan.parts, Parts(500, 2500))
  }

  test("pools_guests_test.go/TestCreditWinPools_RoundingFavoursConverted") {
    val legs = Legs(real = 3, realParts = Some(Parts(1, 2)))
    val plan = right(Resolver.creditWin(stamped(4000, 4000, 0), Vector.empty, legs, 1000))
    wantPools(plan.newPools, 4333, 667, plan.newRealBalance)
    assertEquals(plan.parts, Parts(333, 667))
  }

  test("pools_guests_test.go/TestCreditWinPools_AllConvertedBet") {
    val legs = Legs(real = 1000, realParts = Some(Parts(0, 1000)))
    val plan = right(Resolver.creditWin(stamped(4000, 4000, 0), Vector.empty, legs, 700))
    wantPools(plan.newPools, 4000, 700, plan.newRealBalance)
    assertEquals(plan.parts, Parts(0, 700))
  }

  test("pools_guests_test.go/TestCreditWinPools_OrphanedShareIsConverted") {
    val legs = Legs(real = 600, records = Vector(Leg("rf/gone", 400)), realParts = Some(Parts(600, 0)))
    val plan = right(Resolver.creditWin(stamped(0, 0, 0), Vector.empty, legs, 1000))
    assertEquals(plan.realShare, 1000L)
    wantPools(plan.newPools, 600, 400, 1000)
    assertEquals(plan.parts, Parts(600, 400))
  }

  test("pools_guests_test.go/TestCreditWinPools_DriftedPairRefused") {
    wantPoolsDeny(Resolver.creditWin(stamped(4000, 1, 1), Vector.empty, Legs(real = 400, realParts = Some(Parts(400, 0))), 400))
  }

  // ── rollback_bet ─────────────────────────────────────────────────────────

  test("pools_guests_test.go/TestRollbackPools_RestoresRecordedParts") {
    val legs = Legs(real = 6000, realParts = Some(Parts(1000, 5000)))
    val plan = right(Resolver.rollbackBet(stamped(4000, 4000, 0), Vector.empty, legs, Vector.empty))
    wantPools(plan.newPools, 5000, 5000, plan.newRealBalance)
    assertEquals(plan.parts, Parts(1000, 5000))
  }

  test("pools_guests_test.go/TestRollbackPools_GoneRecordLegGoesToConverted") {
    val legs = Legs(real = 1000, records = Vector(Leg("rf/gone", 500)), realParts = Some(Parts(1000, 0)))
    val plan = right(Resolver.rollbackBet(stamped(0, 0, 0), Vector.empty, legs, Vector.empty))
    assertEquals(plan.realRestored, 1500L)
    wantPools(plan.newPools, 1000, 500, 1500)
    assertEquals(plan.parts, Parts(1000, 500))
  }

  test("pools_guests_test.go/TestRollbackPools_UnknownSplitRestoresToConverted") {
    val plan = right(Resolver.rollbackBet(stamped(100, 100, 0), Vector.empty, Legs(real = 400), Vector.empty))
    wantPools(plan.newPools, 100, 400, plan.newRealBalance)
    assertEquals(plan.parts, Parts(0, 400))
  }

  test("pools_guests_test.go/TestSettleLoss_MovesNoMoney") {
    right(Resolver.settleLoss(stamped(1000, 400, 600), Vector.empty, Legs(real = 100))): Unit
  }

  // ── withdrawals ──────────────────────────────────────────────────────────

  test("pools_guests_test.go/TestWithdrawPools_DepositFirst") {
    val plan = right(Resolver.withdraw(stamped(10000, 5000, 5000), Vector.empty, WithdrawInput(6000)))
    wantPools(plan.newPools, 0, 4000, plan.newReal)
    assertEquals((plan.paidParts, plan.voidedParts), (Parts(5000, 1000), Parts.zero))
  }

  test("pools_guests_test.go/TestWithdrawPools_CapVoidConvertedFirst") {
    val rec  = pendingCapRecord("rf/cap", 4000, 0, 0)
    val plan = right(Resolver.withdraw(stamped(10000, 5000, 5000), Vector(rec), WithdrawInput(6000)))
    assertEquals((plan.paid, plan.voided), (4000L, 2000L))
    wantPools(plan.newPools, 1000, 3000, plan.newReal)
    assertEquals((plan.paidParts, plan.voidedParts), (Parts(4000, 0), Parts(0, 2000)))
  }

  test("pools_guests_test.go/TestWithdrawPools_ClawbackConvertedFirst") {
    val rec  = activeRecord("rf/1", 10, 0, 0, 0, 0)
    val plan = right(Resolver.withdraw(stamped(5500, 5000, 500, restricted = 10), Vector(rec), WithdrawInput(1000, clawback = 800)))
    wantPools(plan.newPools, 3700, 0, plan.newReal)
    assertEquals((plan.clawbackParts, plan.paidParts), (Parts(300, 500), Parts(1000, 0)))
  }

  test("pools_guests_test.go/TestWithdrawPools_DriftedPairRefused") {
    wantPoolsDeny(Resolver.withdraw(stamped(10000, 100, 100), Vector.empty, WithdrawInput(600)))
  }

  test("pools_guests_test.go/TestReturnPools_RestoresRecordedParts") {
    val parts = Pools.restored(Parts(5000, 1000), 6000)
    assertEquals((Pools(0, 4000).credit(parts), parts), (Pools(5000, 5000), Parts(5000, 1000)))
  }

  test("pools_guests_test.go/TestReturnPools_LegacyAndMismatchedPartsGoToDeposit") {
    for (name, recorded) <- Seq("legacy" -> Parts.zero, "mismatch" -> Parts(1, 1)) do
      val parts = Pools.restored(recorded, 600)
      assertEquals((Pools(0, 100).credit(parts), parts), (Pools(600, 100), Parts(600, 0)), name)
  }

  test("pools_guests_test.go/TestReversePools_RestoresPaidParts") {
    val parts = Pools.restored(Parts(4000, 0), 4000)
    assertEquals(Pools(0, 1000).credit(parts), Pools(4000, 1000))
  }

  test("pools_guests_test.go/TestReversePools_LegacyToDepositAndUnstamped") {
    assertEquals(Pools(0, 1000).credit(Pools.restored(Parts.zero, 300)), Pools(300, 1000))
  }

  // ── cancel, admin adjust, provider credit ────────────────────────────────

  test("pools_guests_test.go/TestCancelPools_ClawbackConvertedFirst") {
    val rec  = activeRecord("rf/1", 20, 100, 40, 0, 0)
    val plan = right(Resolver.cancelRecord(stamped(5500, 5000, 500, restricted = 20), Vector(rec), 800))
    wantPools(plan.newPools, 4700, 0, plan.newRealBalance)
    assertEquals(plan.parts, Parts(300, 500))
  }

  test("pools_guests_test.go/TestCancelPools_NoRecordStillSplitsClawback") {
    val plan = right(Resolver.cancelRecord(stamped(100, 40, 60), Vector.empty, 70))
    wantPools(plan.newPools, 30, 0, plan.newRealBalance)
    assertEquals(plan.parts, Parts(10, 60))
  }

  // AdjustReal runs in the entity here; its arithmetic: a credit to deposit, a debit deposit-first,
  // an overdraft refused. The original records no parts on a credit; here the credit's parts are
  // (amount, 0), so a reader sees where it landed (data-model.md).
  test("pools_guests_test.go/TestAdjustRealPools") {
    assertEquals(Pools.credit(Pools(0, 0), Parts.ofDeposit(250)), Right((Pools(250, 0), Parts(250, 0))))
    assertEquals(Pools.debit(Pools(500, 5000), 800, convertedFirst = false), Right((Pools(0, 4700), Parts(500, 300))))
    wantPoolsDeny(Pools.debit(Pools(50, 50), 200, convertedFirst = false))
  }

  test("pools_guests_test.go/TestCreditPrimaryPools") {
    val plan = right(Resolver.creditPrimary(stamped(0, 0, 0), Vector.empty, 400))
    wantPools(plan.newPools, 0, 400, plan.newRealBalance)
    assertEquals(plan.parts, Parts(0, 400))

    val rec        = activeRecord("rf/1", 10, 100, 0, 0, 0)
    val restricted = right(Resolver.creditPrimary(stamped(50, 50, 0, restricted = 10), Vector(rec), 400))
    assertEquals((restricted.target, restricted.newPools, restricted.parts), ("restricted", None, Parts.zero))
  }

  // A chained lifecycle keeps deposit + converted == real balance at every step.
  test("pools_guests_test.go/TestPoolsInvariantAcrossLifecycle") {
    var w   = stamped(6000, 2000, 4000)
    val bet = right(Resolver.placeBet(w, Vector.empty, Stake(1000), flags))
    w = Wallet(bet.newRealBalance, pools = bet.newPools)
    val win = right(Resolver.creditWin(w, Vector.empty, Legs(real = 1000, realParts = Some(bet.parts)), 2500))
    w = Wallet(win.newRealBalance, pools = win.newPools)
    val wd = right(Resolver.withdraw(w, Vector.empty, WithdrawInput(3000)))
    val returned = Pools.restored(wd.paidParts + wd.voidedParts, 3000)
    val after    = wd.newPools.get.credit(returned)
    assertEquals(after, Pools(2000, 4000 - 1000 + 2500))
    assertEquals(after.total, wd.newReal + 3000)
  }
