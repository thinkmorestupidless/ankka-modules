package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

// ── casino.sessions (players; ephemeral, 90 days) ─────────────────────────

sealed trait CasinoSessionsFact extends Fact:
  def playerId: String
  def key: String = playerId

@named("game_session_started")
final case class GameSessionStarted(playerId: String, sessionId: String, gameId: String, playerSessionId: String,
    occurredAt: Instant) extends CasinoSessionsFact

/** `close_reason` (spec 008 FR-022): `player`, `limit`, `forced`, `logout`, `expired`, `suspended`, `superseded`; optional, so readers of the first shape still read it. */
@named("game_session_ended")
final case class GameSessionEnded(playerId: String, sessionId: String, gameId: String, durationMinutes: Int,
    occurredAt: Instant, closeReason: Option[String] = None) extends CasinoSessionsFact

/** The network address and user agent are personal (FR-023). */
@named("player_session_started")
final case class PlayerSessionStarted(playerId: String, sessionId: String, networkAddress: Personal[String],
    userAgent: Personal[String], occurredAt: Instant) extends CasinoSessionsFact

@named("player_session_ended")
final case class PlayerSessionEnded(playerId: String, sessionId: String, closeReason: String, occurredAt: Instant)
    extends CasinoSessionsFact

object CasinoSessionsFact:
  given JsonValueCodec[CasinoSessionsFact] = FactCodecs.make

object CasinoSessions extends Topic[CasinoSessionsFact]("casino.sessions", Vector("players")):
  val families = Vector(
    FamilyDef.of[GameSessionStarted]("game_session_started"),
    FamilyDef.of[GameSessionEnded]("game_session_ended"),
    FamilyDef.of[PlayerSessionStarted]("player_session_started"),
    FamilyDef.of[PlayerSessionEnded]("player_session_ended")
  )

// ── casino.rounds (game-provider) ─────────────────────────────────────────

sealed trait CasinoRoundsFact extends Fact

/**
 * Keyed by the wallet the round was played from; the original's `game_round_settled` fields kept (FR-014).
 * Spec 003 FR-019 adds, additively: the provider's transaction ids and the wallet's, in the same order
 * (one per movement, empty for a free round), so reporting matches the two without a call; and the
 * free-rounds grant a tagged round belongs to, which `rewards` settles the win from (spec 011).
 * `transaction_ids` stays the wallet's ids for readers written against it.
 */
@named("round_settled")
final case class RoundSettled(playerId: String, currency: Currency, roundId: String, provider: String,
    providerRoundId: String, gameId: String, sessionId: String, stakeCents: MinorUnits, winCents: MinorUnits,
    netResultCents: SignedMinorUnits, outcome: String, transactionIds: Vector[String],
    providerTransactionIds: Vector[String], walletTransactionIds: Vector[String], freeRoundsGrant: Option[String],
    occurredAt: Instant)
    extends CasinoRoundsFact:
  def key: String = s"$playerId/${currency.code}"

object RoundSettled:
  /** The outcomes a round settles with: `credit` is a standalone credit or a payout (spec 003 FR-019). */
  object Outcome:
    val Win       = "win"
    val Loss      = "loss"
    val Cancelled = "cancelled"
    val Credit    = "credit"
    val all: Set[String] = Set(Win, Loss, Cancelled, Credit)

object CasinoRoundsFact:
  given JsonValueCodec[CasinoRoundsFact] = FactCodecs.make

object CasinoRounds extends Topic[CasinoRoundsFact]("casino.rounds", Vector("game-provider")):
  val families = Vector(FamilyDef.of[RoundSettled]("round_settled"))

// ── casino.withdrawals (payouts) ──────────────────────────────────────────

/**
 * `payout_id` is the original's export name for the withdrawal request's id (FR-014). `amount_cents` is the amount
 * paid; `gateway`, `terminal` and `provider_reference` were added by spec 007 (R11), optional for older readers.
 */
sealed trait CasinoWithdrawalsFact extends Fact:
  def playerId: String
  def key: String = playerId

@named("withdrawal_requested")
final case class WithdrawalRequested(playerId: String, payoutId: String, amountCents: MinorUnits, currency: Currency,
    terminal: String, occurredAt: Instant, gateway: Option[String] = None) extends CasinoWithdrawalsFact

