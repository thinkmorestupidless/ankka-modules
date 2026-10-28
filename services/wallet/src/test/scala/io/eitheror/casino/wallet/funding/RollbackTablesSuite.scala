package io.eitheror.casino.wallet.funding

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** The original's `rollback_test.go`, case for case. */
class RollbackTablesSuite extends munit.FunSuite with LogCapturing:

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long,
      maxConvertible: Long, openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def findUpdate(updates: Vector[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  private def rollback(w: Wallet, recs: Seq[Record], legs: Legs, contribs: Vector[Leg] = Vector.empty): RollbackPlan =
    Resolver.rollbackBet(w, recs.toVector, legs, contribs).fold(d => fail(s"unexpected deny: $d"), identity)

  test("rollback_test.go/TestRollbackBet_RealFunded") {
    val plan = rollback(Wallet(realBalance = 140), Nil, Legs(real = 10))
    assertEquals((plan.realRestored, plan.newRealBalance), (10L, 150L))
    assertEquals(plan.walletType, "real")
  }

  test("rollback_test.go/TestRollbackBet_BonusFunded") {
    val rec  = activeRecord("restricted-funds/1", 30, 0, 0, 0, 1)
    val plan = rollback(Wallet(realBalance = 100), Seq(rec), Legs(records = Vector(Leg(rec.id, 10))))
    assertEquals((plan.realRestored, plan.newRealBalance), (0L, 100L))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.openRoundCount), (40L, 0L))
    assertEquals(plan.projection.restrictedBalance, 40L)
  }

  test("rollback_test.go/TestRollbackBet_RealFundedActiveWageringReversal") {
    val rec  = activeRecord("restricted-funds/1", 50, 250, 50, 100, 0)
    val plan = rollback(Wallet(realBalance = 100), Seq(rec), Legs(real = 50), Vector(Leg(rec.id, 50)))
    assertEquals(plan.newRealBalance, 150L)
    assertEquals(findUpdate(plan.recordUpdates, rec.id).releasedProgress, 0L)
  }

  test("rollback_test.go/TestRollbackBet_ContributionWeightedReversal") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 5, 100, 0)
    val plan = rollback(Wallet(realBalance = 90), Seq(rec), Legs(real = 10), Vector(Leg(rec.id, 5)))
    assertEquals(plan.newRealBalance, 100L)
    assertEquals(findUpdate(plan.recordUpdates, rec.id).releasedProgress, 0L)
  }

  test("rollback_test.go/TestRollbackBet_SplitBothBalancesRestored") {
    val rec  = activeRecord("restricted-funds/1", 8, 0, 0, 0, 1)
    val plan = rollback(Wallet(realBalance = 0), Seq(rec), Legs(real = 3, records = Vector(Leg(rec.id, 2))))
    assertEquals(plan.newRealBalance, 3L)
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.openRoundCount), (10L, 0L))
    assertEquals(plan.walletType, "split")
  }

  test("rollback_test.go/TestRollbackBet_SplitWithActiveBonusProgressReversal") {
    val rec  = activeRecord("restricted-funds/1", 8, 50, 5, 100, 1)
    val plan = rollback(Wallet(realBalance = 0), Seq(rec), Legs(real = 3, records = Vector(Leg(rec.id, 2))), Vector(Leg(rec.id, 5)))
    val upd  = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.openRoundCount, upd.releasedProgress), (10L, 0L, 0L))
  }

  test("rollback_test.go/TestRollbackBet_ShareToConvertedRecordRedirectsToReal") {
    val converted = Record(id = "restricted-funds/1", status = Status.ConvertedPendingCap, balance = 0)
    val plan      = rollback(Wallet(realBalance = 100), Seq(converted), Legs(records = Vector(Leg(converted.id, 10))))
    assertEquals((plan.realRestored, plan.newRealBalance), (10L, 110L))
    assert(plan.recordRestored.isEmpty, plan.recordRestored.toString)
  }

  private val guardCases: Seq[(String, Long, Seq[Record], Boolean)] = Seq(
    ("stray balance, no record, denies", 50L, Nil, true),
    ("no balance, no record, proceeds", 0L, Nil, false),
    ("balance with a record, proceeds", 10L, Seq(activeRecord("restricted-funds/1", 10, 0, 0, 0, 0)), false)
  )

  for (name, restrictedBal, recs, wantDeny) <- guardCases do
    test(s"rollback_test.go/TestRollbackBet_Guard/$name") {
      val w    = Wallet(realBalance = 100, restrictedBalance = restrictedBal)
      val legs = recs.headOption.fold(Legs(real = 10))(r => Legs(records = Vector(Leg(r.id, 1))))
      Resolver.rollbackBet(w, recs.toVector, legs, Vector.empty) match
        case Left(deny) =>
          assert(wantDeny, s"unexpected deny: $deny")
          assertEquals(deny.code, Codes.RestrictedFundsUnmigrated)
          assert(deny.message.nonEmpty)
        case Right(_) => assert(!wantDeny, s"want ${Codes.RestrictedFundsUnmigrated}")
    }

  test("rollback_test.go/TestRollbackBet_OpenRoundCountNeverGoesNegative") {
    val rec  = activeRecord("restricted-funds/1", 0, 0, 0, 0, 0)
    val plan = rollback(Wallet(realBalance = 0), Seq(rec), Legs(records = Vector(Leg(rec.id, 5))))
    assertEquals(findUpdate(plan.recordUpdates, rec.id).openRoundCount, 0L)
  }
