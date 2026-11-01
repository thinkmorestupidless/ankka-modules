package io.eitheror.casino.rewards.eligibility

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.offers.{Codes, Offer}

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.Locale

/** Every path but an operator's award is `Standard`; an operator's award applies rules 1 to 4 only (FR-006). */
enum Mode:
  case Standard, OperatorOverride

/**
 * Where the claim window counts from (FR-005): a confirming deposit from the selection, the offer's days
 * else 3; every other path from registration, and only when the offer sets days.
 */
enum Anchor:
  case Registration(at: Instant)
  case Selection(at: Instant)

object Anchor:
  /** Registration, or now when no registration time is known: never expired (`EE:477-492`). */
  def registration(registeredAt: Option[Instant], now: Instant): Anchor = Registration(registeredAt.getOrElse(now))

/** The deposit evaluated against: its amount in minor units, its currency, and the wallet's ordinal (FR-012). */
final case class Deposit(amount: Long, currency: Currency, number: Int)

/** How a reward the player holds counts: not yet claimed (available, pending), open, or ended. */
enum Standing:
  case Unclaimed, Open, Ended

object Standing:
  private val unclaimed = Set("AVAILABLE", "PENDING")
  private val open      = Set("ACTIVE", "CONVERTED_PENDING_CAP", "PAUSED")

  /** One of the ten states by name, in any case (FR-009). */
  def of(status: String): Standing =
    val s = status.trim.toUpperCase(Locale.ROOT)
    if unclaimed.contains(s) then Unclaimed else if open.contains(s) then Open else Ended

/** A reward the player holds, by offer; `providerFailed` is one the game provider failed (FR-023). */
final case class Held(offerId: String, standing: Standing, providerFailed: Boolean = false):
  /** A claim of its offer: anything not available or pending, but not one the provider failed (`EE:141-150`). */
  def claim: Boolean = standing != Standing.Unclaimed && !providerFailed

/**
 * What the evaluator reads of the player (FR-008), from `PlayerRewards`: the registration time, the
 * duplicate check in progress, the weak signal's block, the suspected duplicate, the segments and tier rank
 * (dormant: none until something writes them, research R4), and the rewards held.
 */
final case class PlayerSnapshot(
    registeredAt: Option[Instant] = None,
    checkInProgress: Boolean = false,
    noDepositBlocked: Boolean = false,
    suspectedDuplicate: Boolean = false,
    segments: Option[Set[String]] = None,
    tierRank: Option[Int] = None,
    rewards: Vector[Held] = Vector.empty
)

/**
 * One evaluation's inputs from its path (the original's `GrantContext`). `walletCurrency` is the player's,
 * the last choice of FR-007's currency. `detection` is the brand's duplicate detection (rules 1 to 3).
 * `codeVerifiedAtTargeting` skips rule 8. `enforceMinDeposit`, when set, overrides the derived enforcement.
 */
final case class Request(
    mode: Mode,
    anchor: Anchor,
    walletCurrency: Currency,
    code: Option[String] = None,
    deposit: Option[Deposit] = None,
    detection: Boolean = true,
    codeVerifiedAtTargeting: Boolean = false,
    enforceMinDeposit: Option[Boolean] = None
)

/**
 * The one evaluator (FR-005; the original's `EligibilityEvaluator`, research R4), pure over its inputs:
 * the offer, the player, the wallet's open records, the request and the time. Rules run in R4's order and
 * the first decline is answered; an eligible offer is answered its `Decision`.
 */
