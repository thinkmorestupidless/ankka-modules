package io.eitheror.casino.wallet.funding

/** The original's `loss_test.go`, case for case. */
class LossTablesSuite extends munit.FunSuite:

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long,
      maxConvertible: Long, openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  private def findUpdate(updates: Vector[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  private def loss(w: Wallet, recs: Seq[Record], legs: Legs): LossPlan =
    Resolver.settleLoss(w, recs.toVector, legs).fold(d => fail(s"unexpected deny: $d"), identity)

  test("loss_test.go/TestSettleLoss_RealFundedNoBonusStateChange") {
    val plan = loss(Wallet(realBalance = 100), Nil, Legs(real = 10))
    assert(plan.forfeited.isEmpty && plan.recordUpdates.isEmpty, plan.toString)
    assert(!plan.projection.present, plan.projection.toString)
  }

  test("loss_test.go/TestSettleLoss_ForfeitsWhenBalanceZeroAndNoOpenRounds") {
    val rec  = activeRecord("restricted-funds/1", 0, 0, 0, 0, 1)
    val plan = loss(Wallet(realBalance = 100), Seq(rec), Legs(records = Vector(Leg(rec.id, 10))))
    assertEquals(plan.forfeited, Vector(rec.id))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.status, upd.closedNow, upd.openRoundCount), (Status.Forfeited, true, 0L))
    assertEquals(plan.projection, Projection(present = true, restrictedBalance = 0))
  }

  test("loss_test.go/TestSettleLoss_NoForfeitureWhenBalanceRemains") {
    val rec  = activeRecord("restricted-funds/1", 10, 0, 0, 0, 2)
    val plan = loss(Wallet(realBalance = 100), Seq(rec), Legs(records = Vector(Leg(rec.id, 10))))
    assert(plan.forfeited.isEmpty, plan.forfeited.toString)
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.status, upd.openRoundCount), (Status.Active, 1L))
    assertEquals(plan.projection.restrictedBalance, 10L)
  }

  test("loss_test.go/TestSettleLoss_WageringLifecycleForfeitureAllBonusSpent") {
    val rec  = activeRecord("restricted-funds/1", 0, 250, 0, 100, 1)
    val plan = loss(Wallet(realBalance = 200), Seq(rec), Legs(records = Vector(Leg(rec.id, 50))))
    assertEquals(plan.forfeited.size, 1)
    assertEquals(plan.projection, Projection(present = true, restrictedBalance = 0))
  }

  test("loss_test.go/TestSettleLoss_NoBalanceChange") {
    val rec  = activeRecord("restricted-funds/1", 40, 0, 0, 0, 1)
    val plan = loss(Wallet(realBalance = 100), Seq(rec), Legs(records = Vector(Leg(rec.id, 10))))
    assertEquals(findUpdate(plan.recordUpdates, rec.id).balance, 40L)
  }

  private val guardCases: Seq[(String, Long, Seq[Record], Boolean)] = Seq(
    ("stray balance, no record, denies", 50L, Nil, true),
    ("no balance, no record, proceeds", 0L, Nil, false),
    ("balance with a record, proceeds", 10L, Seq(activeRecord("restricted-funds/1", 10, 0, 0, 0, 0)), false)
  )

  for (name, restrictedBal, recs, wantDeny) <- guardCases do
    test(s"loss_test.go/TestSettleLoss_Guard/$name") {
      val w    = Wallet(realBalance = 100, restrictedBalance = restrictedBal)
      val legs = recs.headOption.fold(Legs(real = 10))(r => Legs(records = Vector(Leg(r.id, 1))))
      Resolver.settleLoss(w, recs.toVector, legs) match
        case Left(deny) =>
          assert(wantDeny, s"unexpected deny: $deny")
          assertEquals(deny.code, Codes.RestrictedFundsUnmigrated)
          assert(deny.message.nonEmpty)
        case Right(_) => assert(!wantDeny, s"want ${Codes.RestrictedFundsUnmigrated}")
    }

  test("loss_test.go/TestSettleLoss_OtherOpenRecordsUntouched") {
    val funding = activeRecord("restricted-funds/1", 0, 0, 0, 0, 1)
    val other   = activeRecord("restricted-funds/2", 15, 0, 0, 0, 3)
    val plan    = loss(Wallet(realBalance = 100), Seq(funding, other), Legs(records = Vector(Leg(funding.id, 10))))
    assertEquals(plan.recordUpdates.map(_.id), Vector(funding.id))
    assertEquals(plan.projection.restrictedBalance, 15L)
  }
