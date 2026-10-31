package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/**
 * `casino.players`, produced by `players`, `kyc` and `responsible-gambling` (spec 001 FR-011).
 * Keyed by the player id. The status families keep the original's export fields `previous_value`,
 * `new_value`, `actor`, `actor_id`, `reason` (FR-014).
 */
sealed trait CasinoPlayersFact extends Fact:
  def playerId: String
  def occurredAt: Instant
  def key: String = playerId

// ── from players ───────────────────────────────────────────────────────────

/**
 * Carries the tag, click id and bonus code as presented, unverified, so attribution binds from facts (spec 012),
 * and the profile the player registered with, as `player_profile_updated` carries a later one, so `kyc` judges
 * completeness from registration on (spec 009 FR-016).
 */
@named("player_registered")
final case class PlayerRegistered(playerId: String, registeredAt: Instant, btag: Option[String], clickId: Option[String],
    bonusCode: Option[String], country: Option[String], currency: Option[String], vipTier: Option[String], occurredAt: Instant,
    givenName: Option[Personal[String]] = None, familyName: Option[Personal[String]] = None,
    dateOfBirth: Option[Personal[String]] = None, telephone: Option[Personal[String]] = None,
    addressLine: Option[Personal[String]] = None, city: Option[Personal[String]] = None, postCode: Option[Personal[String]] = None)
    extends CasinoPlayersFact

@named("player_logged_in")
final case class PlayerLoggedIn(playerId: String, sessionId: String, occurredAt: Instant) extends CasinoPlayersFact

@named("player_logged_out")
final case class PlayerLoggedOut(playerId: String, sessionId: String, closeReason: String, occurredAt: Instant) extends CasinoPlayersFact

@named("player_suspended")
final case class PlayerSuspended(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], occurredAt: Instant) extends CasinoPlayersFact

@named("player_reinstated")
final case class PlayerReinstated(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], occurredAt: Instant) extends CasinoPlayersFact

@named("player_self_excluded")
final case class PlayerSelfExcluded(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], excludedAt: Instant, until: Option[Instant], occurredAt: Instant)
    extends CasinoPlayersFact

@named("player_closed")
final case class PlayerClosed(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], occurredAt: Instant) extends CasinoPlayersFact

/** An operator reopening a closed account on the player's request, within the hold (spec 008 FR-032a). */
@named("player_reopened")
final case class PlayerReopened(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], occurredAt: Instant) extends CasinoPlayersFact

@named("vip_tier_changed")
final case class VipTierChanged(playerId: String, previousTier: Option[String], newTier: String, actorId: String,
    occurredAt: Instant) extends CasinoPlayersFact

/** Names only the fields that changed, and carries the eight `kyc` reads for completeness (spec 008 FR-025). */
@named("player_profile_updated")
final case class PlayerProfileUpdated(playerId: String, changedFields: Vector[String], givenName: Option[Personal[String]],
    familyName: Option[Personal[String]], dateOfBirth: Option[Personal[String]], telephone: Option[Personal[String]],
    addressLine: Option[Personal[String]], city: Option[Personal[String]], postCode: Option[Personal[String]],
    country: Option[String], occurredAt: Instant) extends CasinoPlayersFact

@named("player_consent_changed")
final case class PlayerConsentChanged(playerId: String, channel: String, consented: Boolean, source: String,
    occurredAt: Instant) extends CasinoPlayersFact

@named("player_fraud_flagged")
final case class PlayerFraudFlagged(playerId: String, fraudCaseId: String, signal: String, occurredAt: Instant)
    extends CasinoPlayersFact

@named("player_fraud_cleared")
final case class PlayerFraudCleared(playerId: String, fraudCaseId: String, occurredAt: Instant) extends CasinoPlayersFact

@named("player_message_created")
final case class PlayerMessageCreated(playerId: String, messageId: String, sendClass: String, occurredAt: Instant)
    extends CasinoPlayersFact

@named("player_message_read")
final case class PlayerMessageRead(playerId: String, messageId: String, occurredAt: Instant) extends CasinoPlayersFact

@named("player_message_deleted")
final case class PlayerMessageDeleted(playerId: String, messageId: String, occurredAt: Instant) extends CasinoPlayersFact

// ── from kyc ───────────────────────────────────────────────────────────────

@named("player_kyc_submitted")
final case class PlayerKycSubmitted(playerId: String, documentId: String, category: String, occurredAt: Instant)
    extends CasinoPlayersFact

