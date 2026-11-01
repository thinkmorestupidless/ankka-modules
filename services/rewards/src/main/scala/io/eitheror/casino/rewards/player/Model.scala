package io.eitheror.casino.rewards.player

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.eligibility.{Amounts, Decision, Held, PlayerSnapshot, Standing}
import io.eitheror.casino.rewards.offers.Offer
import io.eitheror.casino.rewards.reward.{Opening, RewardStatus, Source}

import java.time.Instant

/**
 * A claim reserved (data-model.md `Claimed`): the reward it is for, the evaluator's decision, how the reward was given
 * (source, code, tag), and the deposit's payment when a deposit made it. The caller opens the reward with it and starts
 * `WalletStep(<reward>/award)`.
 *
 * `replaces` names the reward this claim stands in for: one whose award the wallet refused (`BONUS_ALREADY_ACTIVE`, say).
 * The wallet names its record by the award's key, the reward id, and answers a key it has seen with its first outcome,
 * so a reward's award can be asked once only; a claim after a refusal is therefore made under a fresh reward id, which
 * takes the refused reward's place (its source, code, tag and selection), and the refused one is retired.
 */
final case class Claim(rewardId: String, offerId: String, decision: Decision, source: Source, code: Option[String] = None,
    tag: Option[String] = None, paymentRef: Option[String] = None, at: Instant, replaces: Option[String] = None)

/**
 * A reward the player holds, as this record knows it: its offer, its state as `IndexConsumer` last reported it, the
 * selection, and the claim reserved on it. `claiming` is a claim the wallet has not answered yet: it counts as open, so
 * two claims at once see each other (the edge case "two selections at once"). How it was given is kept from targeting.
 */
final case class HeldReward(
    offerId: String,
    status: RewardStatus = RewardStatus.Available,
    providerFailed: Boolean = false,
    source: Source = Source.System,
    code: Option[String] = None,
    tag: Option[String] = None,
    selectedAt: Option[Instant] = None,
    claim: Option[Claim] = None,
    claiming: Boolean = false,
    lastDecline: Option[String] = None,
    openedAt: Instant = Instant.EPOCH
):
  /** Available or pending, and not being claimed: what the player may select or a deposit may confirm. */
  def unclaimed: Boolean = !claiming && (status == RewardStatus.Available || status == RewardStatus.Pending)
  def standing: Standing = if claiming then Standing.Open else Standing.of(status.wire)
  def held: Held         = Held(offerId, standing, providerFailed)

/** A confirmed deposit as the wallet reported it (FR-012): the ordinal is the wallet's, per player and currency. */
final case class DepositSeen(amount: Long, currency: String, ordinal: Int, paymentRef: String, at: Instant)

/**
 * What this service knows of one player (data-model.md `PlayerRewards`): what the evaluator reads (FR-008) and the
 * rewards held, with the offers made visible to them and the last deposit. `currency` is the player's wallet currency
 * as last seen (a deposit's, or the registration's), FR-007's last choice. `payments` are the recent deposits' payment
 * references, so a deposit reported twice is recorded once. `retired` are rewards a later claim replaced (`Claim.replaces`):
 * their reports are no longer read. No personal value is held: the player is named by id.
 */
final case class PlayerRewardsState(
    registeredAt: Option[Instant] = None,
    checkInProgress: Option[Instant] = None,
    noDepositBlocked: Boolean = false,
    suspectedDuplicate: Boolean = false,
    tier: Option[String] = None,
    detectionOn: Boolean = true,
    currency: Option[String] = None,
    rewards: Map[String, HeldReward] = Map.empty,
    lastDeposit: Option[DepositSeen] = None,
    payments: Vector[String] = Vector.empty,
    retired: Set[String] = Set.empty
):
  /** The evaluator's view of the player (FR-008); segments and the tier rank are dormant (research R4). */
  def snapshot: PlayerSnapshot =
    PlayerSnapshot(registeredAt, checkInProgress.isDefined, noDepositBlocked, suspectedDuplicate, None, None,
      rewards.values.toVector.map(_.held))

  /** The reward of the offer the player may select, if any. */
  def unclaimed(offerId: String): Option[(String, HeldReward)] =
    rewards.toVector.sortBy(_._2.openedAt).find((_, h) => h.offerId == offerId && h.unclaimed)

  /** A pending selection other than `rewardId`, being claimed or not: a second selection is refused while one is held. */
  def otherPending(rewardId: String): Option[String] =
    rewards.toVector.collectFirst { case (id, h) if id != rewardId && h.status == RewardStatus.Pending => id }

  /** The player's first pending selection (research R4: the deposit-confirm path takes the first). */
  def pending: Option[(String, HeldReward)] =
    rewards.toVector.filter((_, h) => h.unclaimed && h.status == RewardStatus.Pending).sortBy(_._2.selectedAt).headOption

  /** The rewards the player may select, in the order they were made visible (FR-004). */
  def selectable: Vector[(String, HeldReward)] = rewards.toVector.filter(_._2.unclaimed).sortBy(_._2.openedAt)

  /** The claim a deposit's payment made, so a repeat answers it again. */
  def claimOf(paymentRef: String): Option[Claim] = rewards.values.flatMap(_.claim).find(_.paymentRef.contains(paymentRef))

