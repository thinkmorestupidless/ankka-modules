package io.eitheror.casino.wallet.funding

/**
 * The original's `adjust_test.go`, case for case (research R2, tasks T058): `AdjustRestricted`
 * (admin-adjust.test.tl's restricted arm) and `CreditPrimary` (game-provider-credit.test.tl). Each
 * test is named `adjust_test.go/<Go test name>`; expected values are the Go test's.
 *
 * The Go wallet's `OpenRestrictedFundsRefs` pipe set has no counterpart: records are the wallet's
 * own state, so `TestAdjustRestricted_DebitToExactlyZero_ClosesForfeited` drops that field.
 */
class AdjustTablesSuite extends munit.FunSuite:

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long, maxConvertible: Long,
      openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def priRecord(id: String, priority: Long, grantedAt: String, balance: Long, releaseRequirement: Long,
      releasedProgress: Long, maxConvertible: Long, openRoundCount: Long): Record =
    activeRecord(id, balance, releaseRequirement, releasedProgress, maxConvertible, openRoundCount)
      .copy(priority = priority, grantedAt = grantedAt)

  private def findUpdate(updates: Seq[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  private def adjust(w: Wallet, recs: Vector[Record], amount: Long): AdjustPlan =
    Resolver.adjustRestricted(w, recs, amount).fold(d => fail(s"unexpected deny: $d"), identity)

  private def refused(w: Wallet, recs: Vector[Record], amount: Long): Deny =
    Resolver.adjustRestricted(w, recs, amount).fold(identity, p => fail(s"expected a deny, got $p"))

  test("adjust_test.go/TestAdjustRestricted_CreditOnNoOpenRecord_Refuses") {
    assertEquals(refused(Wallet(realBalance = 5000), Vector.empty, 1000).code, Codes.NoOpenRestrictedFunds)
  }

  test("adjust_test.go/TestAdjustRestricted_CreditOnActiveRecord_AddsToBalance") {
    val rec = activeRecord("restricted-funds/1", 1000, 0, 0, 0, 0)
    val p   = adjust(Wallet(realBalance = 5000, restrictedBalance = 1000), Vector(rec), 500)
    assertEquals((p.balanceBefore, p.balanceAfter), (1000L, 1500L))
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.status), (1500L, Status.Active))
    assertEquals(p.projection.restrictedBalance, 1500L)
  }

  test("adjust_test.go/TestAdjustRestricted_Debit") {
    val rec = activeRecord("restricted-funds/1", 3000, 0, 0, 0, 0)
    val p   = adjust(Wallet(realBalance = 5000, restrictedBalance = 3000), Vector(rec), -1000)
    assertEquals((p.balanceBefore, p.balanceAfter), (3000L, 2000L))
  }

  test("adjust_test.go/TestAdjustRestricted_DebitToExactlyZero_ClosesForfeited") {
    val rec = activeRecord("restricted-funds/1", 1000, 12540, 0, 0, 0)
    val p   = adjust(Wallet(realBalance = 5000, restrictedBalance = 1000), Vector(rec), -1000)
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.status, upd.closedNow), (0L, Status.Forfeited, true))
    assertEquals(p.closedRecords, Vector(rec.id))
    assertEquals(p.projection, Projection(present = true, restrictedBalance = 0L))
  }

  test("adjust_test.go/TestAdjustRestricted_DebitToZero_OpenRounds_StaysActive") {
    val rec = activeRecord("restricted-funds/1", 1000, 0, 0, 0, 1)
    val p   = adjust(Wallet(realBalance = 5000, restrictedBalance = 1000), Vector(rec), -1000)
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.status, upd.closedNow), (0L, Status.Active, false))
    assert(p.closedRecords.isEmpty, p.closedRecords.toString)
  }

  test("adjust_test.go/TestAdjustRestricted_PartialDebit_StaysActive") {
    val rec = activeRecord("restricted-funds/1", 1000, 0, 0, 0, 0)
    val p   = adjust(Wallet(realBalance = 0, restrictedBalance = 1000), Vector(rec), -400)
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.status, upd.closedNow, p.closedRecords.size), (600L, Status.Active, false, 0))
  }

  test("adjust_test.go/TestAdjustRestricted_CreditOnZeroBalanceRecord_Unchanged") {
    val rec = activeRecord("restricted-funds/1", 0, 0, 0, 0, 0)
    val p   = adjust(Wallet(realBalance = 0), Vector(rec), 300)
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.status, p.closedRecords.size), (300L, Status.Active, 0))
  }

  test("adjust_test.go/TestAdjustRestricted_EmptyingPrimaryLeavesSecondRecordOpen") {
    val a = priRecord("a", 1, "2026-01-01T00:00:00Z", 1000, 0, 0, 0, 0)
    val b = priRecord("b", 2, "2026-01-01T00:00:00Z", 2000, 0, 0, 0, 0)
    val p = adjust(Wallet(realBalance = 0, restrictedBalance = 3000), Vector(a, b), -1000)
    assertEquals(p.closedRecords, Vector("a"))
    assertEquals(p.projection.restrictedBalance, 2000L)
  }

  test("adjust_test.go/TestAdjustRestricted_DebitThatDoesNotReachZero") {
    val rec = activeRecord("restricted-funds/1", 2000, 0, 0, 0, 0)
    val p   = adjust(Wallet(realBalance = 5000, restrictedBalance = 2000), Vector(rec), -500)
    assertEquals(p.balanceAfter, 1500L)
  }

  test("adjust_test.go/TestAdjustRestricted_DebitExceedsBalance_Refuses") {
    val rec = activeRecord("restricted-funds/1", 500, 0, 0, 0, 0)
    assertEquals(refused(Wallet(realBalance = 0, restrictedBalance = 500), Vector(rec), -600).code, Codes.DebitExceedsBalance)
  }

  test("adjust_test.go/TestAdjustRestricted_TwoRecords_TargetsPrimaryOnly") {
    val a = priRecord("a", 1, "2026-01-01T00:00:00Z", 1000, 0, 0, 0, 0)
    val b = priRecord("b", 2, "2026-01-01T00:00:00Z", 2000, 0, 0, 0, 0)
    val p = adjust(Wallet(realBalance = 0, restrictedBalance = 3000), Vector(a, b), 500)
    assertEquals((p.recordId, p.recordUpdates.size), ("a", 1))
    assertEquals(p.projection.restrictedBalance, 3500L)
  }

  // ── CreditPrimary: game-provider-credit.test.tl ──────────────────────────

  test("adjust_test.go/TestCreditPrimary_NoActiveBonus_CreditsReal") {
    val p = Resolver.creditPrimary(Wallet(realBalance = 5000), Vector.empty, 2500)
    assertEquals((p.target, p.newRealBalance), ("real", 7500L))
  }

  test("adjust_test.go/TestCreditPrimary_ConvertedBonus_CreditsReal") {
    val converted = Record(id = "restricted-funds/1", status = Status.ConvertedPendingCap, balance = 0)
    val p         = Resolver.creditPrimary(Wallet(realBalance = 1000), Vector(converted), 900)
    assertEquals((p.target, p.newRealBalance), ("real", 1900L))
  }

  test("adjust_test.go/TestCreditPrimary_ActiveBonus_CreditsRecord") {
    val rec = activeRecord("restricted-funds/1", 500, 25000, 2000, 0, 0)
    val p   = Resolver.creditPrimary(Wallet(realBalance = 0, restrictedBalance = 500), Vector(rec), 9033)
    assertEquals((p.target, p.balanceBefore, p.balanceAfter), ("restricted", 500L, 9533L))
    val upd = findUpdate(p.recordUpdates, rec.id)
    assertEquals((upd.releasedProgress, upd.releaseRequirement), (2000L, 25000L))
  }

  test("adjust_test.go/TestCreditPrimary_TwoRecords_TargetsPrimaryOnly") {
    val a = priRecord("a", 1, "2026-01-01T00:00:00Z", 100, 0, 0, 0, 0)
    val b = priRecord("b", 2, "2026-01-01T00:00:00Z", 200, 0, 0, 0, 0)
    val p = Resolver.creditPrimary(Wallet(realBalance = 0, restrictedBalance = 300), Vector(a, b), 50)
    assertEquals((p.target, p.recordId, p.balanceAfter), ("restricted", "a", 150L))
    assertEquals(p.projection.restrictedBalance, 350L)
  }