@named("withdrawal_approved")
final case class WithdrawalApproved(playerId: String, payoutId: String, amountCents: MinorUnits, currency: Currency,
    actorId: String, occurredAt: Instant, terminal: Option[String] = None, gateway: Option[String] = None)
    extends CasinoWithdrawalsFact

@named("withdrawal_completed")
final case class WithdrawalCompleted(playerId: String, payoutId: String, amountCents: MinorUnits, currency: Currency,
    occurredAt: Instant, terminal: Option[String] = None, gateway: Option[String] = None,
    providerReference: Option[String] = None) extends CasinoWithdrawalsFact

@named("withdrawal_cancelled")
final case class WithdrawalCancelled(playerId: String, payoutId: String, amountCents: MinorUnits, currency: Currency,
    actor: String, occurredAt: Instant, terminal: Option[String] = None, gateway: Option[String] = None,
    reason: Option[String] = None) extends CasinoWithdrawalsFact

@named("withdrawal_failed")
final case class WithdrawalFailed(playerId: String, payoutId: String, amountCents: MinorUnits, currency: Currency,
    failureReason: String, occurredAt: Instant, terminal: Option[String] = None, gateway: Option[String] = None,
    providerReference: Option[String] = None) extends CasinoWithdrawalsFact

@named("withdrawal_reversed")
final case class WithdrawalReversed(playerId: String, payoutId: String, amountCents: MinorUnits, currency: Currency,
    reason: String, occurredAt: Instant, terminal: Option[String] = None, gateway: Option[String] = None,
    providerReference: Option[String] = None) extends CasinoWithdrawalsFact

object CasinoWithdrawalsFact:
  given JsonValueCodec[CasinoWithdrawalsFact] = FactCodecs.make

object CasinoWithdrawals extends Topic[CasinoWithdrawalsFact]("casino.withdrawals", Vector("payouts")):
  val families = Vector(
    FamilyDef.of[WithdrawalRequested]("withdrawal_requested"),
    FamilyDef.of[WithdrawalApproved]("withdrawal_approved"),
    FamilyDef.of[WithdrawalCompleted]("withdrawal_completed"),
    FamilyDef.of[WithdrawalCancelled]("withdrawal_cancelled"),
    FamilyDef.of[WithdrawalFailed]("withdrawal_failed"),
    FamilyDef.of[WithdrawalReversed]("withdrawal_reversed")
  )

// ── casino.rewards (rewards) ──────────────────────────────────────────────

/** Every grant family carries the offer's id, name and type (spec 011 FR-027); `bonus_*` kept (FR-014). */
sealed trait CasinoRewardsFact extends Fact:
  def playerId: String
  def key: String = playerId

@named("bonus_granted")
final case class BonusGranted(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    amountCents: MinorUnits, currency: Currency, bonusCode: Option[String], occurredAt: Instant) extends CasinoRewardsFact

@named("bonus_cancelled")
final case class BonusCancelled(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    amountCents: MinorUnits, currency: Currency, occurredAt: Instant) extends CasinoRewardsFact

@named("bonus_forfeited")
final case class BonusForfeited(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    reason: String, occurredAt: Instant) extends CasinoRewardsFact

@named("bonus_completed")
final case class BonusCompleted(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    occurredAt: Instant) extends CasinoRewardsFact

@named("grant_expired")
final case class GrantExpired(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    occurredAt: Instant) extends CasinoRewardsFact

@named("free_spins_granted")
final case class FreeSpinsGranted(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    rounds: Int, gameIds: Vector[String], occurredAt: Instant) extends CasinoRewardsFact

@named("free_spins_settled")
final case class FreeSpinsSettled(playerId: String, bonusId: String, bonusName: String, offerId: String, offerType: String,
    winCents: MinorUnits, currency: Currency, occurredAt: Instant) extends CasinoRewardsFact

@named("bonus_code_entered")
final case class BonusCodeEntered(playerId: String, code: String, occurredAt: Instant) extends CasinoRewardsFact

object CasinoRewardsFact:
  given JsonValueCodec[CasinoRewardsFact] = FactCodecs.make