object Evaluator:
  /** How long after registration a duplicate check in progress is honoured (`EE:325-342`). */
  val CheckWindow: Duration = Duration.ofMinutes(10)

  /** The claim window from a selection when the offer sets none. */
  val SelectionWindowDays: Int = 3

  private type Rule = (Offer, PlayerSnapshot, Int, Request, Instant) => Option[Decline]

  def evaluate(offer: Offer, player: PlayerSnapshot, walletOpen: Int, request: Request, now: Instant): Either[Decline, Decision] =
    val rules = if request.mode == Mode.OperatorOverride then always else always ++ standardOnly
    rules.iterator.flatMap(_(offer, player, walletOpen, request, now)).nextOption() match
      case Some(decline) => Left(decline)
      case None =>
        Amounts.computeBonusAmount(offer, request.deposit.map(_.amount), enforced(offer, request),
          Amounts.currencyOf(offer, request.deposit, request.walletCurrency))

  /** Open rewards: the player's, or the wallet's open records if more (`EE:164-165`). */
  def openCount(player: PlayerSnapshot, walletOpen: Int): Int =
    player.rewards.count(_.standing == Standing.Open).max(walletOpen)

  /** Whether the player holds a claim of the offer: the award's `ALREADY_CLAIMED` (`CW:174-183`). */
  def alreadyClaimed(player: PlayerSnapshot, offerId: String): Boolean =
    player.rewards.exists(h => h.claim && h.offerId == offerId)

  private def claimed(p: PlayerSnapshot): Vector[String] = p.rewards.filter(_.claim).map(_.offerId)
  private def openOffers(p: PlayerSnapshot): Set[String] = p.rewards.filter(_.standing == Standing.Open).map(_.offerId).toSet

  /** Rule 9 and the amount agree: an operator's award does not enforce the minimum unless told to. */
  private def enforced(o: Offer, r: Request): Boolean =
    r.enforceMinDeposit.getOrElse(r.mode == Mode.Standard && !o.noDepositRequired)

  private def when(cond: Boolean, d: Decline): Option[Decline] = Option.when(cond)(d)

  private val always: Vector[Rule] = Vector(
    // 1. A no-deposit offer while the player's duplicate check is in progress, inside ten minutes of registration.
    (o, p, _, r, now) =>
      when(r.detection && o.noDepositRequired && p.checkInProgress && p.registeredAt.exists(at => now.isBefore(at.plus(CheckWindow))),
        Decline.EligibilityCheckUnavailable),
    // 2. A no-deposit offer while a weak duplicate signal blocks them.
    (o, p, _, r, _) => when(r.detection && o.noDepositRequired && p.noDepositBlocked, Decline.NoDepositOfferBlocked),
    // 3. Any offer for a suspected duplicate.
    (_, p, _, r, _) => when(r.detection && p.suspectedDuplicate, Decline.SuspectedDuplicate),
    // 4. The offer's maximum of open rewards; none is no limit.
    (o, p, w, _, _) => when(o.maxConcurrentGrants.exists(openCount(p, w) >= _), Decline.ActiveBonusConflict)
  )

  private val standardOnly: Vector[Rule] = Vector(
    // 5. An offer it conflicts with held open.
    (o, p, _, _, _) => when(o.conflictsWith.exists(openOffers(p).contains), Decline.ConflictingGrant),
    // 6. The maximum claims per player; 0 is no limit.
    (o, p, _, _, _) => when(o.maxClaimsPerPlayer.filter(_ > 0).exists(max => claimed(p).count(_ == o.id) >= max), Decline.MaxClaimsReached),
    // 7. An offer it excludes already claimed.
    (o, p, _, _, _) => when(o.excludedOfferIds.exists(claimed(p).toSet.contains), Decline.ExcludedByPriorOffer),
    // 8. The code, when the offer carries codes and targeting did not verify it.
    (o, _, _, r, _) =>
      val valid = o.redemptionCodes.map(c => Codes.normalise(c.code)).filter(_.nonEmpty).toSet
      when(!r.codeVerifiedAtTargeting && valid.nonEmpty && !r.code.map(Codes.normalise).exists(valid.contains), Decline.InvalidCode),
    // 9. The minimum deposit, against a deposit when there is one (with none, the amount below answers it).
    (o, _, _, r, _) =>
      when(enforced(o, r) && r.deposit.exists(_.amount < o.minDepositAmount.getOrElse(0L)), Decline.BelowMinDeposit),
    // 10. The deposit number: absent is ANY.
    (o, _, _, r, _) => when(r.deposit.exists(d => o.depositNumberTarget.exists(_ != d.number)), Decline.DepositNumberMismatch),
    // 11. The claim window by its anchor; 0 days is none set.
    (o, _, _, r, now) =>
      val days = o.claimWindowDays.filter(_ > 0)
      def expired(from: Instant, d: Int) = now.isAfter(from.plus(d.toLong, ChronoUnit.DAYS))
      val out = r.anchor match
        case Anchor.Registration(at) => days.exists(expired(at, _))
        case Anchor.Selection(at)    => expired(at, days.getOrElse(SelectionWindowDays))
      when(out, Decline.ClaimWindowExpired),
    // 12. The segment, when the offer names one and the player's segments are known (dormant).
    (o, p, _, _, _) => when(o.segmentPredicate.exists(s => p.segments.exists(!_.contains(s))), Decline.SegmentMismatch),
    // 13. The tier floor, when the player's rank is known (dormant).
    (o, p, _, _, _) => when(o.vipTierFloor.exists(f => p.tierRank.exists(_ < f)), Decline.VipTierTooLow)
  )
