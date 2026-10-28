package io.eitheror.casino.wallet.funding

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The original's `internal/funding/rules_test.go`, ported case for case (tasks.md T030): the rate
 * lookup chain, scope membership, the binding max stake, and the rule-set scenarios through PlaceBet.
 * Each test is named `rules_test.go/<Go test>`; expected values are the Go test's.
 *
 * Go's `Record{}` has an empty status; `Rate` and `InScope` never read it, so those cases build a
 * record with status "" to stay literal.
 */
class RulesTablesSuite extends munit.FunSuite with LogCapturing:

  private val file = "rules_test.go"

  private def activeRecord(id: String, balance: Long, releaseRequirement: Long, releasedProgress: Long, maxConvertible: Long,
      openRoundCount: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress, maxConvertible = maxConvertible, openRoundCount = openRoundCount)

  /** Go's zero `Record{}`. */
  private def zero: Record = Record(id = "", status = "")

  private def flags(defaultRate: Long = 0): Flags =
    Flags(partialSplits = false, bonusWageringCounts = false, allowNegative = false, defaultRate = defaultRate)

  private def ok[A](result: Either[Deny, A]): A = result.fold(d => fail(s"unexpected deny: $d"), identity)

  private def assertDeny[A](result: Either[Deny, A], code: String)(using munit.Location): Unit =
    result match
      case Left(d)  => assertEquals(d.code, code)
      case Right(p) => fail(s"plan = $p, want $code deny")

  // ── Rate ──────────────────────────────────────────────────────────────────

  test(s"$file/TestRate_OverridePrecedence") {
    val rec = zero.copy(contributionDefault = Some(50), contributionOverrides = Map("category:table" -> 10))
    assertEquals(Resolver.rate(rec, Game(category = "table"), 100), 10L)
    assertEquals(Resolver.rate(rec, Game(category = "slots"), 100), 50L)
    assertEquals(Resolver.rate(zero, Game(category = "slots"), 100), 100L)
  }

  test(s"$file/TestRate_GameBeatsProviderBeatsCategory") {
    val rec = zero.copy(contributionOverrides = Map("game:g1" -> 5, "provider:p1" -> 10, "category:table" -> 20))
    assertEquals(Resolver.rate(rec, Game(provider = "p1", gameId = "g1", category = "table"), 100), 5L)
    assertEquals(Resolver.rate(rec, Game(provider = "p1", gameId = "other", category = "table"), 100), 10L)
    assertEquals(Resolver.rate(rec, Game(provider = "other", gameId = "other", category = "table"), 100), 20L)
  }

  // ── InScope (parse.go ParsePipeSet("x") is the one-element set built directly) ──

  test(s"$file/TestInScope_EmptyBothIsInScope") {
    assert(Resolver.inScope(zero, Game(provider = "p1")))
  }

  test(s"$file/TestInScope_ExcludeMatch") {
    val rec = zero.copy(scopeExclude = Set("provider:p1"))
    assert(!Resolver.inScope(rec, Game(provider = "p1")), "excluded provider")
    assert(Resolver.inScope(rec, Game(provider = "p2")), "different provider, no include set")
  }

  test(s"$file/TestInScope_IncludeRequiresMatch") {
    val rec = zero.copy(scopeInclude = Set("category:table"))
    assert(Resolver.inScope(rec, Game(category = "table")), "matches include")
    assert(!Resolver.inScope(rec, Game(category = "slots")), "non-empty include, no match")
  }

  test(s"$file/TestInScope_ExcludeWinsOverInclude") {
    val rec = zero.copy(scopeInclude = Set("provider:p1"), scopeExclude = Set("game:g1"))
    assert(!Resolver.inScope(rec, Game(provider = "p1", gameId = "g1")), "exclude beats include")
    assert(Resolver.inScope(rec, Game(provider = "p1", gameId = "g2")), "matches include, no exclude")
  }

  // ── BindingMaxStake ───────────────────────────────────────────────────────

  test(s"$file/TestBindingMaxStake_NoneSet") {
    assertEquals(Resolver.bindingMaxStake(Vector.empty), (None, ""))
    assertEquals(Resolver.bindingMaxStake(Vector(activeRecord("r1", 10, 0, 0, 0, 0))), (None, ""))
  }

  test(s"$file/TestBindingMaxStake_MinimumWins") {
    val a = activeRecord("a", 0, 0, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "block")
    val b = activeRecord("b", 0, 0, 0, 0, 0).copy(maxStake = Some(50), maxStakeEnforcement = "cap")
    assertEquals(Resolver.bindingMaxStake(Vector(a, b)), (Some(5L), "block"))
  }

  test(s"$file/TestBindingMaxStake_TieBlockBeatsCap") {
    val a = activeRecord("a", 0, 0, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "cap")
    val b = activeRecord("b", 0, 0, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "block")
    assertEquals(Resolver.bindingMaxStake(Vector(a, b)), (Some(5L), "block"))
    assertEquals(Resolver.bindingMaxStake(Vector(b, a)), (Some(5L), "block"), "reversed")
  }

  test(s"$file/TestBindingMaxStake_NonActiveRecordIgnored") {
    val converted = Record(id = "c", status = Status.ConvertedPendingCap, maxStake = Some(5), maxStakeEnforcement = "block")
    assertEquals(Resolver.bindingMaxStake(Vector(converted)), (None, ""))
  }

  // ── rule sets through PlaceBet ────────────────────────────────────────────

  test(s"$file/TestPlaceBet_MaxStakeBlock_DeniesAndWritesNothing") {
    val rec = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "block")
    assertDeny(Resolver.placeBet(Wallet(100), Vector(rec), Stake(10), flags(100)), Codes.MaxStakeExceeded)
  }

  test(s"$file/TestPlaceBet_MaxStakeCap_FundsInFullProgressesCapped") {
    val rec  = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0).copy(maxStake = Some(5), maxStakeEnforcement = "cap")
    val plan = ok(Resolver.placeBet(Wallet(100), Vector(rec), Stake(10), flags(100)))
    assertEquals((plan.realLeg, plan.newRealBalance), (10L, 90L))
    assertEquals(plan.contributions.map(_.amount), Vector(5L))
  }

  test(s"$file/TestPlaceBet_ScopeZero_FundedButNoProgress") {
    val rec  = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0).copy(scopeExclude = Set("provider:P"))
    val plan = ok(Resolver.placeBet(Wallet(100), Vector(rec), Stake(10, Game(provider = "P")), flags(100)))
    assert(plan.contributions.isEmpty, plan.contributions.toString)
  }

  test(s"$file/TestPlaceBet_ScopeBlock_Denies") {
    val rec = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0).copy(scopeExclude = Set("provider:P"), scopeViolationMode = "block")
    assertDeny(Resolver.placeBet(Wallet(100), Vector(rec), Stake(10, Game(provider = "P")), flags(100)), Codes.ScopeBlocked)
  }

  test(s"$file/TestPlaceBet_ContributionOverrideTable") {
    val rec = activeRecord("restricted-funds/1", 50, 100, 0, 0, 0)
      .copy(contributionDefault = Some(50), contributionOverrides = Map("category:table" -> 10))
    val w = Wallet(100)
    assertEquals(ok(Resolver.placeBet(w, Vector(rec), Stake(10, Game(category = "table")), flags(100))).contributions.map(_.amount), Vector(1L))
    assertEquals(ok(Resolver.placeBet(w, Vector(rec), Stake(10, Game(category = "slots")), flags(100))).contributions.map(_.amount), Vector(5L))
    val plain = activeRecord("restricted-funds/2", 50, 100, 0, 0, 0)
    assertEquals(ok(Resolver.placeBet(w, Vector(plain), Stake(10, Game(category = "slots")), flags(30))).contributions.map(_.amount), Vector(3L))
  }

  test(s"$file/TestPlaceBet_AllNullRecord_MatchesUserStory1") {
    val rec  = activeRecord("restricted-funds/1", 50, 50, 0, 100, 0)
    val plan = ok(Resolver.placeBet(Wallet(100, restrictedBalance = 50), Vector(rec), Stake(10), flags(100)))
    assertEquals((plan.realLeg, plan.newRealBalance), (10L, 90L))
    assertEquals(plan.contributions.map(_.amount), Vector(10L))
  }

  test(s"$file/TestPlaceBet_CapVsBlockPrecedenceAcrossTwoRecords") {
    val a = activeRecord("a", 5, 100, 0, 0, 0).copy(priority = 1, maxStake = Some(5), maxStakeEnforcement = "block")
    val b = activeRecord("b", 20, 100, 0, 0, 0).copy(priority = 2, maxStake = Some(50), maxStakeEnforcement = "cap")
    assertDeny(Resolver.placeBet(Wallet(0), Vector(a, b), Stake(10), flags(100)), Codes.MaxStakeExceeded)
  }