@named("player_kyc_document_reviewed")
final case class PlayerKycDocumentReviewed(playerId: String, documentId: String, category: String, decision: String,
    reasonCode: Option[String], actorId: String, occurredAt: Instant) extends CasinoPlayersFact

@named("player_kyc_verified")
final case class PlayerKycVerified(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], occurredAt: Instant) extends CasinoPlayersFact

@named("player_kyc_rejected")
final case class PlayerKycRejected(playerId: String, previousValue: String, newValue: String, actor: String,
    actorId: Option[String], reason: Option[String], occurredAt: Instant) extends CasinoPlayersFact

/** `reason`: `agent`, `required_category`, `profile_changed` or `document` (spec 009 FR-030). */
@named("player_kyc_pending")
final case class PlayerKycPending(playerId: String, previousValue: String, newValue: String, occurredAt: Instant,
    reason: Option[String] = None) extends CasinoPlayersFact

/** `reason`: `threshold`, `source_of_funds`, `window` or `large_deposit` (spec 009 FR-030). */
@named("player_kyc_review_required")
final case class PlayerKycReviewRequired(playerId: String, reason: String, occurredAt: Instant) extends CasinoPlayersFact

@named("player_sof_cleared")
final case class PlayerSofCleared(playerId: String, actorId: String, occurredAt: Instant) extends CasinoPlayersFact

// ── from responsible-gambling ──────────────────────────────────────────────

@named("rg_case_opened")
final case class RgCaseOpened(playerId: String, rgCaseId: String, caseType: String, trigger: String,
    closesAt: Option[Instant], occurredAt: Instant, permanent: Option[Boolean] = None) extends CasinoPlayersFact

@named("rg_case_closed")
final case class RgCaseClosed(playerId: String, rgCaseId: String, caseType: String, closure: String, occurredAt: Instant)
    extends CasinoPlayersFact

@named("rg_limit_applied")
final case class RgLimitApplied(playerId: String, limitType: String, period: Option[String], value: Option[Long],
    causedBy: String, occurredAt: Instant, currency: Option[String] = None) extends CasinoPlayersFact

object CasinoPlayersFact:
  given JsonValueCodec[CasinoPlayersFact] = FactCodecs.make

object CasinoPlayers extends Topic[CasinoPlayersFact]("casino.players", Vector("players", "kyc", "responsible-gambling")):
  val families = Vector(
    FamilyDef.of[PlayerRegistered]("player_registered"),
    FamilyDef.of[PlayerLoggedIn]("player_logged_in"),
    FamilyDef.of[PlayerLoggedOut]("player_logged_out"),
    FamilyDef.of[PlayerSuspended]("player_suspended"),
    FamilyDef.of[PlayerReinstated]("player_reinstated"),
    FamilyDef.of[PlayerSelfExcluded]("player_self_excluded"),
    FamilyDef.of[PlayerClosed]("player_closed"),
    FamilyDef.of[PlayerReopened]("player_reopened"),
    FamilyDef.of[VipTierChanged]("vip_tier_changed"),
    FamilyDef.of[PlayerProfileUpdated]("player_profile_updated"),
    FamilyDef.of[PlayerConsentChanged]("player_consent_changed"),
    FamilyDef.of[PlayerFraudFlagged]("player_fraud_flagged"),
    FamilyDef.of[PlayerFraudCleared]("player_fraud_cleared"),
    FamilyDef.of[PlayerMessageCreated]("player_message_created"),
    FamilyDef.of[PlayerMessageRead]("player_message_read"),
    FamilyDef.of[PlayerMessageDeleted]("player_message_deleted"),
    FamilyDef.of[PlayerKycSubmitted]("player_kyc_submitted"),
    FamilyDef.of[PlayerKycDocumentReviewed]("player_kyc_document_reviewed"),
    FamilyDef.of[PlayerKycVerified]("player_kyc_verified"),
    FamilyDef.of[PlayerKycRejected]("player_kyc_rejected"),
    FamilyDef.of[PlayerKycPending]("player_kyc_pending"),
    FamilyDef.of[PlayerKycReviewRequired]("player_kyc_review_required"),
    FamilyDef.of[PlayerSofCleared]("player_sof_cleared"),
    FamilyDef.of[RgCaseOpened]("rg_case_opened"),
    FamilyDef.of[RgCaseClosed]("rg_case_closed"),
    FamilyDef.of[RgLimitApplied]("rg_limit_applied")
  )
