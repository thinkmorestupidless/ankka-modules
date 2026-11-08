package io.eitheror.casino.engagement.events

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.profile.RecentPayment

import java.time.Instant
import java.time.temporal.ChronoUnit.{MINUTES, SECONDS}

/**
 * contracts/facts.md row by row, then `G/handler_test.go`'s candidate and single-mode cases and
 * `G/payment_failed_test.go` case by case (each named in its title).
 *
 * Replaced by the send claim, not ported: the freshness cases (`TestIsFresh_ToleratesTimestampSlightlyInTheFuture`,
 * `TestIsFresh_FailsClosed`, `TestPlayerEventCandidates_DepositOutsideWindowIsSuppressed`,
 * `_RegistrationInsideWindowStillDispatches`, `_RegistrationOutsideWindowIsSuppressed`,
 * `_UnrelatedTwinWriteDispatchesNothing`, `_OngoingCoolingOffIsNotReAnnounced`, `_SelfExclusion`'s old,
 * missing and unparseable timestamps, `_KycTransitions`' old ones and its status-and-timestamp disagreement,
 * `_WeeksOldRegistrationDoesNotReRegisterOnLogin`'s re-registration). A fact is the transition, so only
 * the fact's own events are candidates, and the claim keyed `<event>/<fact id>` makes each once.
 */
