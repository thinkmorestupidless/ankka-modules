package io.eitheror.casino.rewards.offers

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonKeyCodec, JsonReader, JsonValueCodec, JsonWriter}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.RewardsCodecs

import java.time.Instant

/** An offer's type (FR-001), in the original's names. */
enum OfferType(val wire: String):
  case DepositMatch              extends OfferType("DEPOSIT_MATCH")
  case FreeSpins                 extends OfferType("FREE_SPINS")
  case DepositMatchWithFreeSpins extends OfferType("DEPOSIT_MATCH_WITH_FREE_SPINS")
  case NoDeposit                 extends OfferType("NO_DEPOSIT")

object OfferType:
  def from(text: String): Option[OfferType] = values.find(_.wire.equalsIgnoreCase(text.trim))
  given JsonValueCodec[OfferType]          = RewardsCodecs.wire(values, _.wire)

/** What an offer pays out (FR-001; the original's `PayloadType`). */
enum PayloadType(val wire: String):
  case BonusFunds                extends PayloadType("bonus_funds")
  case RealCash                  extends PayloadType("real_cash")
  case FreeSpins                 extends PayloadType("free_spins")
  case DepositMatchWithFreeSpins extends PayloadType("deposit_match_with_free_spins")

object PayloadType:
  /** Exactly one of the four names, as the original's `PayloadType.parse`. */
  def from(text: String): Option[PayloadType] = values.find(_.wire == text)

  /**
   * The payload of an offer that names none (the original's `OfferPayload.of`): free spins for the two
   * types that carry rounds, else bonus funds. It never derives `deposit_match_with_free_spins`.
   */
  def derive(t: OfferType): PayloadType = t match
    case OfferType.FreeSpins | OfferType.DepositMatchWithFreeSpins => FreeSpins
    case _                                                          => BonusFunds

  given JsonValueCodec[PayloadType] = RewardsCodecs.wire(values, _.wire)

/** Where a free-rounds offer's winnings go; real balance when the offer names none (`BR` create). */
enum WinningsDestination(val wire: String):
  case RealBalance  extends WinningsDestination("real_balance")
  case BonusBalance extends WinningsDestination("bonus_balance")

object WinningsDestination:
  def from(text: String): Option[WinningsDestination] = values.find(_.wire == text.trim)
  given JsonValueCodec[WinningsDestination]          = RewardsCodecs.wire(values, _.wire)

/** A redemption code as the operator entered it; matched and kept unique in `Codes.normalise`'s form. */
final case class RedemptionCode(code: String, affiliate: Boolean = false)

/**
 * The bet-time rules the wallet applies to the award (FR-001), carried to it as the rule snapshot. Settable
 * by an edit only, as the original's; the names are the wallet's `AwardRequest`'s.
 */
final case class BetRules(
    maxStake: Option[Long] = None,
    maxStakeEnforcement: Option[String] = None,
    contributionDefault: Option[Long] = None,
    contributionOverrides: Map[String, Long] = Map.empty,
    scopeInclude: Set[String] = Set.empty,
    scopeExclude: Set[String] = Set.empty,
    scopeViolationMode: Option[String] = None,
    fundingOrder: Option[String] = None,
    winAllocation: Option[String] = None,
    forfeitOnWithdrawal: Option[String] = None,
    forfeitOnCancel: Option[String] = None
)

/**
 * A brand's offer (FR-001; data-model.md). Amounts are minor units; `bonusPercentage` is basis points
 * (10,000 is 100%). `payloadType` is the explicit one only: `payload` derives it from the type when absent.
 * `depositNumberTarget` absent is `ANY`. A `maxClaimsPerPlayer` of 0, and a `claimWindowDays` of 0, are no
 * limit, as the original reads them. `spinConfigurations`, when it has entries, replaces the flat
 * `currency` and `spinValue` pair. Segment and tier floor are carried and dormant (research R4).
 */
final case class Offer(
    id: String,
    name: String = "",
    description: String = "",
    offerType: OfferType,
    payloadType: Option[PayloadType] = None,
    active: Boolean = true,
    validFrom: Option[Instant] = None,
    validUntil: Option[Instant] = None,
    bonusPercentage: Long = 0L,
    maxBonusValue: Option[Long] = None,
    minDepositAmount: Option[Long] = None,
    maxConvertibleAmount: Option[Long] = None,
    depositNumberTarget: Option[Int] = None,
    claimWindowDays: Option[Int] = None,
    wageringWindowDays: Option[Int] = None,
    freeSpinsExpiryDays: Option[Int] = None,
    wageringMultiplier: Long = 0L,
    maxClaimsPerPlayer: Option[Int] = None,
    maxConcurrentGrants: Option[Int] = None,
    excludedOfferIds: Set[String] = Set.empty,
    conflictsWith: Set[String] = Set.empty,
    redemptionCodes: Vector[RedemptionCode] = Vector.empty,
    segmentPredicate: Option[String] = None,
    vipTierFloor: Option[Int] = None,
    role: Option[String] = None,
    noDepositRequired: Boolean = false,
    currency: Option[Currency] = None,
    priority: Option[Long] = None,
    notificationTemplate: Option[String] = None,
    fieldMap: Option[String] = None,
    gameId: Option[String] = None,
    gameName: Option[String] = None,
    provider: Option[String] = None,
    spinCount: Option[Int] = None,
    spinValue: Option[Long] = None,
    spinConfigurations: Map[Currency, Long] = Map.empty,
    winningsDestination: Option[WinningsDestination] = None,
    betRules: BetRules = BetRules(),
    createdAt: Instant,
    updatedAt: Instant
):
  def payload: PayloadType = payloadType.getOrElse(PayloadType.derive(offerType))

  /** An offer of rounds at the provider: the free-rounds checks and grant apply. */
  def freeRounds: Boolean = payload == PayloadType.FreeSpins

  def destination: WinningsDestination = winningsDestination.getOrElse(WinningsDestination.RealBalance)

  /** Active and inside its validity, both bounds exclusive (the original's `isStillLive`). */
  def live(now: Instant): Boolean =
    active && validFrom.forall(_.isBefore(now)) && validUntil.forall(_.isAfter(now))

object Offer:
  private given JsonKeyCodec[Currency] with
    def decodeKey(in: JsonReader): Currency = Currency.from(in.readKeyAsString()).fold(in.decodeError, identity)
    def encodeKey(x: Currency, out: JsonWriter): Unit = out.writeKey(x.code)

  given JsonValueCodec[Offer] = RewardsCodecs.stored[Offer]
