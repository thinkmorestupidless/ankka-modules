package io.eitheror.casino.rewards.offers

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import io.eitheror.casino.contract.money.Currency

import java.time.Instant

/**
 * The create and edit checks in data-model.md's order with their codes (FR-002, FR-003), from
 * `BonusRoutesSpec`'s create cases (`BR:2036-2260`) and research R1's edit list. The provider's spin-value
 * and game checks are the endpoint's, before these; what they must ask is `spinValueChecks`.
 */
class OfferRulesSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-11-01T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")
  private val Zar = Currency.unsafe("ZAR")

  private def offer(id: String, t: OfferType = OfferType.DepositMatch) =
    Offer(id = id, name = s"offer $id", offerType = t, createdAt = now, updatedAt = now)

  private val depositMatch = offer("o-1").copy(
    description = "100% up to 500",
    bonusPercentage = 10000L,
    maxBonusValue = Some(50000L),
    minDepositAmount = Some(2000L),
    wageringMultiplier = 30L,
    claimWindowDays = Some(7),
    wageringWindowDays = Some(30),
    maxClaimsPerPlayer = Some(1),
    redemptionCodes = Vector(RedemptionCode("Welcome")),
    role = Some("welcome"),
    priority = Some(10L)
  )

  /** A pure free-rounds offer paying real balance: no maximum value, no multiplier. */
  private val freeRounds = offer("o-fs", OfferType.FreeSpins).copy(
    currency = Some(Eur),
    gameId = Some("g-1"),
    gameName = Some("Book"),
    provider = Some("st8"),
    spinCount = Some(10),
    spinValue = Some(10L)
  )

  private def create(d: Draft, book: Iterable[Offer] = Vector.empty, programme: Set[String] = Set.empty) =
    OfferRules.create(d, book, programme)
  private def code(r: Either[Refusal, Offer]) = r.left.map(x => (x.status, x.error))
  private def detail(r: Either[Refusal, Offer]) = r.left.toOption.map(_.detail).getOrElse(Vector.empty)

  // ── create ────────────────────────────────────────────────────────────────

  test("a deposit match with every field as given is created active, its payload derived from its type"):
    val created = create(Draft(depositMatch)).toOption.get
    assertEquals(created, depositMatch.copy(active = true))
    assertEquals(created.payload, PayloadType.BonusFunds)

  test("bonus_amount_required: no maximum value, or a maximum value of zero (BR create cases)"):
    for max <- Vector(None, Some(0L)) do
      assertEquals(code(create(Draft(depositMatch.copy(maxBonusValue = max)))), Left((400, "bonus_amount_required")), max)

  test("a pure free-rounds offer needs no maximum value; a deposit match with free rounds does"):
    assert(create(Draft(freeRounds)).isRight)
    val withSpins = freeRounds.copy(offerType = OfferType.DepositMatchWithFreeSpins, wageringMultiplier = 30L)
    assertEquals(code(create(Draft(withSpins))), Left((400, "bonus_amount_required")))

  test("wagering_multiplier_required: none, or zero"):
    for m <- Vector(0L, -1L) do
      assertEquals(code(create(Draft(depositMatch.copy(wageringMultiplier = m)))), Left((400, "wagering_multiplier_required")))

  test("a pure free-rounds offer paying real balance needs no multiplier; one paying restricted funds does"):
    assert(create(Draft(freeRounds.copy(winningsDestination = Some(WinningsDestination.RealBalance)))).isRight)
    val toBonus = freeRounds.copy(winningsDestination = Some(WinningsDestination.BonusBalance), maxConvertibleAmount = Some(5000L))
    assertEquals(code(create(Draft(toBonus))), Left((400, "wagering_multiplier_required")))
    assert(create(Draft(toBonus.copy(wageringMultiplier = 5L))).isRight)

  test("invalid_payload_type: an explicit value not of the four; an explicit valid one wins over the derived"):
    assertEquals(code(create(Draft(depositMatch, payloadType = Some("cash")))), Left((400, "invalid_payload_type")))
    val real = create(Draft(depositMatch, payloadType = Some("real_cash"))).toOption.get
    assertEquals((real.payloadType, real.payload), (Some(PayloadType.RealCash), PayloadType.RealCash))
    assertEquals(create(Draft(depositMatch, payloadType = Some(" "))).map(_.payloadType), Right(None))

  test("max_concurrent_grants_invalid: below one; absent is no limit"):
    for n <- Vector(0, -2) do
      assertEquals(code(create(Draft(depositMatch.copy(maxConcurrentGrants = Some(n))))), Left((400, "max_concurrent_grants_invalid")))
    assert(create(Draft(depositMatch.copy(maxConcurrentGrants = Some(1)))).isRight)
    assert(create(Draft(depositMatch.copy(maxConcurrentGrants = None))).isRight)

  test("spin_configurations_invalid names the reason: a currency, a spin value, a currency twice"):
    def reason(entries: SpinEntry*) =
      val r = create(Draft(freeRounds, spinConfigurations = Some(entries.toVector)))
      (code(r), detail(r))
    assertEquals(reason(SpinEntry(" ", 10L)), (Left((400, "spin_configurations_invalid")), Vector("reason" -> "invalid_currency")))
    assertEquals(reason(SpinEntry("euro", 10L))._2, Vector("reason" -> "invalid_currency"))
    assertEquals(reason(SpinEntry("EUR", 0L))._2, Vector("reason" -> "invalid_spin_value:EUR"))
    assertEquals(reason(SpinEntry("eur", 10L), SpinEntry(" EUR ", 20L))._2, Vector("reason" -> "duplicate_currency:EUR"))

  test("spin configurations are trimmed and upper-cased and clear the flat currency and spin value"):
    val created = create(Draft(freeRounds, spinConfigurations = Some(Vector(SpinEntry(" eur", 10L), SpinEntry("ZAR", 200L))))).toOption.get
    assertEquals(created.spinConfigurations, Map(Eur -> 10L, Zar -> 200L))
    assertEquals((created.currency, created.spinValue), (None, None))
    assertEquals(create(Draft(freeRounds, spinConfigurations = Some(Vector.empty))).map(_.spinConfigurations), Right(Map.empty))

  test("the free-rounds fields in order: currency, game, count, spin value, maximum convertible"):
    val bare = offer("o-fs", OfferType.FreeSpins).copy(winningsDestination = Some(WinningsDestination.BonusBalance), wageringMultiplier = 5L)
    val steps = Vector(
      bare                                         -> "currency_required",
      bare.copy(currency = Some(Eur))              -> "game_id_required",
      bare.copy(currency = Some(Eur), gameId = Some("g-1")) -> "spin_count_required",
      bare.copy(currency = Some(Eur), gameId = Some("g-1"), spinCount = Some(10)) -> "spin_value_required",
      bare.copy(currency = Some(Eur), gameId = Some("g-1"), spinCount = Some(10), spinValue = Some(10L)) -> "max_convertible_amount_required"
    )
    for (o, expected) <- steps do assertEquals(code(create(Draft(o))), Left((400, expected)), expected)
    assertEquals(code(create(Draft(bare.copy(spinCount = Some(0), currency = Some(Eur), gameId = Some("g-1"))))), Left((400, "spin_count_required")))

  test("spin configurations satisfy the currency and the spin value of a free-rounds offer"):
    val noPair = freeRounds.copy(currency = None, spinValue = None)
    assertEquals(code(create(Draft(noPair))), Left((400, "currency_required")))
    assert(create(Draft(noPair, spinConfigurations = Some(Vector(SpinEntry("EUR", 10L))))).isRight)

  test("a deposit match with free rounds derives the free-rounds payload and its checks; the explicit payload of that name does not"):
    val withSpins = offer("o-dm", OfferType.DepositMatchWithFreeSpins).copy(maxBonusValue = Some(1000L), wageringMultiplier = 30L)
    assertEquals(code(create(Draft(withSpins))), Left((400, "currency_required")))
    assert(create(Draft(withSpins, payloadType = Some("deposit_match_with_free_spins"))).isRight)

  test("a draft failing every check is answered the first, then each in data-model.md's order"):
    val worst = offer("o-x", OfferType.FreeSpins).copy(
      winningsDestination = Some(WinningsDestination.BonusBalance), maxConcurrentGrants = Some(0),
      redemptionCodes = Vector(RedemptionCode("A"), RedemptionCode("a")))
    val bad = Draft(worst.copy(offerType = OfferType.DepositMatchWithFreeSpins), payloadType = Some("x"),
      spinConfigurations = Some(Vector(SpinEntry("", 1L))))
    val order = Vector(
      bad                                                                   -> "bonus_amount_required",
      bad.copy(offer = bad.offer.copy(maxBonusValue = Some(100L)))          -> "wagering_multiplier_required",
      bad.copy(offer = bad.offer.copy(maxBonusValue = Some(100L), wageringMultiplier = 2L)) -> "invalid_payload_type"
    )
    for (d, expected) <- order do assertEquals(code(create(d)).left.map(_._2), Left(expected))
    val pastPayload = bad.copy(offer = bad.offer.copy(maxBonusValue = Some(100L), wageringMultiplier = 2L), payloadType = None)
    assertEquals(code(create(pastPayload)).left.map(_._2), Left("max_concurrent_grants_invalid"))
    val pastConcurrent = pastPayload.copy(offer = pastPayload.offer.copy(maxConcurrentGrants = None))
    assertEquals(code(create(pastConcurrent)).left.map(_._2), Left("spin_configurations_invalid"))
    assertEquals(code(create(pastConcurrent.copy(spinConfigurations = None))).left.map(_._2), Left("currency_required"))
    val pastSpins = pastConcurrent.copy(spinConfigurations = None, offer = freeRounds.copy(redemptionCodes = worst.redemptionCodes))
    assertEquals(code(create(pastSpins)), Left((409, "duplicate_code_in_request")))

  test("duplicate_code_in_request: the request repeats a code in any case or spacing (409)"):
    val twice = depositMatch.copy(redemptionCodes = Vector(RedemptionCode("Summer10"), RedemptionCode(" summer10 ", affiliate = true)))
    assertEquals(code(create(Draft(twice))), Left((409, "duplicate_code_in_request")))

  test("code_already_in_use: another active offer's code in any case, naming the code as entered (409)"):
    val other = offer("o-2").copy(redemptionCodes = Vector(RedemptionCode("SUMMER10")))
    val mine  = depositMatch.copy(redemptionCodes = Vector(RedemptionCode("new"), RedemptionCode("summer10")))
    val r     = create(Draft(mine), Vector(other))
    assertEquals(code(r), Left((409, "code_already_in_use")))
    assertEquals(detail(r), Vector("code" -> "summer10"))

  test("a code an inactive offer carries is free to use"):
    val other = offer("o-2").copy(active = false, redemptionCodes = Vector(RedemptionCode("SUMMER10")))
    assert(create(Draft(depositMatch.copy(redemptionCodes = Vector(RedemptionCode("SUMMER10")))), Vector(other)).isRight)

  test("code_already_in_use: a code the programme's document holds, in any case (codes.feature)"):
    val r = create(Draft(depositMatch.copy(redemptionCodes = Vector(RedemptionCode("aff100")))), programme = Set("AFF100"))
    assertEquals((code(r), detail(r)), (Left((409, "code_already_in_use")), Vector("code" -> "aff100")))

  test("the duplicate in the request comes before the code in use"):
    val other = offer("o-2").copy(redemptionCodes = Vector(RedemptionCode("X")))
    val mine  = depositMatch.copy(redemptionCodes = Vector(RedemptionCode("x"), RedemptionCode("X")))
    assertEquals(code(create(Draft(mine), Vector(other))), Left((409, "duplicate_code_in_request")))

  test("an offer survives its stored form"):
    val created = create(Draft(freeRounds, spinConfigurations = Some(Vector(SpinEntry("EUR", 10L))))).toOption.get
      .copy(betRules = BetRules(maxStake = Some(500L), contributionOverrides = Map("slots" -> 10000L)),
        excludedOfferIds = Set("o-0"), depositNumberTarget = Some(2), validUntil = Some(now))
    assertEquals(readFromString[Offer](writeToString(created)), created)

  // ── edit ──────────────────────────────────────────────────────────────────

  private val book = Vector(depositMatch, freeRounds)
  private def edit(id: String, p: Patch, b: Iterable[Offer] = book, programme: Set[String] = Set.empty) =
    OfferRules.edit(id, p, b, programme, now.plusSeconds(60))

  test("offer_not_found: an offer the book does not hold (404)"):
    assertEquals(code(edit("nope", Patch(name = Some("x")))), Left((404, "offer_not_found")))

  test("an edit applies the fields it names, keeps the rest and stamps the time"):
    val edited = edit("o-1", Patch(name = Some("Big welcome"), maxBonusValue = Some(Some(60000L)), claimWindowDays = Some(None))).toOption.get
    assertEquals(edited, depositMatch.copy(name = "Big welcome", maxBonusValue = Some(60000L), claimWindowDays = None,
      updatedAt = now.plusSeconds(60)))

  test("the edit list in order: payload type, maximum concurrent, spin configurations"):
    val p = Patch(payloadType = Some("x"), maxConcurrentGrants = Some(Some(0)), spinConfigurations = Some(Vector(SpinEntry("", 1L))))
    assertEquals(code(edit("o-fs", p)), Left((400, "invalid_payload_type")))
    assertEquals(code(edit("o-fs", p.copy(payloadType = None))), Left((400, "max_concurrent_grants_invalid")))
    assertEquals(code(edit("o-fs", p.copy(payloadType = None, maxConcurrentGrants = Some(None)))), Left((400, "spin_configurations_invalid")))

  test("an edit does not apply the create's amount checks (the original's PATCH)"):
    assert(edit("o-1", Patch(maxBonusValue = Some(None), wageringMultiplier = Some(0L))).isRight)

  test("spin_value_required: an edit of the spin fields leaving a free-rounds offer neither a spin value nor configurations"):
    assertEquals(code(edit("o-fs", Patch(spinValue = Some(None)))), Left((400, "spin_value_required")))
    assertEquals(code(edit("o-fs", Patch(currency = Some(None)))), Left((400, "spin_value_required")))
    assert(edit("o-fs", Patch(spinValue = Some(None), spinConfigurations = Some(Vector(SpinEntry("EUR", 10L))))).isRight)
    assert(edit("o-1", Patch(spinValue = Some(None))).isRight, "a deposit match has no spin value to keep")

  test("an edit setting spin configurations clears the flat pair"):
    val edited = edit("o-fs", Patch(spinConfigurations = Some(Vector(SpinEntry("zar", 200L))))).toOption.get
    assertEquals((edited.spinConfigurations, edited.currency, edited.spinValue), (Map(Zar -> 200L), None, None))

  test("role_already_assigned: another active offer holds the role (409); the offer's own role, or an inactive offer's, is not"):
    val other = offer("o-3").copy(role = Some("reload"))
    assertEquals(code(edit("o-1", Patch(role = Some(Some("reload"))), book :+ other)), Left((409, "role_already_assigned")))
    assert(edit("o-1", Patch(role = Some(Some("welcome"))), book :+ other).isRight)
    assert(edit("o-1", Patch(role = Some(Some("reload"))), book :+ other.copy(active = false)).isRight)
    assert(edit("o-1", Patch(role = Some(None)), book :+ other).isRight)

  test("the role check comes after the spin checks"):
    val other = offer("o-3").copy(role = Some("reload"))
    assertEquals(code(edit("o-fs", Patch(role = Some(Some("reload")), spinValue = Some(None)), book :+ other)), Left((400, "spin_value_required")))

  test("an edit changes codes, with the create's code checks; the offer's own codes are not in use"):
    val other = offer("o-3").copy(redemptionCodes = Vector(RedemptionCode("SPRING")))
    val r     = edit("o-1", Patch(redemptionCodes = Some(Vector(RedemptionCode("WELCOME"), RedemptionCode("spring")))), book :+ other)
    assertEquals((code(r), detail(r)), (Left((409, "code_already_in_use")), Vector("code" -> "spring")))
    assertEquals(code(edit("o-1", Patch(redemptionCodes = Some(Vector(RedemptionCode("a"), RedemptionCode("A")))))),
      Left((409, "duplicate_code_in_request")))
    val kept = edit("o-1", Patch(redemptionCodes = Some(Vector(RedemptionCode("WELCOME"), RedemptionCode("autumn")))), book :+ other)
    assertEquals(kept.map(_.redemptionCodes.map(_.code)), Right(Vector("WELCOME", "autumn")))

  test("an edit resending a code the offer already carries is not refused when the programme's document has since taken it"):
    assert(edit("o-1", Patch(name = Some("x"), redemptionCodes = Some(depositMatch.redemptionCodes)), programme = Set("welcome")).isRight)

  test("an edit changes the type and the payload derives from it while none is explicit"):
    val edited = edit("o-1", Patch(offerType = Some(OfferType.NoDeposit), noDepositRequired = Some(true))).toOption.get
    assertEquals((edited.offerType, edited.payload, edited.noDepositRequired), (OfferType.NoDeposit, PayloadType.BonusFunds, true))

  // ── what the provider is asked ────────────────────────────────────────────

  test("spinValueChecks: a created free-rounds offer's every currency; none for a deposit match"):
    assertEquals(OfferRules.spinValueChecks(freeRounds), Vector(SpinCheck("g-1", Eur, 10L)))
    val multi = freeRounds.copy(currency = None, spinValue = None, spinConfigurations = Map(Eur -> 10L, Zar -> 200L))
    assertEquals(OfferRules.spinValueChecks(multi).toSet, Set(SpinCheck("g-1", Eur, 10L), SpinCheck("g-1", Zar, 200L)))
    assertEquals(OfferRules.spinValueChecks(depositMatch), Vector.empty)

  test("spinValueChecks after an edit: only the triples it changed (the original's 237)"):
    assertEquals(OfferRules.spinValueChecks(freeRounds.copy(name = "renamed"), Some(freeRounds)), Vector.empty)
    assertEquals(OfferRules.spinValueChecks(freeRounds.copy(spinValue = Some(20L)), Some(freeRounds)), Vector(SpinCheck("g-1", Eur, 20L)))
    val before = freeRounds.copy(currency = None, spinValue = None, spinConfigurations = Map(Eur -> 10L))
    val after  = before.copy(spinConfigurations = Map(Eur -> 10L, Zar -> 200L))
    assertEquals(OfferRules.spinValueChecks(after, Some(before)), Vector(SpinCheck("g-1", Zar, 200L)))
    assertEquals(OfferRules.spinValueChecks(after.copy(gameId = Some("g-2")), Some(before)).size, 2)

  // ── codes ─────────────────────────────────────────────────────────────────

  test("a code is normalised trimmed and in lower case"):
    assertEquals(Codes.normalise("  WeLcome10 "), "welcome10")

  test("a code matches a live offer's code in any case first, then the programme's document"):
    val programme = Map("welcome" -> ProgrammeEntry("o-aff", Some("tag-1")), "aff100" -> ProgrammeEntry("o-aff", Some("tag-2")))
    assertEquals(Codes.find(" WELCOME ", book, programme, now), Some(CodeMatch.Brand("o-1")))
    assertEquals(Codes.find("Aff100", book, programme, now), Some(CodeMatch.Affiliate("o-aff", Some("tag-2"))))
    assertEquals(Codes.find("nope", book, programme, now), None)

  test("an offer not live does not match its code: inactive, not yet valid, or past its validity"):
    for o <- Vector(depositMatch.copy(active = false), depositMatch.copy(validFrom = Some(now.plusSeconds(1))),
        depositMatch.copy(validUntil = Some(now.minusSeconds(1)))) do
      assertEquals(Codes.find("welcome", Vector(o), Map.empty, now), None, o)
    assertEquals(Codes.find("welcome", Vector(depositMatch.copy(validFrom = Some(now.minusSeconds(1)), validUntil = Some(now.plusSeconds(1)))),
      Map.empty, now), Some(CodeMatch.Brand("o-1")))