class EventsSuite extends munit.FunSuite:
  private val at      = Instant.parse("2026-11-06T12:00:00Z")
  private val Eur     = Currency.unsafe("EUR")
  private def cents(n: Long) = MinorUnits.unsafe(n)
  private val zero    = SignedMinorUnits(0)

  private val none = Events.Context(None, 0, 0, _ => None)
  private def ctx(registeredAt: Option[Instant] = None, before: Long = 0, after: Long = 0,
      payments: Map[String, RecentPayment] = Map.empty) = Events.Context(registeredAt, before, after, payments.get)

  private def of(fact: Fact, c: Events.Context = none) = Events.of("f-1", fact, c)
  private def names(fact: Fact, c: Events.Context = none) = of(fact, c).map(_.name)
  private def only(fact: Fact, c: Events.Context = none): Event =
    val es = of(fact, c)
    assertEquals(es.size, 1, es)
    es.head

  private def registered(btag: Option[String] = None, currency: Option[String] = Some("EUR")) =
    PlayerRegistered("p-1", at, btag, None, None, Some("MT"), currency, None, at)
  private def limit(tpe: String, period: Option[String], value: Option[Long], cause: String) =
    RgLimitApplied("p-1", tpe, period, value, cause, at, Some("EUR"))
  private def reviewed(decision: String, reason: Option[String] = None) =
    PlayerKycDocumentReviewed("p-1", "d-1", "proof_of_address", decision, reason, "op-1", at)
  private def consent(channel: String, source: String = "preferences") = PlayerConsentChanged("p-1", channel, consented = true, source, at)
  private def deposit = DepositConfirmed("p-1", Eur, "tx-1", cents(5000), "pay-ref-1", 1, zero, cents(0), at)
  private def initiated(terminal: String = "CARD_VISA") = Map("pay-1" -> RecentPayment("pay-1", 1250, "EUR", "connecx", terminal))
  private def failed(code: String = "DECLINED", reason: String = "Payment declined") =
    PaymentFailed("pay-1", "p-1", "connecx", code, reason, at)
  private def status(previous: String, next: String) = (previous, next, "operator", Some("op-1"), Some("why"), at)

  // ── contracts/facts.md, one test per row ─────────────────────────────────

  test("player_registered → player_registered with currency, and btag when presented"):
    assertEquals(only(registered()), Event("player_registered", "player_registered/f-1", Vector("currency" -> "EUR")))
    assertEquals(of(registered(Some("a_1b_2c_"))).head.data, Vector("currency" -> "EUR", "btag" -> "a_1b_2c_"))

  test("player_registered with a btag → affiliate_attributed after player_registered, data btag"):
    val es = of(registered(Some("a_1b_2c_")))
    assertEquals(es.map(_.name), Vector("player_registered", "affiliate_attributed"))
    assertEquals(es(1), Event("affiliate_attributed", "affiliate_attributed/f-1", Vector("btag" -> "a_1b_2c_")))
    assertEquals(names(registered(Some(""))), Vector("player_registered"))

  test("player_logged_in → player_logged_in unless within two minutes of registration, a negative gap counting"):
    val login = PlayerLoggedIn("p-1", "s-1", at)
    assertEquals(only(login, ctx(Some(at.minus(3, MINUTES)))), Event("player_logged_in", "player_logged_in/f-1", Vector.empty))
    assertEquals(names(login, ctx(Some(at.minus(2, MINUTES)))), Vector("player_logged_in"))
    assertEquals(names(login, ctx(Some(at.minus(119, SECONDS)))), Vector.empty)
    assertEquals(names(login, ctx(Some(at.plus(5, MINUTES)))), Vector.empty)
    assertEquals(names(login, ctx(None)), Vector("player_logged_in"))

  test("player_self_excluded → self_exclusion_activated"):
    val f = PlayerSelfExcluded("p-1", "active", "self_excluded", "player", None, None, at, None, at)
    assertEquals(only(f), Event("self_exclusion_activated", "self_exclusion_activated/f-1", Vector.empty))

  test("rg_case_opened cool_off → cooling_off_set, cooling_off_period the close; other types and no close make none"):
    val until = Instant.parse("2026-12-31T00:00:00Z")
    assertEquals(only(RgCaseOpened("p-1", "c-1", "cool_off", "player", Some(until), at)).data,
      Vector("cooling_off_period" -> "2026-12-31T00:00:00Z"))
    assertEquals(names(RgCaseOpened("p-1", "c-1", "self_exclusion", "player", Some(until), at)), Vector.empty)
    assertEquals(names(RgCaseOpened("p-1", "c-1", "cool_off", "player", None, at)), Vector.empty)

  test("rg_limit_applied set or lowered → deposit_, loss_ or session_limit_set by type, limit_type <TYPE>_<PERIOD>"):
    assertEquals(only(limit("DEPOSIT_DAILY", Some("daily"), Some(20000), "set")),
      Event("deposit_limit_set", "deposit_limit_set/f-1", Vector("limit_type" -> "DEPOSIT_DAILY", "limit_value" -> "200.00")))
    assertEquals(only(limit("LOSS_WEEKLY", Some("weekly"), Some(10050), "lowered")).data,
      Vector("limit_type" -> "LOSS_WEEKLY", "limit_value" -> "100.50"))
    assertEquals(only(limit("SESSION_MINUTES", None, Some(120), "set")),
      Event("session_limit_set", "session_limit_set/f-1", Vector("limit_type" -> "SESSION_MINUTES", "limit_value" -> "120")))
    assertEquals(only(limit("deposit", Some("monthly"), Some(5000), "set")).data,
      Vector("limit_type" -> "DEPOSIT_MONTHLY", "limit_value" -> "50.00"))
    assertEquals(names(limit("DEPOSIT_DAILY", Some("daily"), Some(30000), "applied")), Vector.empty)

  test("rg_limit_loosening_requested → rg_limit_loosened with limit_type and limit_value"):
    val f = RgLimitLooseningRequested("p-1", "DEPOSIT_DAILY", Some("daily"), Some(50000), Some("EUR"), at.plus(1, java.time.temporal.ChronoUnit.DAYS), at)
    assertEquals(only(f), Event("rg_limit_loosened", "rg_limit_loosened/f-1", Vector("limit_type" -> "DEPOSIT_DAILY", "limit_value" -> "500.00")))
    assertEquals(only(f.copy(value = None)).data, Vector("limit_type" -> "DEPOSIT_DAILY"))

  test("rg_limit_applied removed → rg_limit_loosened with limit_type only"):
    assertEquals(only(limit("LOSS_DAILY", Some("daily"), None, "removed")),
      Event("rg_limit_loosened", "rg_limit_loosened/f-1", Vector("limit_type" -> "LOSS_DAILY")))

  test("player_kyc_verified → kyc_approved"):
    assertEquals(only(PlayerKycVerified("p-1", "pending", "verified", "operator", None, None, at)).name, "kyc_approved")

  test("player_kyc_rejected → kyc_rejected"):
    assertEquals(only(PlayerKycRejected("p-1", "pending", "rejected", "operator", None, None, at)).name, "kyc_rejected")

  test("player_kyc_document_reviewed approved → kyc_document_accepted, doc_type the category"):
    assertEquals(only(reviewed("approved")),
      Event("kyc_document_accepted", "kyc_document_accepted/f-1", Vector("doc_type" -> "proof_of_address")))

  test("player_kyc_document_reviewed declined → kyc_document_declined, doc_type and reason the reason code"):
    val e = only(reviewed("declined", Some("blurry")))
    assertEquals(e, Event("kyc_document_declined", "kyc_document_declined/f-1", Vector("doc_type" -> "proof_of_address", "reason" -> "blurry")))
    // kyc writes the outcome `rejected` (services/kyc record.Outcome); it is the same decline.
    assertEquals(only(reviewed("rejected", Some("blurry"))), e)
    assertEquals(only(reviewed("declined")).data, Vector("doc_type" -> "proof_of_address"))

  test("player_consent_changed for email or sms → consent_updated; messages makes none, nor a registration's consent"):
    assertEquals(only(consent("email")), Event("consent_updated", "consent_updated/f-1", Vector.empty))
    assertEquals(names(consent("sms")), Vector("consent_updated"))
    assertEquals(names(consent("messages")), Vector.empty)
    assertEquals(names(consent("email", "registration")), Vector.empty)

  test("game_session_started → game_session_started with game_id and session_id"):
    assertEquals(only(GameSessionStarted("p-1", "gs-1", "g-1", "ps-1", at)),
      Event("game_session_started", "game_session_started/f-1", Vector("game_id" -> "g-1", "session_id" -> "gs-1")))

  test("game_session_ended → game_session_ended with game_id, session_id and session_duration_minutes"):
    assertEquals(only(GameSessionEnded("p-1", "gs-1", "g-1", 42, at)).data,
      Vector("game_id" -> "g-1", "session_id" -> "gs-1", "session_duration_minutes" -> "42"))

  test("deposit_confirmed → deposit_confirmed with amount, currency, transaction_id the payment reference"):
    assertEquals(only(deposit, ctx(before = 3, after = 4)),
      Event("deposit_confirmed", "deposit_confirmed/f-1", Vector("amount" -> "50.00", "currency" -> "EUR", "transaction_id" -> "pay-ref-1")))

  test("deposit_confirmed when the lifetime count becomes 1 → first_deposit_confirmed after it, the same data"):
    val es = of(deposit, ctx(before = 0, after = 1))
    assertEquals(es.map(_.key), Vector("deposit_confirmed/f-1", "first_deposit_confirmed/f-1"))
    assertEquals(es(1).data, es(0).data)
    // A repeat of the first deposit, applied already, leaves the count at 1: no second announcement.
    assertEquals(names(deposit, ctx(before = 1, after = 1)), Vector("deposit_confirmed"))

  test("payment.failed → payment_failed keyed by the payment, amount and currency from payment.initiated"):
    assertEquals(only(failed(), ctx(payments = initiated())), Event("payment_failed", "payment_failed/pay-1", Vector(
      "amount" -> "12.50", "currency" -> "EUR", "transaction_id" -> "pay-1", "failure_reason" -> "Payment declined",
      "provider" -> "connecx", "payment_method" -> "CARD_VISA", "reason" -> "card_declined", "provider_code" -> "DECLINED")))

  test("payment.expired → payment_failed keyed by the payment, failure_reason expired, no provider_code"):
    assertEquals(only(PaymentExpired("pay-1", "p-1", "t365", at), ctx(payments = initiated("CRYPTO_TRC20"))),
      Event("payment_failed", "payment_failed/pay-1", Vector(
        "amount" -> "12.50", "currency" -> "EUR", "transaction_id" -> "pay-1", "failure_reason" -> "expired",
        "provider" -> "t365", "payment_method" -> "CRYPTO_TRC20", "reason" -> "window_expired")))

  test("payment_failed with no remembered payment.initiated: each field only when non-empty"):
    assertEquals(only(failed(code = "", reason = "")).data,
      Vector("transaction_id" -> "pay-1", "provider" -> "connecx", "reason" -> "provider_error"))

  test("withdrawal_failed → withdrawal_failed with amount, currency, transaction_id the payout, failure_reason"):
    assertEquals(only(WithdrawalFailed("p-1", "po-1", cents(2500), Eur, "Bank rejected", at)),
      Event("withdrawal_failed", "withdrawal_failed/f-1", Vector(
        "amount" -> "25.00", "currency" -> "EUR", "transaction_id" -> "po-1", "failure_reason" -> "Bank rejected")))

  test("bonus_granted and free_spins_granted → bonus_granted, bonus_name the offer id, reward_id the bonus"):
    assertEquals(only(BonusGranted("p-1", "b-1", "Welcome", "offer-1", "deposit_match", cents(5000), Eur, None, at)),
      Event("bonus_granted", "bonus_granted/f-1", Vector(
        "bonus_name" -> "offer-1", "amount" -> "50.00", "currency" -> "EUR", "reward_id" -> "b-1")))
    assertEquals(only(FreeSpinsGranted("p-1", "b-2", "Spins", "offer-2", "free_spins", 10, Vector("g-1"), at)),
      Event("bonus_granted", "bonus_granted/f-1", Vector("bonus_name" -> "offer-2", "reward_id" -> "b-2")))

  test("bonus_cancelled and bonus_forfeited → bonus_cancelled with bonus_name, amount and currency"):
    assertEquals(only(BonusCancelled("p-1", "b-1", "Welcome", "offer-1", "deposit_match", cents(1234), Eur, at)),
      Event("bonus_cancelled", "bonus_cancelled/f-1", Vector("bonus_name" -> "offer-1", "amount" -> "12.34", "currency" -> "EUR")))
    assertEquals(only(BonusForfeited("p-1", "b-1", "Welcome", "offer-1", "deposit_match", "expired", at, Some(cents(99)), Some(Eur))).data,
      Vector("bonus_name" -> "offer-1", "amount" -> "0.99", "currency" -> "EUR"))
    assertEquals(only(BonusForfeited("p-1", "b-1", "Welcome", "offer-1", "deposit_match", "expired", at)).data,
      Vector("bonus_name" -> "offer-1"))

  test("bonus_completed → wagering_requirement_met with bonus_name (the fact carries no amount)"):
    assertEquals(only(BonusCompleted("p-1", "b-1", "Welcome", "offer-1", "deposit_match", at)),
      Event("wagering_requirement_met", "wagering_requirement_met/f-1", Vector("bonus_name" -> "offer-1")))

  test("bonus_code_entered → promo_code_entered with promo_code, whatever the outcome"):
    assertEquals(only(BonusCodeEntered("p-1", "WELCOME10", at, Some("invalid"))),
      Event("promo_code_entered", "promo_code_entered/f-1", Vector("promo_code" -> "WELCOME10")))

  test("FR-010's families (an identify when they move an attribute, ProfileSuite) make no event"):
    val (pv, nv, actor, actorId, reason, when) = status("active", "closed")
    val identifying = Vector[Fact](
      VipTierChanged("p-1", None, "gold", "op-1", at),
      PlayerEmailChanged("p-1", Personal("a@example.com"), at),
      PlayerProfileUpdated("p-1", Vector("city"), None, None, None, None, None, None, None, None, at),
      PlayerDuplicateCheckEnded("p-1", "weak", at),
      PlayerClosed("p-1", pv, nv, actor, actorId, reason, when),
      PlayerSuspended("p-1", pv, nv, actor, actorId, reason, when),
      PlayerReinstated("p-1", pv, nv, actor, actorId, reason, when),
      PlayerReopened("p-1", pv, nv, actor, actorId, reason, when),
      RgCaseClosed("p-1", "c-1", "cool_off", "expired", at),
      consent("messages"),
      AdminAdjusted("p-1", Eur, "tx-2", SignedMinorUnits(-500), "real", "goodwill", "op-1", zero, cents(0), at),
      HoldReleased("p-1", Eur, "tx-3", "w-1", cents(100), zero, cents(0), at)
    )
    identifying.foreach(f => assertEquals(names(f), Vector.empty, f))

  test("profile-only families make no event"):
    val quiet = Vector[Fact](
      PaymentInitiated("pay-1", "p-1", "connecx", "CARD_VISA", cents(1250), Eur, at),
      PaymentCaptured("pay-1", "p-1", "connecx", cents(1250), Eur, at),
      PlayerKycSubmitted("p-1", "d-1", "id", at), PlayerKycPending("p-1", "none", "pending", at),
      PlayerKycReviewRequired("p-1", "threshold", at), PlayerLoggedOut("p-1", "s-1", "logout", at),
      PlayerSessionEnded("p-1", "s-1", "logout", at), WithdrawalRequested("p-1", "po-1", cents(100), Eur, "t-1", at),
      PlayerFraudFlagged("p-1", "fc-1", "chargeback", at)
    )
    quiet.foreach(f => assertEquals(names(f), Vector.empty, f))

  // ── G/handler_test.go: candidates and single mode ───────────────────────

  test("TestPlayerEventCandidates_WeeksOldRegistrationDoesNotReRegisterOnLogin: a login long after registering is one event"):
    assertEquals(names(PlayerLoggedIn("p-1", "s-1", at), ctx(Some(at.minus(21 * 24 * 60, MINUTES)))), Vector("player_logged_in"))

  test("TestPlayerEventCandidates_FreshRegistrationDispatches: exactly player_registered, the currency carried"):
    val e = only(registered(currency = Some("ZAR")))
    assertEquals((e.name, e.data), ("player_registered", Vector("currency" -> "ZAR")))

  test("TestPlayerEventCandidates_RegistrationCarriesBtag: btag carried, and no empty btag key without one"):
    assertEquals(of(registered(Some("a_123b_456c_"))).head.data.toMap.get("btag"), Some("a_123b_456c_"))
    assertEquals(of(registered()).head.data.toMap.get("btag"), None)

  test("TestPlayerEventCandidates_FreshDepositDispatches: the deposit's own data travels with the event"):
    assertEquals(only(deposit, ctx(before = 2, after = 3)).data.toMap,
      Map("amount" -> "50.00", "currency" -> "EUR", "transaction_id" -> "pay-ref-1"))

  test("TestPlayerEventCandidates_SelfExclusion: a self-exclusion dispatches"):
    assertEquals(names(PlayerSelfExcluded("p-1", "active", "self_excluded", "player", None, None, at, None, at)),
      Vector("self_exclusion_activated"))

  test("TestPlayerEventCandidates_CoolingOffKeyedOnSetAtNotUntil: two cool-offs to one date are two keys, the end as data"):
    val until  = Some(Instant.parse("2026-12-31T00:00:00Z"))
    val first  = Events.of("f-1", RgCaseOpened("p-1", "c-1", "cool_off", "player", until, at.minus(2, MINUTES)), none)
    val second = Events.of("f-2", RgCaseOpened("p-1", "c-2", "cool_off", "player", until, at.minus(1, MINUTES)), none)
    assertNotEquals(first.head.key, second.head.key)
    assertEquals(first.head.data, Vector("cooling_off_period" -> "2026-12-31T00:00:00Z"))

  test("TestPlayerEventCandidates_KycTransitions: a fresh approval and a fresh rejection"):
    assertEquals(names(PlayerKycVerified("p-1", "pending", "verified", "operator", None, None, at)), Vector("kyc_approved"))
    assertEquals(names(PlayerKycRejected("p-1", "pending", "rejected", "operator", None, None, at)), Vector("kyc_rejected"))

  test("TestPlayerEventCandidates_AutoLoginAtRegistrationIsStillSuppressed: a login 10 s after registering makes none"):
    assertEquals(names(PlayerLoggedIn("p-1", "s-1", at), ctx(Some(at.minus(10, SECONDS)))), Vector.empty)
    assertEquals(names(registered()), Vector("player_registered"))

  test("TestResolveSingleEvent_PaymentFailedDedupsPerTransaction: two failures by one player, two keys"):
    val a = only(failed(), none).key
    val b = Events.of("f-2", failed().copy(paymentId = "pay-2"), none).head.key
    assertNotEquals(a, b)
    assertEquals(a, "payment_failed/pay-1")

  test("TestResolveSingleEvent_PaymentFailedIsDedupedForSameTransaction: a redelivered failure, one key"):
    assertEquals(Events.of("f-1", failed(), none).head.key, Events.of("f-9", failed(), none).head.key)
    // An expiry after a failure of the same payment is the same send.
    assertEquals(Events.of("f-3", PaymentExpired("pay-1", "p-1", "connecx", at), none).head.key, "payment_failed/pay-1")

  test("TestResolveSingleEvent_BonusCompletedMapsToWageringRequirementMet"):
    assertEquals(names(BonusCompleted("p-1", "bonus-1", "W", "offer-1", "deposit_match", at)), Vector("wagering_requirement_met"))

  test("TestResolveSingleEvent_BonusCompletedDedupsPerBonusNotPlayer: two completions, two keys"):
    val a = Events.of("f-1", BonusCompleted("p-1", "bonus-1", "W", "offer-1", "deposit_match", at), none).head.key
    val b = Events.of("f-2", BonusCompleted("p-1", "bonus-2", "W", "offer-1", "deposit_match", at), none).head.key
    assertNotEquals(a, b)

  test("TestDispatchSingle_BonusGrantedIncludesRewardIDInAttributes"):
    val e = only(BonusGranted("p-1", "claim-123", "W", "offer-1", "deposit_match", cents(1000), Eur, None, at))
    assertEquals(e.data.toMap.get("reward_id"), Some("claim-123"))

  test("TestDispatchSingle_AmountReachesCIOInMajorUnits: every amount from minor units"):
    assertEquals(only(BonusGranted("p-1", "b1", "W", "o", "t", cents(5000), Eur, None, at)).data.toMap.apply("amount"), "50.00")
    assertEquals(only(BonusCancelled("p-1", "b1", "W", "o", "t", cents(1234), Eur, at)).data.toMap.apply("amount"), "12.34")
    assertEquals(only(WithdrawalFailed("p-1", "w1", cents(2500), Eur, "x", at)).data.toMap.apply("amount"), "25.00")
    assertEquals(only(failed(), ctx(payments = initiated())).data.toMap.apply("amount"), "12.50")

  test("TestDispatchSingle_OmitsRewardIDWhenAbsent: bonus_completed and bonus_cancelled carry no reward_id"):
    assertEquals(only(BonusCompleted("p-1", "bonus-1", "W", "o", "t", at)).data.toMap.get("reward_id"), None)
    assertEquals(only(BonusCancelled("p-1", "b1", "W", "o", "t", cents(1), Eur, at)).data.toMap.get("reward_id"), None)

  test("TestDispatchSingle_PaymentFailedCardDeclineUsesTicketFieldNames: no raw names leak"):
    val data = only(failed(), ctx(payments = initiated("CARD_MASTERCARD"))).data.toMap
    assertEquals(data("reason"), "card_declined")
    assertEquals(data("provider_code"), "DECLINED")
    Seq("scheme", "psp", "failure_code", "crypto_coin", "crypto_network", "received_crypto_amount").foreach(k =>
      assertEquals(data.get(k), None, k))

  test("TestDispatchSingle_PaymentFailedCryptoUnderpaymentReachesTrackPayload: the underpaid code is below_minimum"):
    val data = only(failed("underpaid", "Underpaid: received 95 of 100 expected"), ctx(payments = initiated("USDTRX"))).data.toMap
    assertEquals((data("reason"), data("provider_code"), data("payment_method")), ("below_minimum", "underpaid", "USDTRX"))

  test("TestDispatchSingle_PaymentExpiredMapsToWindowExpired: no provider_code on an expiry"):
    val data = only(PaymentExpired("pay-1", "p-1", "connecx", at), ctx(payments = initiated("CARD_VISA"))).data.toMap
    assertEquals(data("reason"), "window_expired")
    assertEquals(data.get("provider_code"), None)

  test("TestDispatchSingle_PaymentExpiredCryptoIdentifiesMethodAndNetwork: the terminal is the method"):
    val data = only(PaymentExpired("pay-1", "p-1", "t365", at), ctx(payments = initiated("CRYPTO_TRC20"))).data.toMap
    assertEquals((data("reason"), data("payment_method"), data("provider")), ("window_expired", "CRYPTO_TRC20", "t365"))

  test("TestDispatchSingle_NonPaymentTriggerDoesNotGetPaymentFields"):
    val data = only(BonusGranted("p-1", "b-1", "Welcome", "o", "t", cents(1), Eur, None, at)).data.toMap
    Seq("payment_method", "reason", "provider", "provider_code").foreach(k => assertEquals(data.get(k), None, k))

  // ── G/payment_failed_test.go ─────────────────────────────────────────────
  // TestNormalisePaymentMethod's crypto cases (coin and network from the ticker or the scheme) are not ported: no
  // fact carries `crypto_currency`, and the crypto keys are absent (R8). Its card cases decide `card` below.

  test("TestNormalisePaymentMethod: a card scheme is a card; every other method is not"):
    Seq("CARD_VISA", "CARD_MASTERCARD", "CARD_AMEX", "card_visa").foreach(t => assert(Events.card(t), t))
    Seq("INTERAC_ETRANSFER", "BANK_TRANSFER", "WALLETS", "OPEN-BANKING", "", "USDTRX", "USDT", "CRYPTO_TRC20", "CRYPTO_BITCOIN", "CRYPTO")
      .foreach(t => assert(!Events.card(t), t))

  private def reason(code: String = "", text: String = "", card: Boolean = true, expired: Boolean = false) =
    Events.reason(expired, code, text, card)

  test("TestNormaliseFailureReason/explicit expired outcome"):
    assertEquals(reason(expired = true), "window_expired")
  test("TestNormaliseFailureReason/sweeper abandoned"):
    assertEquals(reason(text = "Payment not completed"), "window_expired")
  test("TestNormaliseFailureReason/psp session expired"):
    assertEquals(reason(text = "Payment session expired", card = false), "window_expired")
  test("TestNormaliseFailureReason/raw expired code"):
    assertEquals(reason(code = "expired", text = "Invoice expired"), "window_expired")
  // "received amount recorded" and "underpayment beats declined" rest on `received_crypto_amount`, which no fact carries.
  test("TestNormaliseFailureReason/morphosis underpaid code"):
    assertEquals(reason(code = "underpaid", card = false), "below_minimum")
  test("TestNormaliseFailureReason/underpaid wording"):
    assertEquals(reason(text = "Underpaid: received 95 of 100 expected", card = false), "below_minimum")
  test("TestNormaliseFailureReason/unpriced underpayment"):
    assertEquals(reason(text = "Less crypto arrived than requested and its value was not reported — under review", card = false), "below_minimum")
  test("TestNormaliseFailureReason/cancelled wording"):
    assertEquals(reason(text = "Payment cancelled"), "cancelled")
  test("TestNormaliseFailureReason/unity finance canceled"):
    assertEquals(reason(code = "canceled", text = "User closed the page"), "cancelled")
  test("TestNormaliseFailureReason/card declined"):
    assertEquals(reason(code = "DECLINED", text = "Payment declined"), "card_declined")
  test("TestNormaliseFailureReason/interac declined"):
    assertEquals(reason(text = "Payment declined", card = false), "declined")
  test("TestNormaliseFailureReason/crypto declined"):
    assertEquals(reason(code = "FAILED", text = "Payment declined", card = false), "declined")
  test("TestNormaliseFailureReason/connecx insufficient funds falls through"):
    assertEquals(reason(code = "INSUFFICIENT_FUNDS", text = "Payment declined"), "card_declined")
  test("TestNormaliseFailureReason/unknown code and wording"):
    assertEquals(reason(code = "E1234", text = "Issuer unavailable"), "provider_error")
  test("TestNormaliseFailureReason/nothing at all"):
    assertEquals(reason(card = false), "provider_error")
  test("TestNormaliseFailureReason/expired beats declined"):
    assertEquals(reason(expired = true, text = "Payment declined"), "window_expired")

  test("TestAddPaymentFailedData_AlwaysPopulatesMethodAndReason: reason always; absent inputs leave keys absent"):
    val data = only(PaymentFailed("pay-1", "p-1", "", "", "", at)).data.toMap
    assertEquals(data, Map("transaction_id" -> "pay-1", "reason" -> "provider_error"))

  test("TestAddPaymentFailedData_ExpiredCryptoKeepsMethodAndCoin: an expired crypto attempt keeps its method"):
    val data = only(PaymentExpired("pay-1", "p-1", "cozeni", at), ctx(payments = initiated("USDT"))).data.toMap
    assertEquals((data("payment_method"), data("reason"), data("provider")), ("USDT", "window_expired", "cozeni"))
    assertEquals(data.get("crypto_network"), None)

  // ── Amounts ──────────────────────────────────────────────────────────────

  test("Amounts.major: minor units to two decimals, signed"):
    assertEquals(Amounts.major(1234), "12.34")
    assertEquals(Amounts.major(-5), "-0.05")
    assertEquals(Amounts.major(0), "0.00")
    assertEquals(Amounts.major(Long.MaxValue), "92233720368547758.07")
