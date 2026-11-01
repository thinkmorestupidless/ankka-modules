package io.eitheror.casino.rewards.reward

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.{BetRules, Offer, PayloadType, WinningsDestination}

import java.time.Instant

/** How a reward was given (FR-010), in the original's names. */
enum Source(val wire: String):
  case CustomerioWebhook extends Source("customerio_webhook")
  case PlayerCode        extends Source("player_code")
  case OperatorManual    extends Source("operator_manual")
  case AffiliateCode     extends Source("affiliate_code")
  case System            extends Source("system")

object Source:
  def from(text: String): Option[Source] = values.find(_.wire == text)
  given JsonValueCodec[Source]           = RewardsCodecs.wire(values, _.wire)

/** Why a record went to zero (FR-014). */
enum ForfeitReason(val wire: String):
  case BonusExhausted     extends ForfeitReason("bonus_exhausted")
  case AdminAdjustEmptied extends ForfeitReason("admin_adjust_emptied")

object ForfeitReason:
  given JsonValueCodec[ForfeitReason] = RewardsCodecs.wire(values, _.wire)

/** Why a reward closed (FR-014, FR-024). */
enum CloseReason(val wire: String):
  case CapExpired         extends CloseReason("cap_expired")
  case CapExhausted       extends CloseReason("cap_exhausted")
  case RealBalanceZero    extends CloseReason("real_balance_zero")
  case FreeSpinsFinalised extends CloseReason(Settlement.Finalised)

object CloseReason:
  def from(text: String): Option[CloseReason] = values.find(_.wire == text)
  given JsonValueCodec[CloseReason]           = RewardsCodecs.wire(values, _.wire)

/** Why a reward expired (FR-011, FR-015, FR-024). */
enum ExpireReason(val wire: String):
  case ClaimExpired           extends ExpireReason("claim_expired")
  case WageringWindowExpired  extends ExpireReason("wagering_window_expired")
  case PendingExpired         extends ExpireReason("pending_expired")
  case FreeSpinsExpiredUnused extends ExpireReason(Settlement.ExpiredUnused)

object ExpireReason:
  given JsonValueCodec[ExpireReason] = RewardsCodecs.wire(values, _.wire)

/** Why a reward was cancelled (FR-016, FR-023). */
enum CancelReason(val wire: String):
  case PlayerCancel   extends CancelReason("player_cancel")
  case ProviderFailed extends CancelReason("provider_failed")

object CancelReason:
  given JsonValueCodec[CancelReason] = RewardsCodecs.wire(values, _.wire)

/** An operator's revoke (FR-017): the wallet's forfeit, or its cancel with the clawback. */
enum RevokeMode(val wire: String):
  case Forfeit extends RevokeMode("forfeit")
  case Reclaim extends RevokeMode("reclaim")

object RevokeMode:
  def from(text: String): Option[RevokeMode] = values.find(_.wire == text)
  given JsonValueCodec[RevokeMode]           = RewardsCodecs.wire(values, _.wire)

/**
 * The offer's rules copied at creation (FR-010, the original's `OfferRuleSnapshot`): the bet-time rules the
 * wallet applies, and the windows the clocks read (research R10). A window of none, or 0, is the default.
 */
final case class RuleSnapshot(
    betRules: BetRules = BetRules(),
    claimWindowDays: Option[Int] = None,
    wageringWindowDays: Option[Int] = None,
    freeSpinsExpiryDays: Option[Int] = None,
    maxConcurrentGrants: Option[Int] = None
)

object RuleSnapshot:
  def of(o: Offer): RuleSnapshot =
    RuleSnapshot(o.betRules, o.claimWindowDays, o.wageringWindowDays, o.freeSpinsExpiryDays, o.maxConcurrentGrants)

/** The rounds an offer grants (FR-023), fixed at creation. */
final case class FreeRoundsTerms(gameId: String, provider: String, spinCount: Int, spinValue: Long, destination: Destination)

object FreeRoundsTerms:
  /** The offer's rounds, when it grants rounds and names its game; the spin value is the one resolved for the currency. */
  def of(o: Offer, spinValue: Long): Option[FreeRoundsTerms] =
    for
      game  <- o.gameId.filter(_ => o.freeRounds)
      count <- o.spinCount
    yield FreeRoundsTerms(game, o.provider.getOrElse("st8"), count, spinValue,
      Destination.from(Some(o.winningsDestination.getOrElse(WinningsDestination.RealBalance).wire)))

/**
 * A free-rounds reward's play (data-model.md `FreeRoundsState`): the terms, the tally, the provider's last word, and
 * each late round's win, so a repeated late round answers the same payment. The expiry is the reward's `expiresAt`.
 */
