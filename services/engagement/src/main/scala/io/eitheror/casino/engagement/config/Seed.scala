package io.eitheror.casino.engagement.config

/**
 * The seed (research R12): one identify and one track per trigger for the person `_seed_<slug>`, so a
 * brand's Customer.io workspace knows every event and attribute before a campaign is written. The
 * triggers, attributes and data are the original's (`CrmEvent`, `CustomerIOConfigRoutes.seedAttributes`
 * and `seedData`), in its order. A trigger is the configuration's key (`DepositConfirmed`); its event is
 * the name Customer.io is sent (`deposit_confirmed`).
 *
 * Thirty-two, as the original's enum now holds: it added `KycAwaitingDeposit` (2026-11-04) and
 * `BankAccountAdded` (2026-11-06) after the spec was written, and both are taken (research, *Found during
 * implementation*); no fact produces either yet, so only an operator's seed and configuration name them.
 */
object Seed:

  /** The thirty-two `CrmEvent` keys and their event names, in the enum's order. */
  val events: Vector[(String, String)] = Vector(
    "PlayerRegistered"           -> "player_registered",
    "DepositConfirmed"           -> "deposit_confirmed",
    "PaymentFailed"              -> "payment_failed",
    "BonusGranted"               -> "bonus_granted",
    "SelfExclusionActivated"     -> "self_exclusion_activated",
    "CoolingOffSet"              -> "cooling_off_set",
    "KycApproved"                -> "kyc_approved",
    "KycRejected"                -> "kyc_rejected",
    "KycDocumentAccepted"        -> "kyc_document_accepted",
    "KycDocumentDeclined"        -> "kyc_document_declined",
    "KycAwaitingDeposit"         -> "kyc_awaiting_deposit",
    "PlayerLoggedIn"             -> "player_logged_in",
    "GameSessionStarted"         -> "game_session_started",
    "GameSessionEnded"           -> "game_session_ended",
    "FirstDepositConfirmed"      -> "first_deposit_confirmed",
    "WageringRequirementMet"     -> "wagering_requirement_met",
    "BonusCancelled"             -> "bonus_cancelled",
    "WithdrawalFailed"           -> "withdrawal_failed",
    "DepositLimitSet"            -> "deposit_limit_set",
    "LossLimitSet"               -> "loss_limit_set",
    "SessionLimitSet"            -> "session_limit_set",
    "RgLimitLoosened"            -> "rg_limit_loosened",
    "EmailChangeVerification"    -> "email_change_verification",
    "EmailChangeNotification"    -> "email_change_notification",
    "PasswordChangeVerification" -> "password_change_verification",
    "PasswordChangeNotification" -> "password_change_notification",
    "EmailAddressVerification"   -> "email_address_verification",
    "EmailPrimaryChanged"        -> "email_primary_changed",
    "PromoCodeEntered"           -> "promo_code_entered",
    "PasswordResetRequest"       -> "password_reset_request",
    "ConsentUpdated"             -> "consent_updated",
    "BankAccountAdded"           -> "bank_account_added"
  )

  val triggers: Vector[String] = events.map(_._1)

  private val eventNames = events.toMap

  /** The event Customer.io is sent for a trigger key. */
  def eventName(trigger: String): Option[String] = eventNames.get(trigger)

  def person(slug: String): String = s"_seed_$slug"

  /**
   * One trigger's calls: `attributes` identify the person, and the track sends `tracked`, the
   * attributes with the trigger's `data` over them (the original's `attrs ++ seedData(trigger)`).
   */
  final case class Pair(trigger: String, event: String, attributes: Vector[(String, String)], data: Vector[(String, String)]):
    def tracked: Vector[(String, String)] =
      val keys = data.map(_._1).toSet
      attributes.filterNot((k, _) => keys(k)) ++ data

  def pairs(slug: String): Vector[Pair] =
    val attrs = attributes(slug)
    events.map((trigger, event) => Pair(trigger, event, attrs, data(trigger)))

  def attributes(slug: String): Vector[(String, String)] = Vector(
    "email"                -> s"seed@$slug.example.com",
    "player_name"          -> "Seed Player",
    "brand_id"             -> slug,
    "currency"             -> "USD",
    "country"              -> "GB",
    "language"             -> "en",
    "date_of_birth"        -> "1990-01-01",
    "registration_date"    -> "2024-01-01T00:00:00Z",
    "vip_tier"             -> "standard",
    "last_login_at"        -> "2024-06-01T12:00:00Z",
    "last_deposit_at"      -> "2024-06-01T12:00:00Z",
    "deposit_count"        -> "5",
    "daily_deposit_count"  -> "0",
    "real_money"           -> "125.00",
    "bonus_money"          -> "10.00",
    "total_balance"        -> "135.00",
    "total_deposit_amount" -> "500.00",
    "has_rg_limits"        -> "false",
    "is_cooling_off"       -> "false"
  )

  private val deposit = Vector("amount" -> "100.00", "currency" -> "USD", "transaction_id" -> "seed-txn-001")

  def data(trigger: String): Vector[(String, String)] = trigger match
    case "PlayerRegistered"      => Vector("currency" -> "USD")
    case "DepositConfirmed"      => deposit
    case "FirstDepositConfirmed" => deposit
    case "PaymentFailed"         => deposit :+ ("failure_reason" -> "Payment declined")
    case "BonusGranted"          => Vector("bonus_name" -> "Welcome Bonus", "amount" -> "50.00", "currency" -> "USD")
    case "BonusCancelled"        => Vector("clawback_amount" -> "20.00", "currency" -> "USD")
    case "WithdrawalFailed" =>
      Vector(
        "amount"         -> "100.00",
        "currency"       -> "USD",
        "transaction_id" -> "seed-withdrawal-001",
        "failure_reason" -> "Payout rejected by provider"
      )
    case "GameSessionStarted" => Vector("game_id" -> "seed-game-001", "session_id" -> "seed-session-001")
    case "GameSessionEnded" =>
      Vector("game_id" -> "seed-game-001", "session_id" -> "seed-session-001", "session_duration_minutes" -> "30")
    case "DepositLimitSet"         => Vector("limit_type" -> "DEPOSIT_DAILY", "limit_value" -> "200")
    case "LossLimitSet"            => Vector("limit_type" -> "LOSS_DAILY", "limit_value" -> "100")
    case "SessionLimitSet"         => Vector("limit_type" -> "SESSION_MINUTES", "limit_value" -> "120")
    case "RgLimitLoosened"         => Vector("limit_type" -> "DEPOSIT_DAILY")
    case "CoolingOffSet"           => Vector("cooling_off_period" -> "2024-12-31T00:00:00Z")
    case "KycRejected"             => Vector("reason" -> "document_mismatch")
    case "KycDocumentAccepted"     => Vector("doc_type" -> "identity")
    case "KycDocumentDeclined"     => Vector("doc_type" -> "identity", "reason" -> "document_unclear")
    case "KycAwaitingDeposit"      => Vector("doc_type" -> "payment_method")
    case "EmailChangeVerification" => Vector("verification_link" -> "https://example.com/verify?token=seed")
    case "EmailChangeNotification" => Vector("new_email" -> "newemail@example.com")
    case "PasswordChangeVerification" =>
      Vector("verification_link" -> "https://example.com/verify-password-change?token=seed")
    case "PasswordChangeNotification" => Vector("changed_at" -> "2024-06-01T12:00:00Z")
    case "EmailAddressVerification" =>
      Vector(
        "verification_link" -> "https://example.com/verify-email-address?token=seed&playerId=seed-player",
        "address"           -> "seed@example.com"
      )
    case "EmailPrimaryChanged" => Vector("new_primary" -> "new@example.com", "changed_at" -> "2024-06-01T12:00:00Z")
    case "PromoCodeEntered"    => Vector("promo_code" -> "WELCOME10")
    case "PasswordResetRequest" =>
      Vector("reset_link" -> "https://example.com/reset-password?token=seed&playerId=seed-player")
    case "ConsentUpdated" =>
      Vector("email_subscribed" -> "true", "sms_subscribed" -> "false", "consent_source" -> "player_profile")
    case "BankAccountAdded" => Vector("account_name" -> "John Smith", "masked_iban" -> "GB29...6819")
    // PlayerLoggedIn, WageringRequirementMet, SelfExclusionActivated, KycApproved, and any other key
    case _ => Vector.empty