object PlayerRewardsState:
  /** How many recent payment references are kept against a deposit reported twice. */
  val PaymentsKept: Int = 200

  given JsonValueCodec[PlayerRewardsState] = RewardsCodecs.stored[PlayerRewardsState]

sealed trait PlayerEvent
object PlayerEvent:
  /** The offer made visible: a reward id reserved, `AVAILABLE` (data-model.md `OfferMadeVisible`). */
  final case class OfferMadeVisible(rewardId: String, offerId: String, source: Source, code: Option[String], tag: Option[String],
      at: Instant) extends PlayerEvent
  final case class RewardSelected(rewardId: String, at: Instant)                  extends PlayerEvent
  final case class RewardsDeselected(rewardIds: Vector[String], at: Instant)      extends PlayerEvent
  final case class DepositRecorded(deposit: DepositSeen)                          extends PlayerEvent
  /** The claim reserved on the reward, in the same event as the evaluation that allowed it. */
  final case class Claimed(claim: Claim)                                          extends PlayerEvent
  /** A pending selection a deposit found past its claim window, or of an offer no longer live (FR-012, research R4). */
  final case class PendingExpired(rewardId: String, at: Instant)                  extends PlayerEvent
  /** A deposit's evaluation declined the pending selection: it stays pending with the reason (FR-012). */
  final case class DeclineRecorded(rewardId: String, reason: String, at: Instant) extends PlayerEvent
  /** The reward's state as `IndexConsumer` reports it; `awardRefused` ends a claim the wallet refused. */
  final case class RewardIndexed(rewardId: String, offerId: String, status: RewardStatus, providerFailed: Boolean,
      awardRefused: Boolean, at: Instant) extends PlayerEvent

  // The player's standing from `casino.players` (research R9; data-model.md `PlayerFact`).
  /** `currency` is the registration's, FR-007's last choice while no deposit has named one. */
  final case class RegistrationRecorded(registeredAt: Instant, currency: Option[String], at: Instant) extends PlayerEvent
  final case class CheckStarted(registeredAt: Instant, at: Instant)                   extends PlayerEvent
  final case class CheckEnded(outcome: String, at: Instant)                           extends PlayerEvent
  final case class NoDepositBlocked(at: Instant)                                      extends PlayerEvent
  final case class BlockCleared(at: Instant)                                          extends PlayerEvent
  final case class FlagChanged(suspectedDuplicate: Boolean, at: Instant)             extends PlayerEvent
  final case class TierChanged(tier: String, at: Instant)                             extends PlayerEvent
  /** The brand's duplicate detection as `players` last answered it, carried by a fact (research R9). */
  final case class DetectionChanged(on: Boolean, at: Instant)                         extends PlayerEvent

  /**
   * A code the player entered (FR-019; data-model.md `CodeEntered`), trimmed as entered, and what it came to:
   * `matched`, `invalid` or `ineligible` (`CodeOutcome`). `bonus_code_entered` is published from it (`CodesPublisher`).
   */
  final case class CodeEntered(code: String, outcome: String, at: Instant) extends PlayerEvent