final case class FreeRoundsState(terms: FreeRoundsTerms, tally: Tally, providerStatus: Option[String] = None,
    latePaid: Map[String, Long] = Map.empty)

/** An operator's revoke as the reward holds it; the reason is the audit's alone (research R12). */
final case class Revocation(by: String, mode: RevokeMode)

/**
 * What a reward is opened with: the offer as it was (an operator's free bonus or free rounds name a `manual:<uuid>`
 * offer of type `MANUAL`), the evaluator's decision, how it was given, and who gave it when an operator did.
 */
final case class Opening(
    playerId: String,
    offerId: String,
    offerName: String,
    offerType: String,
    payloadType: PayloadType,
    decision: Decision,
    priority: Option[Long] = None,
    source: Source,
    code: Option[String] = None,
    tag: Option[String] = None,
    snapshot: RuleSnapshot = RuleSnapshot(),
    freeRounds: Option[FreeRoundsTerms] = None,
    by: Option[String] = None
)

object Opening:
  def of(offer: Offer, playerId: String, decision: Decision, source: Source, code: Option[String] = None, tag: Option[String] = None,
      freeRounds: Option[FreeRoundsTerms] = None, by: Option[String] = None): Opening =
    Opening(playerId, offer.id, offer.name, offer.offerType.wire, offer.payload, decision, offer.priority, source, code, tag,
      RuleSnapshot.of(offer), freeRounds, by)

/**
 * The wallet's answer to an award (research R5's `award` step), as the reward keeps it, with the terms the award was
 * made on: the claim's decision may differ from the provisional one the reward opened with (a deposit match is sized
 * by the deposit), so the activation carries the amount, multiplier, maximum convertible and currency awarded.
 */
final case class WalletAward(recordId: Option[String] = None, wageringRequirement: Long, amount: Option[Long] = None,
    wageringMultiplier: Option[Long] = None, maxConvertible: Option[Long] = None, currency: Option[String] = None)

/**
 * A reward (data-model.md `Reward`), keyed by the reward id. `opened` is false until `Open`. `expiresAt` is the
 * deadline of the state the reward is in: the pending selection's, the claim deadline once active, the free
 * rounds' expiry, the cap's once converted. Money is minor units; no customer field is held: the player is
 * named by id only.
 */
final case class RewardState(
    opened: Boolean = false,
    rewardId: String = "",
    playerId: String = "",
    offerId: String = "",
    offerName: String = "",
    offerType: String = "",
    status: RewardStatus = RewardStatus.Available,
    payloadType: PayloadType = PayloadType.BonusFunds,
    faceValue: Long = 0L,
    wageringRequirement: Long = 0L,
    wageringMultiplier: Long = 0L,
    maxConvertible: Long = 0L,
    currency: String = "",
    priority: Option[Long] = None,
    source: Source = Source.System,
    code: Option[String] = None,
    tag: Option[String] = None,
    snapshot: RuleSnapshot = RuleSnapshot(),
    openedBy: Option[String] = None,
    recordId: Option[String] = None,
    openedAt: Instant = Instant.EPOCH,
    selectedAt: Option[Instant] = None,
    grantedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    wageringExpiresAt: Option[Instant] = None,
    capExpiresAt: Option[Instant] = None,
    convertedAt: Option[Instant] = None,
    terminatedAt: Option[Instant] = None,
    terminationReason: Option[String] = None,
    lastDecline: Option[String] = None,
    revoke: Option[Revocation] = None,
    switches: Int = 0,
    freeRounds: Option[FreeRoundsState] = None
):
  def currencyOf: Currency = Currency.unsafe(currency)
  def isFreeRounds: Boolean = freeRounds.isDefined
  /** Open at the wallet: active, or converted and waiting on its cap (FR-016). */
  def isOpen: Boolean = status == RewardStatus.Active || status == RewardStatus.ConvertedPendingCap

  /** The payment a finalisation owes, so a caller that repeats `Finalise` starts the same wallet step (research R5). */
  def finalPayment: Option[Payment] =
    freeRounds.flatMap(f => f.tally.paid.filter(_ > 0).map(Payment(f.terms.destination, _)))

/** A refusal: the code and the message (contracts/routes.md's `{error, message}`). */
final case class Refused(code: String, message: String)

/**
 * What a command answers: the state; the refusal when there is one; the payment the caller starts through
 * `WalletStep` (a finalisation's, or a late round's); and why a round or a finalisation did nothing.
 */
final case class RewardAnswer(state: RewardState, refusal: Option[Refused] = None, payment: Option[Payment] = None,
    skipped: Option[String] = None)

