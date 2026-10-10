package io.eitheror.casino.wallet.funding

/**
 * The original's `internal/funding/placebet_test.go`, ported case for case (tasks.md T030). Each
 * test is named `placebet_test.go/<Go test>[/<sub-case>]`; expected values are the Go test's.
 *
 * Go's zero `Flags` has partial splits and bonus wagering off and a zero default rate; the Scala
 * defaults differ, so every case states its flags through `flags`, which mirrors Go's zero values.
 */
class PlaceBetTablesSuite extends munit.FunSuite:

  private val file = "placebet_test.go"

  /** placebet_test.go `activeRecord`. */
  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long, maxConvertible: Long,
      openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  /** Go's `Flags{...}` with every unnamed field at its zero value. */
  private def flags(defaultRate: Long = 0, partialSplits: Boolean = false, bonusWageringCounts: Boolean = false,
      allowNegative: Boolean = false): Flags =
    Flags(partialSplits = partialSplits, bonusWageringCounts = bonusWageringCounts, allowNegative = allowNegative, defaultRate = defaultRate)

  private def ok[A](result: Either[Deny, A]): A = result.fold(d => fail(s"unexpected deny: $d"), identity)

  private def findUpdate(updates: Seq[RecordUpdate], id: String): RecordUpdate =
    updates.find(_.id == id).getOrElse(fail(s"no RecordUpdate for $id in $updates"))

  private def assertDeny[A](result: Either[Deny, A], code: String)(using munit.Location): Deny =
    result match
      case Left(d)  => assertEquals(d.code, code); d
      case Right(p) => fail(s"plan = $p, want $code deny")

  test(s"$file/TestPlaceBet_RealFundedNoBonus") {
    val plan = ok(Resolver.placeBet(Wallet(realBalance = 100), Vector.empty, Stake(30), flags(defaultRate = 100)))
    assertEquals((plan.realLeg, plan.newRealBalance), (30L, 70L))
    assertEquals(plan.walletType, "real")
    assert(plan.recordLegs.isEmpty && plan.converting.isEmpty, plan.toString)
  }

  // TestPlaceBet_GuardOnUnmigratedBonusBalance / "stray balance, no record - deny"
  Seq(
    ("bonus fallback (test2)", Wallet(0, restrictedBalance = 50), Stake(20), flags(defaultRate = 100)),
    ("insufficient funds shape (test3)", Wallet(10, restrictedBalance = 5), Stake(30), flags(defaultRate = 100)),
    ("turnover gate bonus-funded (test9)", Wallet(0, restrictedBalance = 500), Stake(50), flags(defaultRate = 100)),
    ("partial split (test14)", Wallet(3, restrictedBalance = 10), Stake(5), flags(defaultRate = 100, partialSplits = true)),
    ("partial split insufficient (test15)", Wallet(3, restrictedBalance = 1), Stake(5), flags(defaultRate = 100, partialSplits = true)),
    ("real covers it, stray balance present (test1)", Wallet(100, restrictedBalance = 20), Stake(30), flags(defaultRate = 100)),
    ("allow_negative, stray balance present (test17)", Wallet(3, restrictedBalance = 1), Stake(5),
      flags(defaultRate = 100, partialSplits = true, allowNegative = true))
  ).foreach { (name, w, stake, f) =>
    test(s"$file/TestPlaceBet_GuardOnUnmigratedBonusBalance/stray balance, no record - deny/$name") {
      assertDeny(Resolver.placeBet(w, Vector.empty, stake, f), Codes.RestrictedFundsUnmigrated)
    }
  }

  test(s"$file/TestPlaceBet_InsufficientFunds") {
    val deny = assertDeny(Resolver.placeBet(Wallet(10), Vector.empty, Stake(30), flags(defaultRate = 100)), Codes.InsufficientFunds)
    assertEquals(deny.message, Messages.InsufficientFunds)
    assertEquals(deny.message, "Insufficient funds in real and bonus wallets")
  }

  test(s"$file/TestPlaceBet_AllowNegative_NoBonus") {
    val plan = ok(Resolver.placeBet(Wallet(0), Vector.empty, Stake(1), flags(defaultRate = 100, allowNegative = true)))
    assertEquals((plan.realLeg, plan.newRealBalance), (1L, -1L))
    assert(plan.contributions.isEmpty, plan.contributions.toString)
  }

  test(s"$file/TestPlaceBet_AllowNegativeFalse_StillDenies") {
    assertDeny(Resolver.placeBet(Wallet(0), Vector.empty, Stake(1), flags(defaultRate = 100)), Codes.InsufficientFunds)
  }

  test(s"$file/TestPlaceBet_WageringProgressRealFundedActiveBonus") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 0, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(100, restrictedBalance = 50), Vector(rec), Stake(10), flags(defaultRate = 100)))
    assertEquals((plan.realLeg, plan.newRealBalance), (10L, 90L))
    assertEquals(plan.contributions, Vector(Leg(rec.id, 10)))
    assert(plan.recordLegs.isEmpty, plan.recordLegs.toString)
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals(upd.openRoundCount, 0L)
    assertEquals(upd.releasedProgress, 10L)
    assertEquals(plan.projection.restrictedBalance, 50L)
  }

  test(s"$file/TestPlaceBet_ContributionRateFifty") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 0, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(100, restrictedBalance = 50), Vector(rec), Stake(10), flags(defaultRate = 50)))
    assertEquals(plan.contributions.map(_.amount), Vector(5L))
  }

  test(s"$file/TestPlaceBet_BonusFunded_WageringCountsEnabled") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 0, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(0, restrictedBalance = 50), Vector(rec), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.realLeg, 0L)
    assertEquals(plan.recordLegs.map(_.amount), Vector(10L))
    assertEquals(plan.contributions.map(_.amount), Vector(10L))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals((upd.balance, upd.openRoundCount, upd.releasedProgress), (40L, 1L, 10L))
    assertEquals(plan.projection.restrictedBalance, 40L)
  }

  test(s"$file/TestPlaceBet_BonusFunded_WageringCountsDisabled") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 0, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(0, restrictedBalance = 50), Vector(rec), Stake(10), flags(defaultRate = 100)))
    assert(plan.contributions.isEmpty, plan.contributions.toString)
    assertEquals(findUpdate(plan.recordUpdates, rec.id).releasedProgress, 0L)
  }

  test(s"$file/TestPlaceBet_ConversionRealFunded") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 40, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(100, restrictedBalance = 50), Vector(rec), Stake(20), flags(defaultRate = 100)))
    assertEquals(plan.newRealBalance, 130L)
    assertEquals(plan.converting, Vector(rec.id))
    val upd = findUpdate(plan.recordUpdates, rec.id)
    assertEquals(
      (upd.status, upd.balance, upd.releasedProgress, upd.releaseRequirement, upd.remainingAllowance, upd.maxConvertible),
      (Status.ConvertedPendingCap, 0L, 0L, 0L, 100L, 0L)
    )
    assertEquals(plan.projection, Projection(present = true, restrictedBalance = 0))
  }

  test(s"$file/TestPlaceBet_ConversionUncappedEvenWhenBalanceExceedsMaxWin") {
    val rec  = activeRecord("restricted-funds/1", 100, 50, 40, 60, 0)
    val plan = ok(Resolver.placeBet(Wallet(100, restrictedBalance = 100), Vector(rec), Stake(20), flags(defaultRate = 100)))
    assertEquals(plan.newRealBalance, 180L)
    assertEquals(findUpdate(plan.recordUpdates, rec.id).remainingAllowance, 60L)
  }

  test(s"$file/TestPlaceBet_ConversionBonusOnly") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 40, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(0, restrictedBalance = 50), Vector(rec), Stake(20), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.newRealBalance, 30L)
    assertEquals(plan.converting.size, 1)
    assertEquals(findUpdate(plan.recordUpdates, rec.id).openRoundCount, 1L)
    assertEquals((plan.balanceBefore, plan.balanceAfter), (50L, 30L))
  }

  test(s"$file/TestPlaceBet_DoesNotSpuriouslyConvertNonActiveRecords") {
    val rec  = Record(id = "restricted-funds/1", status = Status.ConvertedPendingCap)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(rec), Stake(10), flags(defaultRate = 100, allowNegative = true)))
    assert(plan.converting.isEmpty && plan.contributions.isEmpty, plan.toString)
  }

  test(s"$file/TestPlaceBet_SplitConverts_WageringCountsTrue") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 40, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(5, restrictedBalance = 50), Vector(rec), Stake(20),
      flags(defaultRate = 100, bonusWageringCounts = true, partialSplits = true)))
    assertEquals(plan.realLeg, 5L)
    assertEquals(plan.recordLegs.map(_.amount), Vector(15L))
    assertEquals(plan.contributions.map(_.amount), Vector(20L))
    assertEquals(plan.newRealBalance, 35L)
    assertEquals(plan.walletType, "split")
  }

  test(s"$file/TestPlaceBet_SplitConverts_WageringCountsFalse") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 45, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(5, restrictedBalance = 50), Vector(rec), Stake(20), flags(defaultRate = 100, partialSplits = true)))
    assertEquals(plan.contributions.map(_.amount), Vector(5L))
    assertEquals(plan.newRealBalance, 35L)
  }

  test(s"$file/TestPlaceBet_TurnoverColumnsNotComputedByResolver") {
    val w    = Wallet(500, depositTurnoverPending = 200)
    val plan = ok(Resolver.placeBet(w, Vector.empty, Stake(50), flags(defaultRate = 100)))
    var pending = w.depositTurnoverPending
    if plan.realLeg > 0 then pending -= math.min(plan.realLeg, pending)
    assertEquals(pending, 150L)
  }

  test(s"$file/TestPlaceBet_ZeroRecords_NoGuardWhenNoneOpenAndNoBalance") {
    val plan = ok(Resolver.placeBet(Wallet(100), Vector.empty, Stake(10), flags(defaultRate = 100)))
    assert(plan.recordUpdates.isEmpty, plan.recordUpdates.toString)
  }

  test(s"$file/TestPlaceBet_RestrictedFirst") {
    val rec  = activeRecord("restricted-funds/1", 20, 100, 0, 0, 0).copy(fundingOrder = "restricted_first")
    val plan = ok(Resolver.placeBet(Wallet(50), Vector(rec), Stake(10), flags(defaultRate = 100)))
    assertEquals((plan.realLeg, plan.newRealBalance), (0L, 50L))
    assertEquals(plan.recordLegs.map(_.amount), Vector(10L))
  }

  test(s"$file/TestPlaceBet_RestrictedFirst_PartialSplitThenReal") {
    val rec  = activeRecord("restricted-funds/1", 4, 100, 0, 0, 0).copy(fundingOrder = "restricted_first")
    val plan = ok(Resolver.placeBet(Wallet(50), Vector(rec), Stake(10), flags(defaultRate = 100, partialSplits = true)))
    assertEquals(plan.realLeg, 6L)
    assertEquals(plan.recordLegs.map(_.amount), Vector(4L))
  }

  test(s"$file/TestPlaceBet_Proportional") {
    val rec  = activeRecord("restricted-funds/1", 10, 100, 0, 0, 0).copy(fundingOrder = "proportional")
    val plan = ok(Resolver.placeBet(Wallet(30), Vector(rec), Stake(8), flags(defaultRate = 100)))
    assertEquals(plan.realLeg, 6L)
    assertEquals(plan.recordLegs.map(_.amount), Vector(2L))
    assertEquals(plan.newRealBalance, 24L)
  }

  test(s"$file/TestPlaceBet_Proportional_LargeAmountsDoNotOverflow") {
    val rec  = activeRecord("restricted-funds/1", 100000, 1000000, 0, 0, 0).copy(fundingOrder = "proportional")
    val plan = ok(Resolver.placeBet(Wallet(300000), Vector(rec), Stake(10000), flags(defaultRate = 100)))
    assertEquals(plan.realLeg, 7500L)
    assertEquals(plan.recordLegs.map(_.amount), Vector(2500L))
  }

  test(s"$file/TestPlaceBet_ContributionLargeStakeDoesNotOverflow") {
    val rec  = activeRecord("restricted-funds/1", 50, 100000000, 0, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(50000000, restrictedBalance = 50), Vector(rec), Stake(50000000), flags(defaultRate = 50)))
    assertEquals(plan.contributions.map(_.amount), Vector(25000000L))
  }

  test(s"$file/TestPlaceBet_Proportional_InsufficientCombinedPool") {
    val rec = activeRecord("restricted-funds/1", 2, 100, 0, 0, 0).copy(fundingOrder = "proportional")
    assertDeny(Resolver.placeBet(Wallet(3), Vector(rec), Stake(10), flags(defaultRate = 100)), Codes.InsufficientFunds)
  }

  test(s"$file/TestPlaceBet_Combined") {
    val rec  = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0).copy(fundingOrder = "combined")
    val plan = ok(Resolver.placeBet(Wallet(3), Vector(rec), Stake(10), flags(defaultRate = 100, partialSplits = true)))
    assertEquals(plan.realLeg, 3L)
    assertEquals(plan.recordLegs.map(_.amount), Vector(7L))
    assertEquals(plan.contributions.map(_.amount), Vector(10L))
  }

  test(s"$file/TestPlaceBet_Combined_ContributionIgnoresBonusWageringCountsFalse") {
    val rec  = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0).copy(fundingOrder = "combined")
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(rec), Stake(10), flags(defaultRate = 100)))
    assertEquals(plan.contributions.map(_.amount), Vector(10L))
  }

  test(s"$file/TestPlaceBet_AllOpen_ThirdRecordProgressesWithoutFunding") {
    val a    = activeRecord("a", 5, 100, 0, 0, 0).copy(priority = 1)
    val b    = activeRecord("b", 20, 100, 0, 0, 0).copy(priority = 2)
    val c    = activeRecord("c", 0, 100, 0, 0, 0).copy(priority = 3, releaseProgressScope = "all_open")
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b, c), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(findUpdate(plan.recordUpdates, "a").releasedProgress, 5L)
    assertEquals(findUpdate(plan.recordUpdates, "b").releasedProgress, 5L)
    val updC = findUpdate(plan.recordUpdates, "c")
    assertEquals(updC.releasedProgress, 10L)
    assertEquals(updC.openRoundCount, 0L)
  }

  test(s"$file/TestPlaceBet_AllOpen_GatedByBonusWageringCounts") {
    val rec   = activeRecord("restricted-funds/1", 20, 100, 0, 0, 0).copy(releaseProgressScope = "all_open")
    val other = activeRecord("restricted-funds/2", 0, 100, 0, 0, 0).copy(releaseProgressScope = "all_open")
    val plan  = ok(Resolver.placeBet(Wallet(3), Vector(rec, other), Stake(10), flags(defaultRate = 100, partialSplits = true)))
    assertEquals(findUpdate(plan.recordUpdates, rec.id).releasedProgress, 3L)
    assertEquals(findUpdate(plan.recordUpdates, other.id).releasedProgress, 3L)
  }

  test(s"$file/TestPlaceBet_FundingGrantVsAllOpen_TwoRecords") {
    val a    = activeRecord("a", 5, 100, 0, 0, 0).copy(priority = 1)
    val b    = activeRecord("b", 20, 100, 0, 0, 0).copy(priority = 2)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.recordLegs.map(_.amount), Vector(5L, 5L))
    val (updA, updB) = (findUpdate(plan.recordUpdates, "a"), findUpdate(plan.recordUpdates, "b"))
    assertEquals((updA.releasedProgress, updB.releasedProgress), (5L, 5L))
    assertEquals((updA.openRoundCount, updB.openRoundCount), (1L, 1L))
  }

  test(s"$file/TestPlaceBet_IncludeExcludeInterplay_TwoRecords") {
    // parse.go ParsePipeSet("category:table") is the one-element set built directly here.
    val inScope    = activeRecord("restricted-funds/1", 5, 100, 0, 0, 0).copy(priority = 1, scopeInclude = Set("category:table"))
    val unaffected = activeRecord("restricted-funds/2", 20, 100, 0, 0, 0).copy(priority = 2)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(inScope, unaffected), Stake(10, Game(category = "slots")),
      flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(findUpdate(plan.recordUpdates, inScope.id).releasedProgress, 0L)
    assertEquals(findUpdate(plan.recordUpdates, unaffected.id).releasedProgress, 5L)
  }
