package io.eitheror.casino.wallet.funding

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** The original's `creditwin_test.go`, case for case. */
class CreditWinTablesSuite extends munit.FunSuite with LogCapturing:

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long,
      maxConvertible: Long, openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def findUpdate(updates: Vector[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  private def win(w: Wallet, recs: Seq[Record], legs: Legs, amount: Long): WinPlan =
    Resolver.creditWin(w, recs.toVector, legs, amount).fold(d => fail(s"unexpected deny: $d"), identity)

  test("creditwin_test.go/TestCreditWin_RealFunded") {
    val plan = win(Wallet(realBalance = 100), Nil, Legs(real = 1), 30)
    assertEquals((plan.realShare, plan.newRealBalance), (30L, 130L))
    assertEquals(plan.walletType, "real")
    assert(plan.recordShares.isEmpty, plan.recordShares.toString)
  }

  test("creditwin_test.go/TestCreditWin_BonusFunded") {
    val rec  = activeRecord("restricted-funds/1", 20, 0, 0, 0, 0)
    val plan = win(Wallet(realBalance = 100), Seq(rec), Legs(records = Vector(Leg(rec.id, 1))), 15)
    assertEquals((plan.realShare, plan.newRealBalance), (0L, 100L))
    assertEquals(plan.recordShares.map(_.amount), Vector(15L))
    assertEquals(findUpdate(plan.recordUpdates, rec.id).balance, 35L)
    assertEquals(plan.projection.restrictedBalance, 35L)
  }

  test("creditwin_test.go/TestCreditWin_SplitFunded") {
    val rec  = activeRecord("restricted-funds/1", 20, 0, 0, 0, 0)
    val plan = win(Wallet(realBalance = 0), Seq(rec), Legs(real = 6, records = Vector(Leg(rec.id, 4))), 50)
    assertEquals((plan.realShare, plan.newRealBalance), (30L, 30L))
    assertEquals(plan.recordShares.map(_.amount), Vector(20L))
    assertEquals((plan.splitRealAmount, plan.splitBonusAmount), (30L, 20L))
    assertEquals(findUpdate(plan.recordUpdates, rec.id).balance, 40L)
    assertEquals(plan.walletType, "split")
  }

  test("creditwin_test.go/TestCreditWin_OpenRoundCountDecrementsOnWin") {
    val rec  = activeRecord("restricted-funds/1", 20, 0, 0, 0, 3)
    val plan = win(Wallet(realBalance = 0), Seq(rec), Legs(records = Vector(Leg(rec.id, 1))), 10)
    assertEquals(findUpdate(plan.recordUpdates, rec.id).openRoundCount, 2L)
  }

  test("creditwin_test.go/TestCreditWin_ShareToConvertedRecordRedirectsToReal") {
    val converted = Record(id = "restricted-funds/1", status = Status.ConvertedPendingCap, balance = 0)
    val plan      = win(Wallet(realBalance = 100), Seq(converted), Legs(records = Vector(Leg(converted.id, 1))), 15)
    assertEquals((plan.realShare, plan.newRealBalance), (15L, 115L))
    assert(plan.recordShares.isEmpty, plan.recordShares.toString)
    assertEquals(plan.orphanedToReal.map(_.recordId), Vector(converted.id))
    assertEquals(plan.projection.restrictedBalance, 0L)
  }

  test("creditwin_test.go/TestCreditWin_ShareToMissingRecordRedirectsToReal") {
    val plan = win(Wallet(realBalance = 100), Nil, Legs(records = Vector(Leg("restricted-funds/gone", 1))), 15)
    assertEquals(plan.realShare, 15L)
  }

  test("creditwin_test.go/TestCreditWin_AllRestrictedAllocation") {
    val rec  = activeRecord("restricted-funds/1", 0, 0, 0, 0, 0).copy(winAllocation = "all_restricted")
    val plan = win(Wallet(realBalance = 50), Seq(rec), Legs(real = 8, records = Vector(Leg(rec.id, 2))), 100)
    assertEquals((plan.realShare, plan.newRealBalance), (0L, 50L))
    assertEquals(plan.recordShares.map(_.amount), Vector(100L))
  }

  test("creditwin_test.go/TestCreditWin_AllRestrictedAllocation_HighestPriorityAmongTwo") {
    val a    = activeRecord("a", 0, 0, 0, 0, 0).copy(priority = 1, winAllocation = "all_restricted")
    val b    = activeRecord("b", 0, 0, 0, 0, 0).copy(priority = 2)
    val plan = win(Wallet(realBalance = 0), Seq(a, b), Legs(records = Vector(Leg(a.id, 5), Leg(b.id, 5))), 20)
    assertEquals(plan.realShare, 0L)
    assertEquals(plan.recordShares, Vector(Leg("a", 20)))
  }

  test("creditwin_test.go/TestCreditWin_AllRestrictedAllocation_HighestPriorityConverted_RedirectsToReal") {
    val a    = Record(id = "a", status = Status.ConvertedPendingCap, priority = 1, winAllocation = "all_restricted")
    val b    = activeRecord("b", 0, 0, 0, 0, 0).copy(priority = 2, winAllocation = "all_restricted")
    val plan = win(Wallet(realBalance = 0), Seq(a, b), Legs(records = Vector(Leg(a.id, 5), Leg(b.id, 5))), 20)
    assertEquals(plan.realShare, 20L)
    assert(plan.recordShares.isEmpty, plan.recordShares.toString)
  }

  test("creditwin_test.go/TestCreditWin_WinAllocation_ReadFromFirstActiveFundingRecord") {
    val converted = Record(id = "a", status = Status.ConvertedPendingCap, priority = 1, winAllocation = "all_restricted")
    val b         = activeRecord("b", 0, 0, 0, 0, 0).copy(priority = 2)
    val legs      = Legs(real = 5, records = Vector(Leg(converted.id, 5), Leg(b.id, 5)))
    val plan      = win(Wallet(realBalance = 5), Seq(converted, b), legs, 20)
    assertEquals(plan.realShare, 12L)
    assertEquals(plan.recordShares, Vector(Leg("b", 8)))
  }

  private val guardCases: Seq[(String, Long, Seq[Record], Boolean)] = Seq(
    ("stray balance, no record, denies", 50L, Nil, true),
    ("no balance, no record, proceeds", 0L, Nil, false),
    ("balance with a record, proceeds", 10L, Seq(activeRecord("restricted-funds/1", 10, 0, 0, 0, 0)), false)
  )

  for (name, restrictedBal, recs, wantDeny) <- guardCases do
    test(s"creditwin_test.go/TestCreditWin_Guard/$name") {
      val w    = Wallet(realBalance = 100, restrictedBalance = restrictedBal)
      val legs = recs.headOption.fold(Legs(real = 10))(r => Legs(records = Vector(Leg(r.id, 1))))
      Resolver.creditWin(w, recs.toVector, legs, 10) match
        case Left(deny) =>
          assert(wantDeny, s"unexpected deny: $deny")
          assertEquals(deny.code, Codes.RestrictedFundsUnmigrated)
          assert(deny.message.nonEmpty)
        case Right(_) => assert(!wantDeny, s"want ${Codes.RestrictedFundsUnmigrated}")
    }

  test("creditwin_test.go/TestCreditWin_FollowSourceRoundingRemainderGoesToLastRecord") {
    val r1   = activeRecord("restricted-funds/1", 0, 0, 0, 0, 0)
    val r2   = activeRecord("restricted-funds/2", 0, 0, 0, 0, 0)
    val plan = win(Wallet(realBalance = 0), Seq(r1, r2), Legs(records = Vector(Leg(r1.id, 1), Leg(r2.id, 2))), 10)
    assertEquals(plan.recordShares.map(_.amount), Vector(3L, 7L))
  }

  test("creditwin_test.go/TestCreditWin_LargeWinOnLargeStakeDoesNotOverflow") {
    val plan = win(Wallet(realBalance = 2397), Nil, Legs(real = 50000), 500000)
    assertEquals((plan.realShare, plan.newRealBalance), (500000L, 502397L))
  }

  test("creditwin_test.go/TestCreditWin_LargeSplitWinDoesNotOverflow") {
    val rec  = activeRecord("restricted-funds/1", 0, 0, 0, 0, 1)
    val plan = win(Wallet(realBalance = 0), Seq(rec), Legs(real = 30000, records = Vector(Leg(rec.id, 20000))), 500000)
    assertEquals(plan.realShare, 300000L)
    assertEquals(plan.recordShares.map(_.amount), Vector(200000L))
  }
