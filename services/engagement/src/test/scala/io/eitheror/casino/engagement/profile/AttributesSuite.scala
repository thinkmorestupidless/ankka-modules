package io.eitheror.casino.engagement.profile

import com.thinkmorestupidless.ankka.core.Personal

import java.time.{Instant, LocalDate}

/**
 * The identify's attributes (FR-003, FR-008): `handler_test.go`'s `buildIdentifyAttrs` cases and
 * `daily_deposit_count_test.go`'s attribute, balance and encoding cases, each named after the original
 * test, held to the Scala form where the two differ (`DEPOSIT_DAILY` not `rg_limit_DEPOSIT_DAILY`, the
 * restricted balance as `bonus_money`).
 */
class AttributesSuite extends munit.FunSuite:
  private val today = LocalDate.parse("2026-08-02")
  private def at(s: String) = Instant.parse(s)
  private def personal(v: String) = Some(ProfileState.personal("player-1", v))
  private def attrs(p: ProfileState): Map[String, String] = Attributes.of(p, "spinvibe", today).toMap

  private val bare = ProfileState(playerId = "player-1")

  /** Every source the attributes read, as `fullProps()` (no display name or language: no fact carries them). */
  private val full = ProfileState(
    playerId = "player-1",
    registeredAt = Some(at("2026-01-01T00:00:00Z")),
    email = personal("player@example.com"),
    givenName = personal("Player"),
    familyName = personal("One"),
    dateOfBirth = personal("1990-01-01"),
    country = Some("US"),
    currency = Some("USD"),
    btag = Some("a_123b_456c_"),
    vipTier = Some("gold"),
    lastLoginAt = Some(at("2026-07-01T00:00:00Z")),
    lastDepositAt = Some(at("2026-07-02T00:00:00Z")),
    depositCount = 12,
    totalDepositCents = 120000,
    dailyDepositCount = 3,
    depositDay = Some(today),
    realCents = Some(12345),
    restrictedCents = Some(500),
    limits = Map("DEPOSIT_DAILY" -> Limit(Some("daily"), Some(10000), Some("USD"))),
    status = "active",
    kycStatus = Some("verified"),
    suspectedDuplicate = true,
    consents = Map(
      "email" -> Consent(granted = true, at("2026-06-01T00:00:00Z"), "registration_form"),
      "sms"   -> Consent(granted = true, at("2026-06-02T00:00:00Z"), "registration_form")
    ),
    seen = Map("case/c-0" -> at("2026-05-01T00:00:00Z"), "duplicate" -> at("2026-05-01T00:00:00Z"))
  )

  test("TestBuildIdentifyAttrs_AllFieldsPresent: every FR-008 attribute, by name, in the original's order") {
    val expected = Vector(
      "id"                   -> "player-1",
      "created_at"           -> "1767225600",
      "email"                -> "player@example.com",
      "player_name"          -> "Player",
      "brand_id"             -> "spinvibe",
      "currency"             -> "USD",
      "country"              -> "US",
      "date_of_birth"        -> "1990-01-01",
      "registration_date"    -> "2026-01-01T00:00:00Z",
      "vip_tier"             -> "gold",
      "last_login_at"        -> "2026-07-01T00:00:00Z",
      "last_deposit_at"      -> "2026-07-02T00:00:00Z",
      "total_deposit_amount" -> "1200.00",
      "has_rg_limits"        -> "true",
      "rg_limit_types"       -> "DEPOSIT_DAILY",
      "is_cooling_off"       -> "false",
      "suspected_duplicate"  -> "true",
      "kyc_status"           -> "verified",
      "account_status"       -> "active",
      "deposit_count"        -> "12",
      "daily_deposit_count"  -> "3",
      "real_money"           -> "123.45",
      "bonus_money"          -> "5.00",
      "total_balance"        -> "128.45",
      "email_subscribed"     -> "true",
      "sms_subscribed"       -> "true",
      "email_consent_at"     -> "2026-06-01T00:00:00Z",
      "sms_consent_at"       -> "2026-06-02T00:00:00Z",
      "consent_source"       -> "registration_form",
      "pot_vip"              -> "false",
      "btag"                 -> "a_123b_456c_",
      "marketing_suppressed" -> "false"
    )
    assertEquals(Attributes.of(full, "spinvibe", today), expected)
  }

  test("the seven always sent are present on an empty profile, with the suppression it implies") {
    assertEquals(
      Attributes.of(bare, "spinvibe", today),
      Vector(
        "id"                   -> "player-1",
        "brand_id"             -> "spinvibe",
        "has_rg_limits"        -> "false",
        "daily_deposit_count"  -> "0",
        "email_subscribed"     -> "false",
        "sms_subscribed"       -> "false",
        "pot_vip"              -> "false",
        "marketing_suppressed" -> "true",
        "suppression_reason"   -> "consent_withdrawn"
      )
    )
  }

  test("no attribute is ever an empty string") {
    val blanks = bare.copy(email = personal(""), givenName = personal(""), currency = Some(""), country = Some(""),
      vipTier = Some(""), btag = Some(""), kycStatus = Some(""))
    assert(Attributes.of(blanks, "spinvibe", today).forall(_._2.nonEmpty))
    assertEquals(attrs(blanks).keySet, attrs(bare).keySet)
  }

  test("language, affiliate_id and last_device_os are never sent") {
    for key <- Seq("language", "affiliate_id", "last_device_os") do assert(!attrs(full).contains(key), key)
  }

  test("TestBuildIdentifyAttrs_ConsentAndVipDefaultFalse: no consent fact and no VIP mark read false") {
    val a = attrs(bare.copy(email = personal("player@example.com")))
    for key <- Seq("email_subscribed", "sms_subscribed", "pot_vip") do assertEquals(a(key), "false", key)
  }

  test("TestBuildIdentifyAttrs_ConsentAndVipFalseWhenNotExactlyTrueString: a withdrawn consent reads false") {
    val withdrawn = Consent(granted = false, at("2026-06-01T00:00:00Z"), "player_profile")
    val a         = attrs(bare.copy(consents = Map("email" -> withdrawn, "sms" -> withdrawn)))
    for key <- Seq("email_subscribed", "sms_subscribed", "pot_vip") do assertEquals(a(key), "false", key)
    assert(!a.contains("email_consent_at"))
    assert(!a.contains("sms_consent_at"))
    assertEquals(a("consent_source"), "player_profile")
  }

  test("a consent granted and then withdrawn reads false and keeps the time it was granted, as the original does") {
    val withdrawn = Consent(granted = false, at("2026-06-03T00:00:00Z"), "preferences", Some(at("2026-06-01T00:00:00Z")))
    val a         = attrs(bare.copy(consents = Map("email" -> withdrawn)))
    assertEquals(a("email_subscribed"), "false")
    assertEquals(a("email_consent_at"), "2026-06-01T00:00:00Z")
    assertEquals(a("consent_source"), "preferences")
  }

  test("TestBuildIdentifyAttrs_OmitsUnsetPassthroughFields") {
    val a = attrs(bare.copy(email = personal("player@example.com")))
    for key <- Seq("suspected_duplicate", "kyc_status", "account_status", "deposit_count", "email_consent_at",
        "sms_consent_at", "consent_source", "vip_tier", "is_cooling_off", "total_deposit_amount", "created_at",
        "registration_date")
    do assert(!a.contains(key), key)
  }

  test("TestBuildIdentifyAttrs_RgLimitTypesUsesSessionMinutesKey: the Scala form, in the original's order") {
    val session = bare.copy(limits = Map("SESSION_MINUTES" -> Limit(None, Some(30), None)))
    assertEquals(attrs(session)("has_rg_limits"), "true")
    assertEquals(attrs(session)("rg_limit_types"), "SESSION_MINUTES")
    val several = bare.copy(limits = Map(
      "SESSION_MINUTES" -> Limit(None, Some(30), None),
      "LOSS_WEEKLY"     -> Limit(Some("weekly"), Some(1000), Some("EUR")),
      "DEPOSIT_DAILY"   -> Limit(Some("daily"), Some(1000), Some("EUR"))
    ))
    assertEquals(attrs(several)("rg_limit_types"), "DEPOSIT_DAILY,LOSS_WEEKLY,SESSION_MINUTES")
  }

  test("TestBuildIdentifyAttrs_AffiliateFields: btag is sent, affiliate_id never") {
    val a = attrs(bare.copy(btag = Some("a_123b_456c_")))
    assertEquals(a("btag"), "a_123b_456c_")
    assert(!a.contains("affiliate_id"))
  }

  test("player_name is the given name, else the address before @") {
    assertEquals(attrs(bare.copy(email = personal("jo@example.com")))("player_name"), "jo")
    assertEquals(attrs(bare.copy(email = personal("jo@example.com"), givenName = personal("Joanna")))("player_name"), "Joanna")
    assert(!attrs(bare).contains("player_name"))
  }

  test("an erased profile sends no email, name or date of birth") {
    val subject = ProfileState.subject("player-1")
    val erased = full.copy(email = Some(Personal.Erased(subject)), givenName = Some(Personal.Erased(subject)),
      familyName = Some(Personal.Erased(subject)), dateOfBirth = Some(Personal.Erased(subject)), erased = true)
    val a = attrs(erased)
    for key <- Seq("email", "player_name", "date_of_birth") do assert(!a.contains(key), key)
    assertEquals(a("id"), "player-1")
  }

  test("is_cooling_off: true while a cooling-off case is open, false once a case has been seen") {
    val open = bare.copy(cases = Map("c-1" -> RgCase("cool_off", Some(at("2026-08-09T00:00:00Z")))))
    assertEquals(attrs(open)("is_cooling_off"), "true")
    assertEquals(attrs(bare.copy(seen = Map("case/c-0" -> at("2026-08-01T00:00:00Z"))))("is_cooling_off"), "false")
  }

  test("marketing_suppressed and suppression_reason follow the CRM's suppression") {
    val excluded = full.copy(cases = Map("c-1" -> RgCase("self_exclusion", None)))
    assertEquals(attrs(excluded)("marketing_suppressed"), "true")
    assertEquals(attrs(excluded)("suppression_reason"), "self_excluded")
    assert(!attrs(full).contains("suppression_reason"))
  }

  test("created_at is the registration as Unix seconds") {
    assertEquals(attrs(bare.copy(registeredAt = Some(at("2026-08-02T10:51:00.123Z"))))("created_at"), "1785667860")
  }

  // ── daily_deposit_count_test.go ─────────────────────────────────────────

  test("TestBuildIdentifyAttrs_DailyDepositCountCurrentDay") {
    assertEquals(attrs(bare.copy(dailyDepositCount = 3, depositDay = Some(today)))("daily_deposit_count"), "3")
  }

  test("TestBuildIdentifyAttrs_DailyDepositCountStaleDayReadsZero") {
    val p = bare.copy(dailyDepositCount = 3, depositDay = Some(LocalDate.parse("2020-01-01")))
    assertEquals(attrs(p)("daily_deposit_count"), "0")
  }

  test("TestBuildIdentifyAttrs_DailyDepositCountAlwaysPresent") {
    val a = attrs(bare.copy(email = personal("p@example.com")))
    assertEquals(a.get("daily_deposit_count"), Some("0"))
    assert(!a.contains("vip_tier"))
  }

  test("TestBuildIdentifyAttrs_TotalDepositsNotSent") {
    val a = attrs(bare.copy(depositCount = 12))
    assertEquals(a("deposit_count"), "12")
    assert(!a.contains("total_deposits"))
  }

  test("TestMajorUnits_ConvertsFromCents") {
    val cases = Seq(10000L -> "100.00", 1L -> "0.01", 0L -> "0.00", 2550L -> "25.50", 7500L -> "75.00", -500L -> "-5.00")
    for (cents, want) <- cases do assertEquals(Attributes.majorUnits(cents), want)
  }

  test("TestBuildIdentifyAttrs_BalancesFromWalletTwin") {
    val a = attrs(bare.copy(currency = Some("EUR"), realCents = Some(12345), restrictedCents = Some(500)))
    assertEquals((a("real_money"), a("bonus_money"), a("total_balance")), ("123.45", "5.00", "128.45"))
  }

  test("TestBuildIdentifyAttrs_BalancesOmittedWhenWalletUnavailable") {
    val a = attrs(bare.copy(currency = Some("EUR")))
    for key <- Seq("real_money", "bonus_money", "total_balance") do assert(!a.contains(key), key)
  }

  test("TestBuildIdentifyAttrs_ZeroBalanceStillSent") {
    val a = attrs(bare.copy(realCents = Some(0), restrictedCents = Some(0)))
    assertEquals((a("real_money"), a("bonus_money"), a("total_balance")), ("0.00", "0.00", "0.00"))
  }

  test("TestBuildIdentifyAttrs_TotalBalanceOmittedWhenOnlyOneHalfReadable") {
    val a = attrs(bare.copy(realCents = Some(2500)))
    assertEquals(a("real_money"), "25.00")
    assert(!a.contains("bonus_money"))
    assert(!a.contains("total_balance"))
  }

  test("TestSumMajorUnits_SumsCentsBeforeConverting") {
    assertEquals(attrs(bare.copy(realCents = Some(12345), restrictedCents = Some(500)))("total_balance"), "128.45")
    assertEquals(attrs(bare.copy(realCents = Some(0), restrictedCents = Some(0)))("total_balance"), "0.00")
  }

  test("total_deposit_amount is the lifetime total in major units, once a deposit is seen") {
    assertEquals(attrs(bare.copy(depositCount = 1, totalDepositCents = 2550))("total_deposit_amount"), "25.50")
  }

  // ── encodeAttrs ─────────────────────────────────────────────────────────

  test("FR-003's nine numeric attributes") {
    assertEquals(
      Attributes.numeric,
      Set("created_at", "deposit_count", "daily_deposit_count", "real_money", "bonus_money", "total_balance",
        "total_deposit_amount", "amount", "session_duration_minutes")
    )
  }

  test("TestEncodeAttrs_NumericAttributesUnquoted") {
    assertEquals(Attributes.number("deposit_count", "1"), Some(BigDecimal(1)))
    assertEquals(Attributes.number("daily_deposit_count", "2"), Some(BigDecimal(2)))
    assertEquals(Attributes.number("total_balance", "128.45"), Some(BigDecimal("128.45")))
  }

  test("TestEncodeAttrs_MarshalsAsJSONNumbers: exact, so 100.00 stays 100.00 and 10 compares above 3") {
    assertEquals(Attributes.number("total_deposit_amount", "100.00").map(_.bigDecimal.toPlainString), Some("100.00"))
    assert(Attributes.number("deposit_count", "10").exists(_ > 3))
    assertEquals(Attributes.number("created_at", "1785682260"), Some(BigDecimal(1785682260L)))
  }

  test("TestEncodeAttrs_NonAllowlistedStayStrings") {
    for (k, v) <- Seq("player_name" -> "12345", "transaction_id" -> "0099", "id" -> "42") do
      assertEquals(Attributes.number(k, v), None, k)
  }

  test("TestEncodeAttrs_UnparseableFallsBackToString") {
    assertEquals(Attributes.number("deposit_count", "not-a-number"), None)
  }

  test("TestEncodeAttrs_AlwaysProducesValidJSON: no Inf or NaN becomes a number") {
    assertEquals(Attributes.number("amount", "Inf"), None)
    assertEquals(Attributes.number("daily_deposit_count", "NaN"), None)
    assertEquals(Attributes.number("deposit_count", ""), None)
    assertEquals(Attributes.number("created_at", "0x10"), None)
  }

  test("every numeric attribute the identify sends parses as a number") {
    for (k, v) <- Attributes.of(full, "spinvibe", today) if Attributes.numeric.contains(k) do
      assert(Attributes.number(k, v).isDefined, s"$k = $v")
  }