/** What a command came to, for its caller to act on (open the reward, select it, start the award, expire it). */
sealed trait Outcome
object Outcome:
  /** The offer is visible with this reward; `fresh` when this command reserved it, so the caller opens the reward. */
  final case class Visible(rewardId: String, fresh: Boolean)        extends Outcome
  final case class Selected(rewardId: String)                       extends Outcome
  final case class Deselected(rewardIds: Vector[String])            extends Outcome
  final case class Claimed(claim: Claim)                            extends Outcome
  final case class Expired(rewardId: String)                        extends Outcome
  /** The evaluator's decline (FR-005), its reason as the original answered it. */
  final case class Declined(rewardId: Option[String], reason: String) extends Outcome
  /** A refusal of the command itself: `offer_not_visible`, `ALREADY_CLAIMED`. */
  final case class Refused(code: String)                            extends Outcome
  /** Nothing to do: a deposit with no pending selection, a repeat that writes nothing. */
  case object Idle                                                  extends Outcome

final case class PlayerAnswer(state: PlayerRewardsState, outcome: Outcome)

// ── commands (data-model.md's table) ─────────────────────────────────────

/** Makes the offer visible to the player (a campaign's target, or a code), reserving a reward `AVAILABLE`. */
final case class Target(offer: Offer, source: Source, code: Option[String] = None, tag: Option[String] = None, at: Instant)

/**
 * The player selects the offer. `walletOpen` is the wallet's open records and `walletCurrency` FR-007's last choice,
 * both read by the caller, since an entity calls nothing.
 */
final case class Select(offer: Offer, walletOpen: Int, walletCurrency: Currency, at: Instant)

final case class Deselect(at: Instant)

/**
 * A confirmed deposit (FR-012). `pending` is the reward the caller found pending and its offer as the book holds it now
 * (none when the book no longer has it); the record decides against its own state.
 */
final case class DepositConfirmed(deposit: DepositSeen, pending: Option[PendingOffer], walletOpen: Int, at: Instant)

final case class PendingOffer(rewardId: String, offer: Option[Offer])

/** An award of the offer on another path (the award route, an operator's award): evaluated in `mode`, then reserved. */
final case class ClaimOffer(offer: Offer, operator: Boolean, deposit: Option[io.eitheror.casino.rewards.eligibility.Deposit],
    source: Source, code: Option[String], tag: Option[String], walletOpen: Int, walletCurrency: Currency, at: Instant)

/** What a code entered came to, as `bonus_code_entered` names it (contracts/facts.md). */
object CodeOutcome:
  val Matched    = "matched"
  val Invalid    = "invalid"
  val Ineligible = "ineligible"

/** The offer a code entered names, as the caller found it (`Codes.find`): how it is given, and the programme's tag. */
final case class CodeFound(offer: Offer, source: Source, tag: Option[String] = None)

/**
 * A code the player entered (FR-019): `found` is the live offer it names, none when it names nothing live. `walletOpen`
 * and `walletCurrency` are read by the caller, as for `Select`.
 */
final case class EnterCode(code: String, found: Option[CodeFound], walletOpen: Int, walletCurrency: Currency, at: Instant)

/** A change of the player's standing as `casino.players` reports it (research R9), for `PlayerFact`. */
sealed trait PlayerChange
object PlayerChange:
  final case class Registered(registeredAt: Instant, currency: Option[String]) extends PlayerChange
  final case class CheckStarted(registeredAt: Instant)                          extends PlayerChange
  final case class CheckEnded(outcome: String)                                  extends PlayerChange
  case object Blocked                                                           extends PlayerChange
  case object BlockCleared                                                      extends PlayerChange
  /** A fraud flag raised (`true`) or cleared: the suspected duplicate of rule 3. */
  final case class Flag(suspected: Boolean)                                     extends PlayerChange
  final case class Tier(name: String)                                           extends PlayerChange

/**
 * A fact of the player's from `PlayersConsumer`: the change, and the brand's detection switch as last read, when it was
 * read (research R9: at start and on each check fact).
 */
final case class PlayerFact(change: PlayerChange, detection: Option[Boolean], at: Instant)

final case class RewardChanged(rewardId: String, offerId: String, status: RewardStatus, providerFailed: Boolean, awardRefused: Boolean,
    at: Instant)

/** How a reward is opened when the offer is made visible, before any deposit: its fixed amount, provisional until the award. */
object Openings:
  def provisional(offer: Offer, playerId: String, currency: Currency, source: Source, code: Option[String], tag: Option[String]): Opening =
    val decision = Amounts.computeBonusAmount(offer, None, enforceMinDeposit = false, currency)
      .getOrElse(Decision(0L, 0L, 0L, offer.wageringMultiplier, currency))
    Opening.of(offer, playerId, decision, source, code, tag)