object CasinoRewards extends Topic[CasinoRewardsFact]("casino.rewards", Vector("rewards")):
  val families = Vector(
    FamilyDef.of[BonusGranted]("bonus_granted"),
    FamilyDef.of[BonusCancelled]("bonus_cancelled"),
    FamilyDef.of[BonusForfeited]("bonus_forfeited"),
    FamilyDef.of[BonusCompleted]("bonus_completed"),
    FamilyDef.of[GrantExpired]("grant_expired"),
    FamilyDef.of[FreeSpinsGranted]("free_spins_granted"),
    FamilyDef.of[FreeSpinsSettled]("free_spins_settled"),
    FamilyDef.of[BonusCodeEntered]("bonus_code_entered")
  )

// ── casino.push (engagement; ephemeral, one day) ──────────────────────────

sealed trait CasinoPushFact extends Fact:
  def playerId: String
  def key: String = playerId

@named("push_delivered")
final case class PushDelivered(playerId: String, correlationId: String, connections: Int, occurredAt: Instant)
    extends CasinoPushFact

@named("presence_changed")
final case class PresenceChanged(playerId: String, online: Boolean, since: Instant, occurredAt: Instant) extends CasinoPushFact

object CasinoPushFact:
  given JsonValueCodec[CasinoPushFact] = FactCodecs.make

object CasinoPush extends Topic[CasinoPushFact]("casino.push", Vector("engagement")):
  val families = Vector(
    FamilyDef.of[PushDelivered]("push_delivered"),
    FamilyDef.of[PresenceChanged]("presence_changed")
  )

// ── platform.audit (every service) ────────────────────────────────────────

/** A change, by field. A personal field's change names the field and carries no value (spec 018 FR-003). */
final case class AuditChange(field: String, before: Option[String], after: Option[String])

/** The shape spec 018 FR-001 owns. Keyed by the subject. */
sealed trait PlatformAuditFact extends Fact

@named("audit_record")
final case class AuditRecord(recordId: String, brand: String, service: String, action: String, actorKind: String,
    actorId: String, approverId: Option[String], subjectKind: String, subjectId: String, outcome: String,
    reasonCode: Option[String], changes: Vector[AuditChange], correlationId: String, causationId: String,
    recordClass: String, occurredAt: Instant) extends PlatformAuditFact:
  def key: String = s"$subjectKind/$subjectId"

object AuditChange:
  given FieldType[AuditChange] with
    def schema = Json.obj(
      "type"       -> Json.str("object"),
      "required"   -> Json.Arr(Vector(Json.str("field"))),
      "properties" -> Json.obj("field" -> Json.obj("type" -> Json.str("string")), "before" -> Json.obj("type" -> Json.str("string")), "after" -> Json.obj("type" -> Json.str("string")))
    )
    def sample = AuditChange("status", Some("active"), Some("suspended"))

object PlatformAuditFact:
  given JsonValueCodec[PlatformAuditFact] = FactCodecs.make

object PlatformAudit extends Topic[PlatformAuditFact]("platform.audit", Vector("every service")):
  val families = Vector(FamilyDef.of[AuditRecord]("audit_record"))

// ── payments.merchants (payments' merchant; shared) ───────────────────────

/** Never a raw key: its id, sub-brand and digest. */
sealed trait PaymentsMerchantsFact extends Fact:
  def keyId: String
  def key: String = keyId

@named("merchant_key_issued")
final case class MerchantKeyIssued(keyId: String, subBrand: String, digest: String, occurredAt: Instant) extends PaymentsMerchantsFact

@named("merchant_key_deactivated")
final case class MerchantKeyDeactivated(keyId: String, subBrand: String, occurredAt: Instant) extends PaymentsMerchantsFact

object PaymentsMerchantsFact:
  given JsonValueCodec[PaymentsMerchantsFact] = FactCodecs.make

object PaymentsMerchants extends Topic[PaymentsMerchantsFact]("payments.merchants", Vector("merchant")):
  val families = Vector(
    FamilyDef.of[MerchantKeyIssued]("merchant_key_issued"),
    FamilyDef.of[MerchantKeyDeactivated]("merchant_key_deactivated")
  )