sealed trait RewardEvent
object RewardEvent:
  /** Opened, available. The opening is the reward's fixed part; every later event reads it from the state. */
  final case class Targeted(rewardId: String, opening: Opening, at: Instant)                         extends RewardEvent
  final case class Selected(at: Instant, expiresAt: Instant)                                         extends RewardEvent
  final case class Deselected(at: Instant)                                                           extends RewardEvent
  /** The wallet's award confirmed (FR-013), or free rounds granted at the provider; `expiresAt` the claim deadline or the rounds' expiry. */
  final case class Activated(award: Option[WalletAward], expiresAt: Instant, wageringExpiresAt: Option[Instant], at: Instant)
      extends RewardEvent
  final case class AwardRefused(code: String, at: Instant)                                           extends RewardEvent
  final case class Converted(at: Instant, capExpiresAt: Instant)                                     extends RewardEvent
  final case class Forfeited(reason: ForfeitReason, amount: Option[Long], at: Instant)              extends RewardEvent
  /** `afterConversion`: the close of a pending cap, which publishes nothing (research R11). */
  final case class Closed(reason: CloseReason, afterConversion: Boolean, at: Instant)              extends RewardEvent
  /** `from`: the state it left; only an expiry from `ACTIVE` is published (contracts/facts.md). */
  final case class Expired(reason: ExpireReason, from: RewardStatus, at: Instant)                  extends RewardEvent
  final case class Cancelled(reason: CancelReason, clawback: Long, at: Instant)                    extends RewardEvent
  /** The reason is personal (FR-033): named in the audit, never published. */
  final case class Revoked(mode: RevokeMode, by: String, reason: Option[Personal[String]], clawback: Long, at: Instant)
      extends RewardEvent
  final case class Switched(step: Int, priority: Option[Long], at: Instant)                         extends RewardEvent
  final case class ProviderStatusRecorded(status: String, at: Instant)                             extends RewardEvent
  /** A round counted (FR-024); `remaining` is the rounds left after it, for the view. */
  final case class RoundCounted(roundId: String, win: Long, remaining: Int, at: Instant)            extends RewardEvent
  /** A round after finalisation, paid on its own when it won something (FR-024). */
  final case class LatePaid(roundId: String, win: Long, at: Instant)                                extends RewardEvent
  /** The rounds' end: the amount owed to the destination (zero pays nothing); `Closed` or `Expired` follows. */
  final case class FreeRoundsFinalised(amount: Long, at: Instant)                                  extends RewardEvent

// ── commands (data-model.md's table) ─────────────────────────────────────

/** `initial` is `AVAILABLE`, `PENDING` or `ACTIVE`; `ACTIVE` is a reward the wallet already holds (`award`). */
final case class Open(opening: Opening, initial: RewardStatus, at: Instant, award: Option[WalletAward] = None,
    wageringExpiresAt: Option[Instant] = None)
final case class Select(at: Instant)
final case class Deselect(at: Instant)
final case class Activate(award: Option[WalletAward], at: Instant)
final case class RefuseAward(code: String, at: Instant)
final case class Convert(at: Instant)
final case class Forfeit(reason: ForfeitReason, amount: Option[Long], at: Instant)
final case class Close(reason: CloseReason, at: Instant)
/** `deadline`, when a timer asks: a deadline the reward no longer holds is stale and does nothing. */
final case class Expire(reason: ExpireReason, at: Instant, deadline: Option[Instant] = None)
final case class Cancel(reason: CancelReason, clawback: Long, at: Instant)
final case class Revoke(mode: RevokeMode, by: String, reason: Option[Personal[String]], clawback: Long, at: Instant)
/** `step` is the switch's number, `switch-<n>` (research R5); a step already recorded does nothing. */
final case class Switch(step: Int, priority: Option[Long], at: Instant)
final case class ProviderStatus(status: String, at: Instant)
final case class RoundSettled(roundId: String, win: Long, at: Instant)
final case class Finalise(at: Instant)

/** The refusals a reward answers. */
object RewardCodes:
  val NotFound      = "CLAIM_NOT_FOUND"
  val NotFreeRounds = "NOT_FREE_ROUNDS"
  val InvalidOpen   = "INVALID_OPENING"
  /** A switch of a reward that is not open, in the wallet's word for it. */
  val NoActiveBonus = "NO_ACTIVE_BONUS"

/** The defaults of research R10. */
object Windows:
  /** The claim deadline and the cap's, from activation and from conversion, when the offer sets no wagering window. */
  val WageringDays: Int = 30
  /** The free rounds' expiry when the offer sets none (FR-025). */
  val FreeRoundsDays: Int = 30
  /** The pending selection's window when the offer sets no claim window (FR-011). */
  val PendingDays: Int = io.eitheror.casino.rewards.eligibility.Evaluator.SelectionWindowDays

  def days(set: Option[Int], default: Int): Long = set.filter(_ > 0).getOrElse(default).toLong
