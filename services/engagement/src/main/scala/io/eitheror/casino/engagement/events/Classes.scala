package io.eitheror.casino.engagement.events

/**
 * FR-011: every trigger is marketing or service. Marketing is FR-011's list; service is every other event of
 * FR-006 and the seven send-only triggers of FR-014. `identify` (FR-010) is a class of its own.
 */
object Classes:
  val marketing: Set[String] = Set(
    "player_registered", "player_logged_in", "game_session_started", "game_session_ended", "deposit_confirmed",
    "first_deposit_confirmed", "bonus_granted", "bonus_cancelled", "wagering_requirement_met", "promo_code_entered",
    "affiliate_attributed"
  )

  /** FR-014's send route triggers, PascalCase as the brand's trigger table names them. */
  val sendOnly: Set[String] = Set(
    "EmailAddressVerification", "EmailChangeVerification", "EmailChangeNotification", "EmailPrimaryChanged",
    "PasswordChangeVerification", "PasswordChangeNotification", "PasswordResetRequest"
  )

  val service: Set[String] = Set(
    "payment_failed", "withdrawal_failed", "deposit_limit_set", "loss_limit_set", "session_limit_set",
    "rg_limit_loosened", "cooling_off_set", "self_exclusion_activated", "kyc_approved", "kyc_rejected",
    "kyc_document_accepted", "kyc_document_declined", "consent_updated"
  ) ++ sendOnly

  val Identify = "identify"

  def of(trigger: String): Option[SendClass] =
    if marketing(trigger) then Some(SendClass.Marketing)
    else if service(trigger) then Some(SendClass.Service)
    else if trigger == Identify then Some(SendClass.Identify)
    else None
