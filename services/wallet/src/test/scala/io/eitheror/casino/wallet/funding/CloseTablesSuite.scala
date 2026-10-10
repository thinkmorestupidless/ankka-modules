package io.eitheror.casino.wallet.funding

/**
 * The original's `internal/funding/close_test.go`, ported case for case (tasks.md T030):
 * ForfeitRecord, CancelRecord (with the clawback given, as the Go guest took it), ClosePendingCap
 * and AwardCheck. Each test is named `close_test.go/<Go test>`; expected values are the Go test's.
 * None names a grant reference: the Go guests had none (spec 002 adds it).
 */
class CloseTablesSuite extends munit.FunSuite:

  private val file = "close_test.go"
  private val day1 = "2026-01-01T00:00:00Z"

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long, maxConvertible: Long,
      openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def priRecord(id: String, priority: Long, grantedAt: String, balance: Long): Record =
    activeRecord(id, balance, 0, 0, 0, 0).copy(priority = priority, grantedAt = grantedAt)

  private def ok[A](result: Either[Deny, A]): A = result.fold(d => fail(s"unexpected deny: $d"), identity)

  private def findUpdate(updates: Seq[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  // ── ForfeitRecord ─────────────────────────────────────────────────────────

  test(s"$file/TestForfeitRecord_ActiveBonus_ClearsBalanceAndForfeits") {
    val rec  = activeRecord("restricted-funds/1", 1500, 7500, 2000, 5000, 0)
    val plan = ok(Resolver.forfeitRecord(Wallet(5000, restrictedBalance = 1500), Vector(rec)))
    assertEquals(plan.forfeitedAmount, 1500L)
    assertEquals(plan.closedRecords, Vector(rec.id))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.status, upd.balance, upd.closedNow), (Status.Forfeited, 0L, true))
    assertEquals(plan.projection, Projection(present = true, restrictedBalance = 0))
  }

  test(s"$file/TestForfeitRecord_NoActiveBonus_Refuses") {
    assertEquals(Resolver.forfeitRecord(Wallet(100), Vector.empty), Left(Deny(Codes.NoActiveBonus, "No active bonus to forfeit")))
  }

  test(s"$file/TestForfeitRecord_TwoRecords_OnlyPrimaryForfeits") {
    val plan = ok(Resolver.forfeitRecord(Wallet(0, restrictedBalance = 40), Vector(priRecord("a", 1, day1, 15), priRecord("b", 2, day1, 25))))
    assertEquals(plan.closedRecords, Vector("a"))
    assertEquals(plan.forfeitedAmount, 15L)
    assertEquals(plan.projection.restrictedBalance, 25L)
  }

  // ── CancelRecord ──────────────────────────────────────────────────────────

  test(s"$file/TestCancelRecord_NoClawback") {
    val rec  = activeRecord("restricted-funds/1", 20, 100, 40, 0, 0)
    val plan = ok(Resolver.cancelRecord(Wallet(100, restrictedBalance = 20), Vector(rec), 0))
    assertEquals((plan.bonusBalanceBefore, plan.clawbackApplied, plan.newRealBalance), (20L, 0L, 100L))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.status, upd.balance), (Status.Forfeited, 0L))
  }

  test(s"$file/TestCancelRecord_ClawbackLessThanReal") {
    val rec  = activeRecord("restricted-funds/2", 20, 100, 40, 0, 0)
    val plan = ok(Resolver.cancelRecord(Wallet(100, restrictedBalance = 20), Vector(rec), 15))
    assertEquals((plan.clawbackApplied, plan.newRealBalance), (15L, 85L))
  }

  test(s"$file/TestCancelRecord_ClawbackFlooredToRealBalance") {
    val rec  = activeRecord("restricted-funds/3", 50, 250, 0, 0, 0)
    val plan = ok(Resolver.cancelRecord(Wallet(10, restrictedBalance = 50), Vector(rec), 50))
    assertEquals((plan.clawbackApplied, plan.newRealBalance), (10L, 0L))
  }

  test(s"$file/TestCancelRecord_NoOpenRecord_LenientNoOp") {
    val plan = ok(Resolver.cancelRecord(Wallet(40), Vector.empty, 15))
    assert(plan.closedRecords.isEmpty && plan.recordUpdates.isEmpty, plan.toString)
    assertEquals((plan.bonusBalanceBefore, plan.clawbackApplied, plan.newRealBalance), (0L, 15L, 25L))
  }

  test(s"$file/TestCancelRecord_TwoRecords_OnlyPrimaryClosesBalanceBeforeSumsBoth") {
    val plan = ok(Resolver.cancelRecord(Wallet(100, restrictedBalance = 75), Vector(priRecord("a", 1, day1, 30), priRecord("b", 2, day1, 45)), 0))
    assertEquals(plan.bonusBalanceBefore, 75L)
    assertEquals(plan.closedRecords, Vector("a"))
    assertEquals(plan.projection.restrictedBalance, 45L)
  }

  // ── ClosePendingCap ───────────────────────────────────────────────────────

  test(s"$file/TestClosePendingCap_ConvertedPendingCap_Closes") {
    val rec  = Record(id = "restricted-funds/1", status = Status.ConvertedPendingCap, remainingAllowance = 100, depositsSinceRelease = 200)
    val plan = ok(Resolver.closePendingCap(Wallet(350), Vector(rec)))
    assertEquals(plan.closedRecords, Vector(rec.id))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.status, upd.balance), (Status.Closed, 0L))
  }

  test(s"$file/TestClosePendingCap_NonePending_Refuses") {
    assertEquals(Resolver.closePendingCap(Wallet(100), Vector.empty), Left(Deny(Codes.NotPendingCap, "No CONVERTED_PENDING_CAP bonus to close")))
  }

  test(s"$file/TestClosePendingCap_ActiveRecordAlone_Refuses") {
    val result = Resolver.closePendingCap(Wallet(100, restrictedBalance = 10), Vector(activeRecord("restricted-funds/1", 10, 0, 0, 0, 0)))
    assertEquals(result.left.map(_.code), Left(Codes.NotPendingCap))
  }

  test(s"$file/TestClosePendingCap_TwoRecords_SkipsActiveFindsPendingCap") {
    val active  = priRecord("a", 1, day1, 10)
    val pending = Record(id = "b", status = Status.ConvertedPendingCap, priority = 2)
    val plan    = ok(Resolver.closePendingCap(Wallet(0, restrictedBalance = 10), Vector(active, pending)))
    assertEquals(plan.closedRecords, Vector("b"))
    assertEquals(plan.projection.restrictedBalance, 10L)
  }

  // ── AwardCheck ────────────────────────────────────────────────────────────

  test(s"$file/TestAwardCheck_RecordActiveAndPresent_OK") {
    val rec = activeRecord("restricted-funds/1", 10, 50, 0, 0, 0)
    assertEquals(Resolver.awardCheck(Vector(rec), rec.id), None)
  }

  test(s"$file/TestAwardCheck_RecordMissing_Refuses") {
    assertEquals(Resolver.awardCheck(Vector.empty, "restricted-funds/gone").map(_.code), Some(Codes.RestrictedFundsUnavailable))
  }

  test(s"$file/TestAwardCheck_RecordPresentButNotActive_Refuses") {
    val closed = Record(id = "restricted-funds/1", status = Status.Forfeited)
    assertEquals(Resolver.awardCheck(Vector(closed), closed.id).map(_.code), Some(Codes.RestrictedFundsUnavailable))
  }
