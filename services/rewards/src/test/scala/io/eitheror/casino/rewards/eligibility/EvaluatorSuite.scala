package io.eitheror.casino.rewards.eligibility

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.offers.{Offer, OfferType, RedemptionCode}

import java.time.Instant

/**
 * Research R4's thirteen rules in order, the two modes and the two anchors (FR-005, FR-006), from
 * `EligibilityEvaluatorSpec` and its fixtures (the JSON files under `fixtures/eligibility/`), case by case under their
 * names, amounts in minor units ("50.00" is 5000).
 */
class EvaluatorSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-09-22T12:00:00Z")
  private val Gbp = Currency.unsafe("GBP")
  private val Eur = Currency.unsafe("EUR")
  private val longAgo = Anchor.Registration(Instant.parse("2026-01-01T00:00:00Z"))

  private val base = Offer(id = "offer-under-test", offerType = OfferType.DepositMatch, maxBonusValue = Some(5000L),
    createdAt = now, updatedAt = now)
  private val standard = Request(Mode.Standard, longAgo, Gbp)
  private val nobody   = PlayerSnapshot()

  private def deposit(amount: Long, number: Int = 1) = Some(Deposit(amount, Eur, number))
  private def held(offerId: String, status: String, providerFailed: Boolean = false) =
    Held(offerId, Standing.of(status), providerFailed)

  private def run(offer: Offer = base, player: PlayerSnapshot = nobody, walletOpen: Int = 0, request: Request = standard) =
    Evaluator.evaluate(offer, player, walletOpen, request, now)

  private def declined(expected: Decline, offer: Offer = base, player: PlayerSnapshot = nobody, walletOpen: Int = 0,
      request: Request = standard)(using munit.Location): Unit =
    assertEquals(run(offer, player, walletOpen, request).left.map(d => (d, d.reason, d.rule)),
      Left((expected, expected.reason, expected.rule)))

  private def eligible(amount: Long, offer: Offer = base, player: PlayerSnapshot = nobody, walletOpen: Int = 0,
      request: Request = standard)(using munit.Location): Unit =
    assertEquals(run(offer, player, walletOpen, request).map(_.amount), Right(amount))

  // ── duplicate.json: rules 1 to 3 ──────────────────────────────────────────

  private val noDeposit = base.copy(maxBonusValue = Some(1000L), noDepositRequired = true)
  private val checking  = nobody.copy(checkInProgress = true, registeredAt = Some(Instant.parse("2026-09-22T11:58:00Z")))

  test("suspected_duplicate_flagged_declines; _absent_eligible"):
    declined(Decline.SuspectedDuplicate, player = nobody.copy(suspectedDuplicate = true))
    eligible(5000L)

  test("suspected_duplicate_flagged_but_detection_disabled_eligible"):
    eligible(5000L, player = nobody.copy(suspectedDuplicate = true), request = standard.copy(detection = false))

  test("suspected_duplicate_flagged_declines_under_operator_override_too"):
    declined(Decline.SuspectedDuplicate, player = nobody.copy(suspectedDuplicate = true),
      request = standard.copy(mode = Mode.OperatorOverride))

  test("pending_duplicate_check_blocks_no_deposit_offer"):
    declined(Decline.EligibilityCheckUnavailable, noDeposit, checking)

  test("pending_duplicate_check_does_not_block_deposit_required_offer"):
    eligible(5000L, player = checking, request = standard.copy(deposit = deposit(10000L)))

  test("pending_duplicate_check_ignored_when_detection_disabled"):
    eligible(1000L, noDeposit, checking, request = standard.copy(detection = false))

  test("pending_duplicate_check_stale_after_window_is_ignored: ten minutes from registration, exclusive"):
    eligible(1000L, noDeposit, checking.copy(registeredAt = Some(Instant.parse("2026-09-22T11:49:59Z"))))
    eligible(1000L, noDeposit, checking.copy(registeredAt = Some(Instant.parse("2026-09-22T11:50:00Z"))))
    declined(Decline.EligibilityCheckUnavailable, noDeposit, checking.copy(registeredAt = Some(Instant.parse("2026-09-22T11:50:01Z"))))

  test("a check in progress with no registration time known does not refuse"):
    eligible(1000L, noDeposit, checking.copy(registeredAt = None))

  test("no_deposit_offer_blocked_blocks_no_deposit_offer; _does_not_block_deposit_required_offer"):
    declined(Decline.NoDepositOfferBlocked, noDeposit, nobody.copy(noDepositBlocked = true))
    eligible(5000L, player = nobody.copy(noDepositBlocked = true), request = standard.copy(deposit = deposit(10000L)))

  test("no_deposit_offer_blocked_ignored_when_detection_disabled"):
    eligible(1000L, noDeposit, nobody.copy(noDepositBlocked = true), request = standard.copy(detection = false))

  test("the check in progress comes before the weak signal, and the weak signal before the suspected duplicate"):
    val all = checking.copy(noDepositBlocked = true, suspectedDuplicate = true)
    declined(Decline.EligibilityCheckUnavailable, noDeposit, all)
    declined(Decline.NoDepositOfferBlocked, noDeposit, all.copy(checkInProgress = false))

  // ── concurrency.json: rule 4 ──────────────────────────────────────────────

  private val oneOpen = nobody.copy(rewards = Vector(held("offer-other", "ACTIVE")))

  test("open_grant_via_edge_blocks_explicit_limit_one"):
    declined(Decline.ActiveBonusConflict, base.copy(maxConcurrentGrants = Some(1)), oneOpen)

  test("no_open_grants_eligible"):
    eligible(5000L)

  test("wallet_open_without_edge_still_blocks"):
    declined(Decline.ActiveBonusConflict, base.copy(maxConcurrentGrants = Some(1)), walletOpen = 1)

  test("max_concurrent_grants_two_allows_one_open"):
    eligible(5000L, base.copy(maxConcurrentGrants = Some(2)), oneOpen)

  test("absent_limit_is_unlimited_with_open_grant; _with_several_open_grants"):
    eligible(5000L, player = oneOpen, walletOpen = 1)
    val several = nobody.copy(rewards = Vector(held("offer-a", "ACTIVE"), held("offer-b", "ACTIVE"), held("offer-c", "PAUSED")))
    eligible(5000L, player = several, walletOpen = 1)

  test("the open count is the larger of the player's open rewards and the wallet's open records (walletOpenCount backstop)"):
    val cap2 = base.copy(maxConcurrentGrants = Some(2))
    eligible(5000L, cap2, walletOpen = 1)
    declined(Decline.ActiveBonusConflict, cap2, walletOpen = 2)
    declined(Decline.ActiveBonusConflict, cap2, nobody.copy(rewards = Vector(held("a", "ACTIVE"), held("b", "CONVERTED_PENDING_CAP"))))
    assertEquals(Evaluator.openCount(oneOpen, 0), 1)
    assertEquals(Evaluator.openCount(oneOpen, 3), 3)

  test("available, pending and terminal rewards are not open"):
    val notOpen = nobody.copy(rewards = Vector("AVAILABLE", "PENDING", "CLOSED", "EXPIRED", "CANCELLED", "FORFEITED", "REVOKED")
      .map(held("x", _)))
    eligible(5000L, base.copy(maxConcurrentGrants = Some(1)), notOpen)

  // ── conflicts.json: rule 5 ────────────────────────────────────────────────

  private val conflicting = base.copy(maxConcurrentGrants = Some(5), conflictsWith = Set("offer-other"))

  test("conflicting_open_grant_declines; no_conflict_eligible; conflicts_with_absent_eligible"):
    declined(Decline.ConflictingGrant, conflicting, oneOpen)
    eligible(5000L, conflicting.copy(conflictsWith = Set("offer-unrelated")), oneOpen)
    eligible(5000L, conflicting.copy(conflictsWith = Set.empty), oneOpen)

  test("a conflict with an offer held but not open does not refuse"):
    eligible(5000L, conflicting, nobody.copy(rewards = Vector(held("offer-other", "CLOSED"))))

  test("conflicts_rule_skipped_under_operator_override"):
    eligible(5000L, conflicting, oneOpen, request = standard.copy(mode = Mode.OperatorOverride))

  // ── claims.json: rule 6 ───────────────────────────────────────────────────

  private val claimsOffer = base.copy(id = "offer-claims-test", maxClaimsPerPlayer = Some(1))
  private def claimed(status: String, providerFailed: Boolean = false) =
    nobody.copy(rewards = Vector(held("offer-claims-test", status, providerFailed)))

  test("claims_at_limit_declines; claims_below_limit_eligible"):
    declined(Decline.MaxClaimsReached, claimsOffer, claimed("CLOSED"))
    eligible(5000L, claimsOffer)

  test("max_claims_absent_unlimited_eligible; max_claims_explicit_zero_unlimited_eligible"):
    eligible(5000L, claimsOffer.copy(maxClaimsPerPlayer = None), claimed("CLOSED"))
    eligible(5000L, claimsOffer.copy(maxClaimsPerPlayer = Some(0)), claimed("CLOSED"))

  test("pending_or_available_grant_not_counted_as_claim"):
    eligible(5000L, claimsOffer, claimed("AVAILABLE"))
    eligible(5000L, claimsOffer, claimed("PENDING"))

  test("provider_failed_grant_is_not_a_claim; cancelled_grant_without_provider_failed_still_claims"):
    eligible(5000L, claimsOffer, claimed("CANCELLED", providerFailed = true))
    declined(Decline.MaxClaimsReached, claimsOffer, claimed("CANCELLED"))

  test("an active reward of the offer is a claim of it"):
    declined(Decline.MaxClaimsReached, claimsOffer, claimed("ACTIVE"))

  test("alreadyClaimed is over the same claims (ALREADY_CLAIMED is the award's answer, not a rule)"):
    assert(Evaluator.alreadyClaimed(claimed("ACTIVE"), "offer-claims-test"))
    assert(!Evaluator.alreadyClaimed(claimed("PENDING"), "offer-claims-test"))
    assert(!Evaluator.alreadyClaimed(claimed("CANCELLED", providerFailed = true), "offer-claims-test"))
    assert(!Evaluator.alreadyClaimed(nobody, "offer-claims-test"))
    assertEquals(Decline.AlreadyClaimed.reason, "ALREADY_CLAIMED")

  // ── exclusions.json: rule 7 ───────────────────────────────────────────────

  test("excluded_offer_claimed_via_edge_declines; no_excluded_claims_eligible; excluded_ids_absent_eligible"):
    val excluding = base.copy(excludedOfferIds = Set("offerX"))
    val claimedX  = nobody.copy(rewards = Vector(held("offerX", "CLOSED")))
    declined(Decline.ExcludedByPriorOffer, excluding, claimedX)
    eligible(5000L, excluding)
    eligible(5000L, base, claimedX)
    eligible(5000L, excluding, nobody.copy(rewards = Vector(held("offerX", "AVAILABLE"))))

  // ── code.json: rule 8 ─────────────────────────────────────────────────────

  private val coded = base.copy(redemptionCodes = Vector(RedemptionCode("WELCOME10")))

  test("code_required_missing_declines; code_matches_eligible; code_mismatch_declines; no_codes_required_eligible"):
    declined(Decline.InvalidCode, coded)
    eligible(5000L, coded, request = standard.copy(code = Some("WELCOME10")))
    declined(Decline.InvalidCode, coded, request = standard.copy(code = Some("WRONGCODE")))
    eligible(5000L, request = standard.copy(code = Some("ANY")))

  test("a code matches trimmed and in any case (research R4); an affiliate code is a code"):
    eligible(5000L, coded, request = standard.copy(code = Some("  welcome10 ")))
    eligible(5000L, base.copy(redemptionCodes = Vector(RedemptionCode("AFF1", affiliate = true))), request = standard.copy(code = Some("AFF1")))

  test("code_verified_at_targeting_skips_rule, and the deposit-confirm activation of a code-gated pending reward"):
    eligible(5000L, coded, request = standard.copy(codeVerifiedAtTargeting = true))
    val confirm = Request(Mode.Standard, Anchor.Selection(Instant.parse("2026-09-20T00:00:00Z")), Eur,
      deposit = deposit(10000L), codeVerifiedAtTargeting = true)
    eligible(5000L, coded, request = confirm)

  // ── min_deposit.json: rule 9 ──────────────────────────────────────────────

  private val minimum = base.copy(minDepositAmount = Some(5000L))

  test("deposit_below_min_declines; deposit_meets_min_eligible; min_deposit_absent_no_constraint"):
    declined(Decline.BelowMinDeposit, minimum, request = standard.copy(deposit = deposit(2000L)))
    eligible(5000L, minimum, request = standard.copy(deposit = deposit(5000L)))
    eligible(5000L, request = standard.copy(deposit = deposit(100L)))

  test("no_deposit_required_skips_check"):
    eligible(5000L, minimum.copy(noDepositRequired = true))
    eligible(5000L, minimum.copy(noDepositRequired = true), request = standard.copy(deposit = deposit(100L)))

  test("a deposit offer with a minimum claimed with no deposit is below the minimum (EE:380-388)"):
    declined(Decline.BelowMinDeposit, minimum)

  test("an explicit enforcement overrides the derived one (the no-deposit select path)"):
    eligible(5000L, minimum, request = standard.copy(enforceMinDeposit = Some(false)))
    declined(Decline.BelowMinDeposit, minimum.copy(noDepositRequired = true), request = standard.copy(enforceMinDeposit = Some(true)))

  // ── deposit_number.json: rule 10 ──────────────────────────────────────────

  test("deposit_number_mismatch_declines; _matches_eligible; _target_any_eligible; _target_absent_defaults_any_eligible"):
    val second = base.copy(depositNumberTarget = Some(2))
    declined(Decline.DepositNumberMismatch, second, request = standard.copy(deposit = deposit(5000L, 1)))
    eligible(5000L, second, request = standard.copy(deposit = deposit(5000L, 2)))
    eligible(5000L, request = standard.copy(deposit = deposit(5000L, 5)))

  test("deposit_number_ignored_with_no_deposit"):
    eligible(5000L, base.copy(depositNumberTarget = Some(2), noDepositRequired = true))

  // ── claim_window.json: rule 11 and the anchors ────────────────────────────

  private val thirty = base.copy(claimWindowDays = Some(30))
  private def at(text: String) = Instant.parse(text)

  test("registration_anchor_absent_window_no_constraint_eligible; _explicit_zero_no_constraint_eligible"):
    val old = standard.copy(anchor = Anchor.Registration(at("2020-01-01T00:00:00Z")))
    eligible(5000L, request = old)
    eligible(5000L, base.copy(claimWindowDays = Some(0)), request = old)

  test("registration_anchor_within_window_eligible; registration_anchor_expired_declines"):
    eligible(5000L, thirty, request = standard.copy(anchor = Anchor.Registration(at("2026-09-01T00:00:00Z"))))
    declined(Decline.ClaimWindowExpired, thirty, request = standard.copy(anchor = Anchor.Registration(at("2026-01-01T00:00:00Z"))))

  test("selection_anchor_default_three_days_eligible; _expired_declines; _explicit_zero_uses_three_day_default_eligible"):
    eligible(5000L, request = standard.copy(anchor = Anchor.Selection(at("2026-09-21T12:00:00Z"))))
    declined(Decline.ClaimWindowExpired, request = standard.copy(anchor = Anchor.Selection(at("2026-09-01T00:00:00Z"))))
    eligible(5000L, base.copy(claimWindowDays = Some(0)), request = standard.copy(anchor = Anchor.Selection(at("2026-09-21T12:00:00Z"))))

  test("the window's end is inside it: expired only after the anchor plus the days"):
    eligible(5000L, request = standard.copy(anchor = Anchor.Selection(at("2026-09-19T12:00:00Z"))))
    declined(Decline.ClaimWindowExpired, request = standard.copy(anchor = Anchor.Selection(at("2026-09-19T11:59:59Z"))))
    eligible(5000L, base.copy(claimWindowDays = Some(7)), request = standard.copy(anchor = Anchor.Selection(at("2026-09-16T00:00:00Z"))))

  test("a registration anchor with no registration time known is now, never expired"):
    eligible(5000L, base.copy(claimWindowDays = Some(1)), request = standard.copy(anchor = Anchor.registration(None, now)))
    assertEquals(Anchor.registration(Some(at("2026-01-01T00:00:00Z")), now), Anchor.Registration(at("2026-01-01T00:00:00Z")))

  // ── segment.json and vip.json: rules 12 and 13, dormant ───────────────────

  test("segment_missing_declines; segment_present_eligible; segment_predicate_absent_no_constraint_eligible"):
    val segmented = base.copy(segmentPredicate = Some("high_roller"))
    declined(Decline.SegmentMismatch, segmented, nobody.copy(segments = Some(Set("vip"))))
    eligible(5000L, segmented, nobody.copy(segments = Some(Set("high_roller", "vip"))))
    eligible(5000L, player = nobody.copy(segments = Some(Set.empty)))

  test("a player with no segments known is not constrained by a predicate (dormant, research R4)"):
    eligible(5000L, base.copy(segmentPredicate = Some("high_roller")))

  test("vip_tier_below_floor_declines; vip_tier_meets_floor_eligible; vip_tier_floor_absent_no_constraint_eligible"):
    val floored = base.copy(vipTierFloor = Some(3))
    declined(Decline.VipTierTooLow, floored, nobody.copy(tierRank = Some(2)))
    eligible(5000L, floored, nobody.copy(tierRank = Some(3)))
    eligible(5000L, player = nobody.copy(tierRank = Some(0)))

  test("a floor passes while no rank is known (dormant, research R4)"):
    eligible(5000L, base.copy(vipTierFloor = Some(3)))

  // ── order.json: every rule violated, each fixed in turn ───────────────────

  private val orderOffer = base.copy(id = "offer-order-test", maxConcurrentGrants = Some(1), conflictsWith = Set("offer-other"),
    maxClaimsPerPlayer = Some(1), excludedOfferIds = Set("offer-other"), redemptionCodes = Vector(RedemptionCode("WELCOME10")),
    minDepositAmount = Some(5000L), depositNumberTarget = Some(2), claimWindowDays = Some(10), segmentPredicate = Some("high_roller"),
    vipTierFloor = Some(3))
  private val orderPlayer = nobody.copy(suspectedDuplicate = true, segments = Some(Set("vip")), tierRank = Some(1),
    rewards = Vector(held("offer-other", "ACTIVE"), held("offer-order-test", "CLOSED")))
  private val orderRequest = standard.copy(code = Some("WRONGCODE"), deposit = deposit(1000L))

  test("order.json: the first violated rule is answered, then each in R4's order as the earlier is fixed"):
    type Inputs = (Offer, PlayerSnapshot, Request)
    val fixes = Vector[Inputs => Inputs](
      (o, p, r) => (o, p.copy(suspectedDuplicate = false), r),
      (o, p, r) => (o.copy(maxConcurrentGrants = Some(2)), p, r),
      (o, p, r) => (o.copy(conflictsWith = Set("offer-unrelated")), p, r),
      (o, p, r) => (o.copy(maxClaimsPerPlayer = Some(2)), p, r),
      (o, p, r) => (o.copy(excludedOfferIds = Set("offer-unrelated")), p, r),
      (o, p, r) => (o, p, r.copy(code = Some("WELCOME10"))),
      (o, p, r) => (o, p, r.copy(deposit = deposit(5000L))),
      (o, p, r) => (o, p, r.copy(deposit = deposit(5000L, 2))),
      (o, p, r) => (o.copy(claimWindowDays = Some(9999)), p, r),
      (o, p, r) => (o, p.copy(segments = Some(Set("high_roller", "vip"))), r),
      (o, p, r) => (o, p.copy(tierRank = Some(3)), r)
    )
    val expected = Vector(Decline.SuspectedDuplicate, Decline.ActiveBonusConflict, Decline.ConflictingGrant, Decline.MaxClaimsReached,
      Decline.ExcludedByPriorOffer, Decline.InvalidCode, Decline.BelowMinDeposit, Decline.DepositNumberMismatch,
      Decline.ClaimWindowExpired, Decline.SegmentMismatch, Decline.VipTierTooLow)
    val stages = fixes.scanLeft[Inputs]((orderOffer, orderPlayer, orderRequest))((in, fix) => fix(in))
    for ((o, p, r), want) <- stages.zip(expected) do
      assertEquals(Evaluator.evaluate(o, p, 0, r, now).left.toOption, Some(want), want)
    val (o, p, r) = stages.last
    assertEquals(Evaluator.evaluate(o, p, 0, r, now).map(_.amount), Right(5000L))

  // ── modes.json: an operator's award applies rules 1 to 4 ──────────────────

  test("operator_override_still_checks_duplicate; _still_checks_concurrency; _skips_rules_5_through_13_eligible"):
    val operator = orderRequest.copy(mode = Mode.OperatorOverride)
    declined(Decline.SuspectedDuplicate, orderOffer, orderPlayer, request = operator)
    declined(Decline.ActiveBonusConflict, orderOffer, orderPlayer.copy(suspectedDuplicate = false), request = operator)
    eligible(5000L, orderOffer.copy(maxConcurrentGrants = Some(5)), orderPlayer.copy(suspectedDuplicate = false), request = operator)

  test("an operator's award still applies the duplicate check in progress and the weak signal"):
    val operator = standard.copy(mode = Mode.OperatorOverride)
    declined(Decline.EligibilityCheckUnavailable, noDeposit, checking, request = operator)
    declined(Decline.NoDepositOfferBlocked, noDeposit, nobody.copy(noDepositBlocked = true), request = operator)

  // ── the decision ──────────────────────────────────────────────────────────

  test("an eligible decision carries the amount, the requirement, the convertible cap and the currency"):
    val matched = base.copy(bonusPercentage = 10000L, maxBonusValue = Some(50000L), wageringMultiplier = 30L, minDepositAmount = Some(2000L))
    val decision = run(matched, request = standard.copy(deposit = deposit(5000L))).toOption.get
    assertEquals(decision, Decision(5000L, 150000L, 5000L, 30L, Eur))

  test("the currency is the offer's first, then the deposit's, then the player's wallet currency (FR-007)"):
    val zar = Currency.unsafe("ZAR")
    assertEquals(run(base.copy(currency = Some(zar)), request = standard.copy(deposit = deposit(100L))).map(_.currency), Right(zar))
    assertEquals(run(request = standard.copy(deposit = deposit(100L))).map(_.currency), Right(Eur))
    assertEquals(run().map(_.currency), Right(Gbp))

  test("Standing reads the ten states in any case"):
    assertEquals(Vector("ACTIVE", "paused", "Converted_Pending_Cap").map(Standing.of), Vector.fill(3)(Standing.Open))
    assertEquals(Vector("AVAILABLE", "pending").map(Standing.of), Vector.fill(2)(Standing.Unclaimed))
    assertEquals(Vector("CLOSED", "EXPIRED", "CANCELLED", "FORFEITED", "REVOKED").map(Standing.of), Vector.fill(5)(Standing.Ended))

  test("a decline is written as its reason"):
    import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
    assertEquals(writeToString(Decline.ClaimWindowExpired), "\"CLAIM_WINDOW_EXPIRED\"")
    assertEquals(Decline.values.map(d => readFromString[Decline](writeToString(d))).toVector, Decline.values.toVector)
    assertEquals(Decline.from("vip_tier_too_low"), Some(Decline.VipTierTooLow))
