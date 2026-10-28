package io.eitheror.casino.wallet.funding

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The original's `withdraw_test.go`, case for case (research R2, tasks T049). Each test is named
 * `withdraw_test.go/<Go test name>`; expected values are the Go test's.
 */
class WithdrawTablesSuite extends munit.FunSuite with LogCapturing:

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long, maxConvertible: Long,
      openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def pendingCapRecord(id: String, depositsSinceRelease: Long, remainingAllowance: Long, priority: Long): Record =
    Record(id = id, status = Status.ConvertedPendingCap, depositsSinceRelease = depositsSinceRelease,
      remainingAllowance = remainingAllowance, priority = priority)

  private def findUpdate(updates: Seq[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  private def plan(w: Wallet, recs: Vector[Record], in: WithdrawInput): WithdrawPlan =
    Resolver.withdraw(w, recs, in).fold(d => fail(s"unexpected deny: $d"), identity)

  private def deny(w: Wallet, recs: Vector[Record], in: WithdrawInput): Deny =
    Resolver.withdraw(w, recs, in).fold(identity, p => fail(s"expected a deny, got $p"))

  test("withdraw_test.go/TestWithdraw_HappyPath") {
    val p = plan(Wallet(realBalance = 50000), Vector.empty, WithdrawInput(amount = 10000))
    assertEquals(p.newReal, 40000L)
    assertEquals((p.paid, p.voided), (10000L, 0L))
    assert(p.forfeits.isEmpty && p.capDraws.isEmpty && p.recordUpdates.isEmpty, p.toString)
    assertEquals(p.restrictedBalance, 0L)
  }

  test("withdraw_test.go/TestWithdraw_InsufficientFunds") {
    assertEquals(deny(Wallet(realBalance = 5000), Vector.empty, WithdrawInput(amount = 10000)).code, Codes.InsufficientFunds)
  }

  test("withdraw_test.go/TestWithdraw_AlreadyPending") {
    val d = deny(Wallet(realBalance = 50000), Vector.empty,
      WithdrawInput(amount = 10000, pendingWithdrawalRequestRef = "withdrawal-request/existing-123"))
    assertEquals(d.code, Codes.WithdrawalRequestAlreadyPending)
  }

  test("withdraw_test.go/TestWithdraw_BlockedTurnover") {
    val d = deny(Wallet(realBalance = 50000, depositTurnoverPending = 20000), Vector.empty, WithdrawInput(amount = 10000))
    assertEquals(d.code, Codes.WithdrawalBlockedTurnover)
  }

  test("withdraw_test.go/TestWithdraw_CapWorkedExample_ExhaustsAndCloses") {
    val rec = pendingCapRecord("restricted-funds/cap1", 20000, 10000, 0)
    val p   = plan(Wallet(realBalance = 35000), Vector(rec), WithdrawInput(amount = 35000))
    assertEquals((p.paid, p.voided), (30000L, 5000L))
    assertEquals(p.newReal, 0L)
    val cd = p.capDraws(0)
    assertEquals((cd.fromDeposits, cd.fromAllowance, cd.closes), (20000L, 10000L, true))
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.status, upd.closedNow), (Status.Closed, true))
    assertEquals(p.restrictedBalance, 0L)
  }

  test("withdraw_test.go/TestWithdraw_CapWithinAllowance_StaysOpen") {
    val rec = pendingCapRecord("restricted-funds/cap2", 20000, 10000, 0)
    val p   = plan(Wallet(realBalance = 35000), Vector(rec), WithdrawInput(amount = 15000))
    assertEquals((p.paid, p.voided), (15000L, 0L))
    assertEquals(p.newReal, 20000L)
    val cd = p.capDraws(0)
    assertEquals((cd.fromDeposits, cd.fromAllowance, cd.closes), (15000L, 0L, false))
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.depositsSinceRelease, upd.remainingAllowance, upd.status), (5000L, 10000L, Status.ConvertedPendingCap))
    assertEquals(p.restrictedBalance, 0L)
  }

  test("withdraw_test.go/TestWithdraw_CapBeyondDeposits_PartialAllowanceNoClosure") {
    val rec = pendingCapRecord("restricted-funds/cap3", 20000, 10000, 0)
    val p   = plan(Wallet(realBalance = 35000), Vector(rec), WithdrawInput(amount = 25000))
    assertEquals((p.paid, p.voided), (25000L, 0L))
    assertEquals(p.newReal, 10000L)
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.depositsSinceRelease, upd.remainingAllowance), (0L, 5000L))
    assert(!p.capDraws(0).closes, p.capDraws(0).toString)
  }

  test("withdraw_test.go/TestWithdraw_CapClosureOnZeroReal") {
    val rec = pendingCapRecord("restricted-funds/cap4", 0, 50000, 0)
    val p   = plan(Wallet(realBalance = 10000), Vector(rec), WithdrawInput(amount = 10000))
    assertEquals((p.newReal, p.voided), (0L, 0L))
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.status, upd.remainingAllowance), (Status.Closed, 40000L))
    assert(p.capDraws(0).closes, "closes forced by real hitting zero")
  }

  test("withdraw_test.go/TestWithdraw_ActiveBonus_ForfeitsInFull") {
    val rec = activeRecord("restricted-funds/1", 7500, 0, 0, 0, 0)
    val p   = plan(Wallet(realBalance = 50000), Vector(rec), WithdrawInput(amount = 10000, clawback = 0))
    assertEquals(p.newReal, 40000L)
    assertEquals(p.clawback, 0L)
    assertEquals(p.forfeits.map(f => (f.amount, f.policy)), Vector((7500L, Policy.FundsAndWinnings)))
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.status, upd.balance), (Status.Forfeited, 0L))
    assert(p.requiresConfirmation)
    assertEquals(p.restrictedBalance, 0L)
  }

  test("withdraw_test.go/TestWithdraw_ActiveBonusWithClawback") {
    val rec = activeRecord("restricted-funds/1", 10000, 0, 0, 0, 0)
    val p   = plan(Wallet(realBalance = 100000), Vector(rec), WithdrawInput(amount = 5000, clawback = 90000))
    assertEquals(p.clawback, 90000L)
    assertEquals(p.newReal, 5000L)
  }

  test("withdraw_test.go/TestWithdraw_InsufficientAfterBonusForfeit") {
    val rec = activeRecord("restricted-funds/1", 5000, 0, 0, 0, 0)
    val d   = deny(Wallet(realBalance = 20000), Vector(rec), WithdrawInput(amount = 10000, clawback = 15000))
    assertEquals(d.code, Codes.WithdrawalInsufficientAfterBonusForfeit)
  }

  test("withdraw_test.go/TestWithdraw_PolicyFunds_NoClawbackContribution") {
    val rec = activeRecord("restricted-funds/1", 8000, 0, 0, 0, 0).copy(forfeitOnWithdrawal = Policy.Funds)
    val p   = plan(Wallet(realBalance = 50000), Vector(rec), WithdrawInput(amount = 10000, clawback = 99999))
    assertEquals(p.clawback, 0L)
    assertEquals(p.newReal, 40000L)
    assertEquals(p.forfeits.map(f => (f.policy, f.cancelGrant)), Vector((Policy.Funds, false)))
  }

  test("withdraw_test.go/TestWithdraw_PolicyNone_RecordUntouchedNoConfirmation") {
    val rec = activeRecord("restricted-funds/1", 5000, 0, 0, 0, 0).copy(forfeitOnWithdrawal = Policy.None)
    val p   = plan(Wallet(realBalance = 50000), Vector(rec), WithdrawInput(amount = 10000))
    assert(p.forfeits.isEmpty, p.forfeits.toString)
    assert(!p.requiresConfirmation)
    assertEquals(p.newReal, 40000L)
    assertEquals(p.restrictedBalance, 5000L)
    assert(p.recordUpdates.isEmpty, p.recordUpdates.toString)
  }

  test("withdraw_test.go/TestWithdraw_PolicyCancelPromotion_ForfeitsAndMarksGrantForCancel") {
    val rec = activeRecord("restricted-funds/1", 6000, 0, 0, 0, 0).copy(forfeitOnWithdrawal = Policy.CancelPromotion, grantRef = "grant/g1")
    val p   = plan(Wallet(realBalance = 50000), Vector(rec), WithdrawInput(amount = 10000, clawback = 1000))
    assertEquals(p.clawback, 1000L)
    assertEquals(p.newReal, 39000L)
    assertEquals(p.cancelGrants, Vector("grant/g1"))
    assertEquals(p.forfeits.map(_.cancelGrant), Vector(true))
  }

  test("withdraw_test.go/TestWithdraw_TwoCapRecords_DrawDownNoVoid") {
    val rec0 = pendingCapRecord("restricted-funds/cap0", 0, 3000, 0)
    val rec1 = pendingCapRecord("restricted-funds/cap1", 0, 2000, 1)
    val p    = plan(Wallet(realBalance = 100000), Vector(rec0, rec1), WithdrawInput(amount = 4500))
    assertEquals((p.paid, p.voided), (4500L, 0L))
    assertEquals((p.capDraws(0).fromAllowance, p.capDraws(0).closes), (3000L, true))
    assertEquals((p.capDraws(1).fromAllowance, p.capDraws(1).closes), (1500L, false))
    assertEquals(findUpdate(p.recordUpdates, rec1.id).remainingAllowance, 500L)
  }

  test("withdraw_test.go/TestWithdraw_TwoCapRecords_DrawDownWithVoidBothClose") {
    val rec0 = pendingCapRecord("restricted-funds/cap0", 0, 3000, 0)
    val rec1 = pendingCapRecord("restricted-funds/cap1", 0, 2000, 1)
    val p    = plan(Wallet(realBalance = 100000), Vector(rec0, rec1), WithdrawInput(amount = 6000))
    assertEquals((p.paid, p.voided), (5000L, 1000L))
    assert(p.capDraws.forall(_.closes), p.capDraws.toString)
    assertEquals(findUpdate(p.recordUpdates, rec0.id).status, Status.Closed)
    assertEquals(findUpdate(p.recordUpdates, rec1.id).status, Status.Closed)
  }

  test("withdraw_test.go/TestWithdraw_ActiveAndCapTogether") {
    val active = activeRecord("restricted-funds/active1", 1000, 0, 0, 0, 0)
    val cap    = pendingCapRecord("restricted-funds/cap1", 0, 5000, 0)
    val p      = plan(Wallet(realBalance = 20000), Vector(active, cap), WithdrawInput(amount = 3000, clawback = 500))
    assertEquals(p.forfeits.map(_.recordId), Vector(active.id))
    assertEquals(p.capDraws.map(cd => (cd.recordId, cd.fromAllowance)), Vector((cap.id, 3000L)))
    assertEquals(p.clawback, 500L)
    // realAfter = 20000 - 500 = 19500; newReal = 19500 - 3000 = 16500.
    assertEquals(p.newReal, 16500L)
    assertEquals((p.paid, p.voided), (3000L, 0L))
  }

  test("withdraw_test.go/TestWithdraw_ClawbackAppliesWhenAnyQualifyingForfeitPresent") {
    val fundsRec    = activeRecord("restricted-funds/funds1", 2000, 0, 0, 0, 0).copy(forfeitOnWithdrawal = Policy.Funds)
    val winningsRec = activeRecord("restricted-funds/winnings1", 3000, 0, 0, 0, 1).copy(forfeitOnWithdrawal = Policy.FundsAndWinnings)
    val p = plan(Wallet(realBalance = 50000), Vector(fundsRec, winningsRec), WithdrawInput(amount = 10000, clawback = 2000))
    assertEquals(p.clawback, 2000L)
    assertEquals(p.newReal, 38000L)
    assertEquals(p.forfeits.size, 2)
  }

  test("withdraw_test.go/TestWithdraw_NoCapRecords_NeverVoids") {
    val rec = activeRecord("restricted-funds/1", 0, 0, 0, 0, 0)
    val p   = plan(Wallet(realBalance = 20000), Vector(rec), WithdrawInput(amount = 10000))
    assertEquals((p.paid, p.voided), (10000L, 0L))
    assert(p.capDraws.isEmpty, p.capDraws.toString)
  }
