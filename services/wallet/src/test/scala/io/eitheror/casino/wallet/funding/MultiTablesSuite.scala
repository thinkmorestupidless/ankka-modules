package io.eitheror.casino.wallet.funding

/**
 * The original's `internal/funding/multi_test.go`, ported case for case (tasks.md T030): the
 * resolver proven over N ∈ {2, 3} open records. Each test is named `multi_test.go/<Go test>[/<case>]`;
 * expected values are the Go test's. Flags mirror Go's zero values through `flags`.
 */
class MultiTablesSuite extends munit.FunSuite:

  private val file = "multi_test.go"

  /** multi_test.go `priRecord`: an ACTIVE record with explicit priority and granted time. */
  private def priRecord(id: String, priority: Long, grantedAt: String, balance: Long, releaseRequirement: Long,
      releasedProgress: Long, maxConvertible: Long, openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, priority = priority, grantedAt = grantedAt, balance = balance,
      releaseRequirement = releaseRequirement, releasedProgress = releasedProgress, maxConvertible = maxConvertible,
      openRoundCount = openRoundCount)

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

  private val day1 = "2026-01-01T00:00:00Z"

  // ── draw-down order (US3 #1, #4) ──────────────────────────────────────────

  Seq(
    ("priority asc, N=2 (US3 #1)",
      Vector(priRecord("a", 1, day1, 5, 100, 0, 0, 0), priRecord("b", 2, day1, 20, 100, 0, 0, 0)),
      Vector(Leg("a", 5), Leg("b", 5)), Map("a" -> 1L, "b" -> 1L), Map("a" -> 5L, "b" -> 5L)),
    ("priority asc, N=3",
      Vector(priRecord("a", 1, day1, 3, 100, 0, 0, 0), priRecord("b", 2, day1, 4, 100, 0, 0, 0), priRecord("c", 3, day1, 20, 100, 0, 0, 0)),
      Vector(Leg("a", 3), Leg("b", 4), Leg("c", 3)), Map("a" -> 1L, "b" -> 1L, "c" -> 1L), Map("a" -> 3L, "b" -> 4L, "c" -> 3L)),
    ("equal priority, earliest granted_at first, N=2 (US3 #4)",
      Vector(priRecord("a", 1, day1, 5, 100, 0, 0, 0), priRecord("b", 1, "2026-01-02T00:00:00Z", 20, 100, 0, 0, 0)),
      Vector(Leg("a", 5), Leg("b", 5)), Map("a" -> 1L, "b" -> 1L), Map("a" -> 5L, "b" -> 5L)),
    ("equal priority, earliest granted_at first, N=3",
      Vector(priRecord("a", 1, day1, 2, 100, 0, 0, 0), priRecord("b", 1, "2026-01-02T00:00:00Z", 2, 100, 0, 0, 0),
        priRecord("c", 1, "2026-01-03T00:00:00Z", 20, 100, 0, 0, 0)),
      Vector(Leg("a", 2), Leg("b", 2), Leg("c", 1)), Map("a" -> 1L, "b" -> 1L, "c" -> 1L), Map("a" -> 2L, "b" -> 2L, "c" -> 1L))
  ).foreach { (name, recs, wantLegs, wantOpen, wantWagered) =>
    test(s"$file/TestMulti_PlaceBet_DrawDownOrder/$name") {
      val amount = wantLegs.map(_.amount).sum
      val plan   = ok(Resolver.placeBet(Wallet(0), recs, Stake(amount), flags(defaultRate = 100, bonusWageringCounts = true)))
      assertEquals(plan.realLeg, 0L)
      assertEquals(plan.recordLegs, wantLegs)
      wantOpen.foreach { (id, open) =>
        val upd = findUpdate(plan.recordUpdates, id)
        assertEquals(upd.openRoundCount, open, id)
        assertEquals(upd.releasedProgress, wantWagered(id), id)
        assertEquals(upd.status, Status.Active, id)
      }
      assertEquals(plan.projection.restrictedBalance, recs.map(_.balance).sum - amount)
    }
  }

  test(s"$file/TestMulti_PlaceBet_DrawDownOrder_IsCallerSupplied_NotRecomputed") {
    val a    = priRecord("a", 1, day1, 5, 100, 0, 0, 0)
    val b    = priRecord("b", 1, "2026-01-02T00:00:00Z", 20, 100, 0, 0, 0)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(b, a), Stake(10), flags(defaultRate = 100)))
    assertEquals(plan.recordLegs, Vector(Leg("b", 10)))
  }

  // ── funding_grant vs all_open (US3 #2) ────────────────────────────────────

  test(s"$file/TestMulti_PlaceBet_FundingGrantVsAllOpen_ThirdRecord") {
    val a    = priRecord("a", 1, day1, 5, 100, 0, 0, 0)
    val b    = priRecord("b", 2, day1, 20, 100, 0, 0, 0)
    val c    = priRecord("c", 3, day1, 0, 100, 0, 0, 0).copy(releaseProgressScope = "all_open")
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b, c), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.recordLegs.map(_.recordId), Vector("a", "b"))
    val (updA, updB, updC) = (findUpdate(plan.recordUpdates, "a"), findUpdate(plan.recordUpdates, "b"), findUpdate(plan.recordUpdates, "c"))
    assertEquals((updA.releasedProgress, updA.openRoundCount), (5L, 1L))
    assertEquals((updB.releasedProgress, updB.openRoundCount), (5L, 1L))
    assertEquals((updC.releasedProgress, updC.openRoundCount, updC.balance), (10L, 0L, 0L))
    assertEquals(plan.projection.restrictedBalance, 15L)
  }

  // ── most restrictive max_stake (US3 #3) ───────────────────────────────────

  test(s"$file/TestMulti_PlaceBet_MostRestrictiveMaxStake/block on the lower limit binds (US3 #3)") {
    val a = priRecord("a", 1, day1, 100, 100, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "block")
    val b = priRecord("b", 2, day1, 100, 100, 0, 0, 0).copy(maxStake = Some(50), maxStakeEnforcement = "cap")
    assertDeny(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(defaultRate = 100)), Codes.MaxStakeExceeded)
  }

  test(s"$file/TestMulti_PlaceBet_MostRestrictiveMaxStake/tie at the minimum: block wins over cap") {
    val a = priRecord("a", 1, day1, 100, 100, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "cap")
    val b = priRecord("b", 2, day1, 100, 100, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "block")
    assertDeny(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(defaultRate = 100)), Codes.MaxStakeExceeded)
  }

  test(s"$file/TestMulti_PlaceBet_MostRestrictiveMaxStake/cap binds when it supplies the minimum, even with a higher block elsewhere") {
    val a    = priRecord("a", 1, day1, 100, 1000, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "cap")
    val b    = priRecord("b", 2, day1, 100, 1000, 0, 0, 0).copy(maxStake = Some(20), maxStakeEnforcement = "block")
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.recordLegs, Vector(Leg("a", 10)))
    assertEquals(findUpdate(plan.recordUpdates, "a").releasedProgress, 5L)
  }

  test(s"$file/TestMulti_PlaceBet_MostRestrictiveMaxStake/does not bind: stake within every limit, no cap and no deny") {
    val a    = priRecord("a", 1, day1, 100, 1000, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "cap")
    val b    = priRecord("b", 2, day1, 100, 1000, 0, 0, 0).copy(maxStake = Some(50), maxStakeEnforcement = "block")
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(3), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(findUpdate(plan.recordUpdates, "a").releasedProgress, 3L)
  }

  // ── scope on one record only ──────────────────────────────────────────────

  test(s"$file/TestMulti_PlaceBet_ScopeViolationOnOneRecordOnly") {
    val a    = priRecord("a", 1, day1, 5, 1000, 0, 0, 0).copy(scopeExclude = Set("provider:P"))
    val b    = priRecord("b", 2, day1, 20, 1000, 0, 0, 0)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10, Game(provider = "P")), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.recordLegs.map(_.amount), Vector(5L, 5L))
    val updA = findUpdate(plan.recordUpdates, "a")
    assertEquals((updA.releasedProgress, updA.balance), (0L, 0L))
    assertEquals(findUpdate(plan.recordUpdates, "b").releasedProgress, 5L)
  }

  test(s"$file/TestMulti_PlaceBet_ScopeBlockOnOneRecord_RefusesEntireBet") {
    val a = priRecord("a", 1, day1, 5, 1000, 0, 0, 0).copy(scopeExclude = Set("provider:P"), scopeViolationMode = "block")
    val b = priRecord("b", 2, day1, 20, 1000, 0, 0, 0)
    assertDeny(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10, Game(provider = "P")), flags(defaultRate = 100)), Codes.ScopeBlocked)
  }

  // ── conversion of a non-primary record ────────────────────────────────────

  test(s"$file/TestMulti_PlaceBet_ConversionOfNonPrimaryRecord/non-primary converts, primary does not") {
    val a    = priRecord("a", 1, day1, 5, 100, 0, 0, 0)
    val b    = priRecord("b", 2, day1, 20, 50, 45, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.converting, Vector("b"))
    val updA = findUpdate(plan.recordUpdates, "a")
    assertEquals((updA.status, updA.releasedProgress), (Status.Active, 5L))
    val updB = findUpdate(plan.recordUpdates, "b")
    assertEquals(
      (updB.status, updB.balance, updB.releasedProgress, updB.releaseRequirement, updB.remainingAllowance),
      (Status.ConvertedPendingCap, 0L, 0L, 0L, 100L)
    )
    assertEquals(plan.newRealBalance, 15L)
  }

  test(s"$file/TestMulti_PlaceBet_ConversionOfNonPrimaryRecord/primary and non-primary both convert on the same bet") {
    val a    = priRecord("a", 1, day1, 3, 3, 0, 40, 0)
    val b    = priRecord("b", 2, day1, 20, 50, 45, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.converting, Vector("a", "b"))
    assertEquals(plan.newRealBalance, 13L)
  }

  test(s"$file/TestMulti_PlaceBet_ConversionOfNonPrimaryRecord/conversion proceeds while a sibling's scope violation only zeroes its own contribution") {
    val a    = priRecord("a", 1, day1, 5, 5, 0, 40, 0)
    val b    = priRecord("b", 2, day1, 20, 1000, 0, 0, 0).copy(scopeExclude = Set("provider:P"))
    val plan = ok(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10, Game(provider = "P")), flags(defaultRate = 100, bonusWageringCounts = true)))
    assertEquals(plan.converting, Vector("a"))
    val updB = findUpdate(plan.recordUpdates, "b")
    assertEquals((updB.status, updB.releasedProgress, updB.balance), (Status.Active, 0L, 15L))
  }

  // ── win across two funding legs (US3 #5) ──────────────────────────────────

  private val twoLegs = Legs(records = Vector(Leg("a", 5), Leg("b", 5)))

  test(s"$file/TestMulti_CreditWin_TwoFundingLegs/follow_source: proportional to each record's leg (US3 #5)") {
    val a    = priRecord("a", 1, day1, 0, 0, 0, 0, 1)
    val b    = priRecord("b", 2, day1, 0, 0, 0, 0, 1)
    val plan = ok(Resolver.creditWin(Wallet(0), Vector(a, b), twoLegs, 20))
    assertEquals(plan.realShare, 0L)
    assertEquals(plan.recordShares, Vector(Leg("a", 10), Leg("b", 10)))
    val (updA, updB) = (findUpdate(plan.recordUpdates, "a"), findUpdate(plan.recordUpdates, "b"))
    assertEquals((updA.balance, updA.openRoundCount), (10L, 0L))
    assertEquals((updB.balance, updB.openRoundCount), (10L, 0L))
    assertEquals(plan.projection.restrictedBalance, 20L)
  }

  test(s"$file/TestMulti_CreditWin_TwoFundingLegs/all_restricted: the whole win to the highest-priority funding record") {
    val a    = priRecord("a", 1, day1, 0, 0, 0, 0, 1).copy(winAllocation = "all_restricted")
    val b    = priRecord("b", 2, day1, 0, 0, 0, 0, 1)
    val plan = ok(Resolver.creditWin(Wallet(0), Vector(a, b), twoLegs, 20))
    assertEquals(plan.realShare, 0L)
    assertEquals(plan.recordShares, Vector(Leg("a", 20)))
    val (updA, updB) = (findUpdate(plan.recordUpdates, "a"), findUpdate(plan.recordUpdates, "b"))
    assertEquals(updA.balance, 20L)
    assertEquals(updB.balance, 0L)
    assertEquals((updA.openRoundCount, updB.openRoundCount), (0L, 0L))
  }

  // ── rollback and loss across two records ──────────────────────────────────

  test(s"$file/TestMulti_RollbackBet_TwoRecords") {
    val a    = priRecord("a", 1, day1, 0, 100, 5, 0, 1)
    val b    = priRecord("b", 2, day1, 0, 100, 2, 0, 1)
    val plan = ok(Resolver.rollbackBet(Wallet(0), Vector(a, b), twoLegs, Vector(Leg("a", 5), Leg("b", 3))))
    assertEquals((plan.realRestored, plan.newRealBalance), (0L, 0L))
    assertEquals(plan.recordRestored, Vector(Leg("a", 5), Leg("b", 5)))
    assertEquals(plan.progressReversed, Vector(Leg("a", 5), Leg("b", 3)))
    for id <- Seq("a", "b") do
      val upd = findUpdate(plan.recordUpdates, id)
      assertEquals((upd.balance, upd.openRoundCount, upd.releasedProgress, upd.status), (5L, 0L, 0L, Status.Active), id)
    assertEquals(plan.projection.restrictedBalance, 10L)
  }

  test(s"$file/TestMulti_SettleLoss_TwoRecords") {
    val a    = priRecord("a", 1, day1, 0, 0, 0, 0, 1)
    val b    = priRecord("b", 2, day1, 15, 0, 0, 0, 1)
    val plan = ok(Resolver.settleLoss(Wallet(0), Vector(a, b), twoLegs))
    assertEquals(plan.forfeited, Vector("a"))
    val (updA, updB) = (findUpdate(plan.recordUpdates, "a"), findUpdate(plan.recordUpdates, "b"))
    assertEquals((updA.status, updA.closedNow, updA.openRoundCount, updA.balance), (Status.Forfeited, true, 0L, 0L))
    assertEquals((updB.status, updB.closedNow, updB.openRoundCount, updB.balance), (Status.Active, false, 0L, 15L))
    assertEquals(plan.projection, Projection(present = true, restrictedBalance = 15))
  }

  // ── Project over two open records ─────────────────────────────────────────

  test(s"$file/TestMulti_Project_TwoOpenRecords/both ACTIVE: sum balances") {
    val a = priRecord("a", 1, day1, 30, 100, 10, 0, 2)
    val b = priRecord("b", 2, day1, 45, 200, 20, 0, 3)
    assertEquals(Resolver.project(Vector(a, b), ""), Projection(present = true, restrictedBalance = 75))
  }

  test(s"$file/TestMulti_Project_TwoOpenRecords/one ACTIVE, one CONVERTED_PENDING_CAP: both open, sum balances") {
    val converted = Record(id = "a", status = Status.ConvertedPendingCap, releasedProgress = 999, openRoundCount = 7)
    val b         = priRecord("b", 2, day1, 45, 200, 20, 0, 3)
    assertEquals(Resolver.project(Vector(converted, b), ""), Projection(present = true, restrictedBalance = 45))
  }

  test(s"$file/TestMulti_Project_TwoOpenRecords/no open record, no closure this call: Present false, nothing computed") {
    val forfeited = Record(id = "a", status = Status.Forfeited, balance = 20)
    assertEquals(Resolver.project(Vector(forfeited), ""), Projection())
  }

  test(s"$file/TestMulti_Project_TwoOpenRecords/last open record forfeited this call: Present true, balance written") {
    val forfeited = Record(id = "a", status = Status.Forfeited)
    assertEquals(Resolver.project(Vector(forfeited), Status.Forfeited), Projection(present = true, restrictedBalance = 0))
  }

  test(s"$file/TestMulti_Project_TwoOpenRecords/one open record: Present true, its own balance written") {
    val a = priRecord("a", 1, day1, 30, 100, 10, 0, 2)
    assertEquals(Resolver.project(Vector(a), ""), Projection(present = true, restrictedBalance = 30))
  }

  // ── proportional across two records ───────────────────────────────────────

  test(s"$file/TestMulti_PlaceBet_ProportionalOrder_TwoRecords") {
    val a    = priRecord("a", 1, day1, 3, 1000, 0, 0, 0).copy(fundingOrder = "proportional")
    val b    = priRecord("b", 2, day1, 7, 1000, 0, 0, 0)
    val plan = ok(Resolver.placeBet(Wallet(15), Vector(a, b), Stake(10), flags(defaultRate = 100)))
    assertEquals(plan.realLeg, 6L)
    assertEquals(plan.recordLegs, Vector(Leg("a", 3), Leg("b", 1)))
    assertEquals(plan.realLeg + plan.recordLegs.map(_.amount).sum, 10L)
  }

  // ── every deny code ───────────────────────────────────────────────────────

  Seq(
    ("INSUFFICIENT_FUNDS, N=2",
      Vector(priRecord("a", 1, day1, 2, 100, 0, 0, 0), priRecord("b", 2, day1, 3, 100, 0, 0, 0)),
      Wallet(0), Stake(10), Codes.InsufficientFunds),
    ("MAX_STAKE_EXCEEDED, N=2",
      Vector(priRecord("a", 1, day1, 100, 100, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "block"),
        priRecord("b", 2, day1, 100, 100, 0, 0, 0)),
      Wallet(0), Stake(10), Codes.MaxStakeExceeded),
    ("SCOPE_BLOCKED, N=2",
      Vector(priRecord("a", 1, day1, 100, 100, 0, 0, 0).copy(scopeExclude = Set("provider:P"), scopeViolationMode = "block"),
        priRecord("b", 2, day1, 100, 100, 0, 0, 0)),
      Wallet(0), Stake(10, Game(provider = "P")), Codes.ScopeBlocked),
    ("RESTRICTED_FUNDS_UNMIGRATED, N=0 (the guard's only trigger shape)",
      Vector.empty[Record], Wallet(0, restrictedBalance = 50), Stake(10), Codes.RestrictedFundsUnmigrated)
  ).foreach { (name, recs, w, stake, want) =>
    test(s"$file/TestMulti_PlaceBet_EveryDenyCode/$name") {
      assertDeny(Resolver.placeBet(w, recs, stake, flags(defaultRate = 100)), want)
    }
  }
