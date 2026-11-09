package io.eitheror.casino.engagement.events

/** FR-011: every trigger of FR-006 and FR-014 in exactly one class. */
class ClassesSuite extends munit.FunSuite:
  private val fr006 = Vector(
    "player_registered", "affiliate_attributed", "player_logged_in", "game_session_started", "game_session_ended",
    "deposit_confirmed", "first_deposit_confirmed", "payment_failed", "bonus_granted", "bonus_cancelled",
    "wagering_requirement_met", "withdrawal_failed", "deposit_limit_set", "loss_limit_set", "session_limit_set",
    "rg_limit_loosened", "cooling_off_set", "self_exclusion_activated", "kyc_approved", "kyc_rejected",
    "kyc_document_accepted", "kyc_document_declined", "promo_code_entered", "consent_updated"
  )
  private val fr014 = Vector(
    "EmailAddressVerification", "EmailChangeVerification", "EmailChangeNotification", "EmailPrimaryChanged",
    "PasswordChangeVerification", "PasswordChangeNotification", "PasswordResetRequest"
  )
  private val fr011Marketing = Set(
    "player_registered", "player_logged_in", "game_session_started", "game_session_ended", "deposit_confirmed",
    "first_deposit_confirmed", "bonus_granted", "bonus_cancelled", "wagering_requirement_met", "promo_code_entered",
    "affiliate_attributed"
  )

  test("every trigger of FR-006 and FR-014 is in exactly one class"):
    (fr006 ++ fr014).foreach { t =>
      assertEquals(Seq(Classes.marketing(t), Classes.service(t)).count(identity), 1, t)
      assert(Classes.of(t).isDefined, t)
    }

  test("marketing is FR-011's list exactly; service is every other trigger and nothing more"):
    assertEquals(Classes.marketing, fr011Marketing)
    assertEquals(Classes.service, (fr006 ++ fr014).toSet -- fr011Marketing)

  test("of: marketing, service, the identify, and none for an unknown trigger"):
    assertEquals(Classes.of("deposit_confirmed"), Some(SendClass.Marketing))
    assertEquals(Classes.of("self_exclusion_activated"), Some(SendClass.Service))
    assertEquals(Classes.of("PasswordResetRequest"), Some(SendClass.Service))
    assertEquals(Classes.of("identify"), Some(SendClass.Identify))
    assertEquals(Classes.of("ConsentUpdated"), None)
    assertEquals(Classes.of("cooling_off_removed"), None)
