package io.eitheror.casino.rewards.eligibility

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.rewards.RewardsCodecs

/**
 * Why a player is declined (FR-005): exactly the original's reasons (`EE:102-120`), each with the name of
 * the rule that produced it, in R4's order. `AlreadyClaimed` is in the vocabulary and no rule's: it is the
 * answer to an award of an offer the player already holds a claim of (`Evaluator.alreadyClaimed`).
 */
enum Decline(val reason: String, val rule: String):
  case EligibilityCheckUnavailable extends Decline("ELIGIBILITY_CHECK_UNAVAILABLE", "pending duplicate check")
  case NoDepositOfferBlocked       extends Decline("NO_DEPOSIT_OFFER_BLOCKED", "no deposit offer blocked")
  case SuspectedDuplicate          extends Decline("SUSPECTED_DUPLICATE", "suspected duplicate")
  case ActiveBonusConflict         extends Decline("ACTIVE_BONUS_CONFLICT", "concurrency")
  case ConflictingGrant            extends Decline("CONFLICTING_GRANT", "conflicts")
  case MaxClaimsReached            extends Decline("MAX_CLAIMS_REACHED", "claims")
  case ExcludedByPriorOffer        extends Decline("EXCLUDED_BY_PRIOR_OFFER", "exclusions")
  case InvalidCode                 extends Decline("INVALID_CODE", "code")
  case BelowMinDeposit             extends Decline("BELOW_MIN_DEPOSIT", "min deposit")
  case DepositNumberMismatch       extends Decline("DEPOSIT_NUMBER_MISMATCH", "deposit number")
  case ClaimWindowExpired          extends Decline("CLAIM_WINDOW_EXPIRED", "claim window")
  case SegmentMismatch             extends Decline("SEGMENT_MISMATCH", "segment")
  case VipTierTooLow               extends Decline("VIP_TIER_TOO_LOW", "VIP floor")
  case AlreadyClaimed              extends Decline("ALREADY_CLAIMED", "already claimed")

object Decline:
  def from(text: String): Option[Decline] = values.find(_.reason.equalsIgnoreCase(text.trim))
  given JsonValueCodec[Decline]          = RewardsCodecs.wire(values, _.reason)

/**
 * What would allow a refused grant (spec 030 RW2): beside each refusal's reason, the conditions under which the same
 * grant would pass, read from the rule that refused it (`Evaluator`'s rules 1 to 13, FR-005, FR-006) or from the
 * wallet's refusal. Each is a code the backoffice words; an unknown reason allows by nothing it can name (empty).
 *
 *  - `duplicate_check_ended`: the player's duplicate check ends (rule 1).
 *  - `check_window_passed`: ten minutes pass since the player registered (rule 1).
 *  - `no_deposit_block_cleared`: the weak duplicate signal's block is cleared (rule 2).
 *  - `fraud_flag_cleared`: the player's fraud flag is cleared (rule 3).
 *  - `brand_detection_off`: the brand turns duplicate detection off (rules 1 to 3).
 *  - `offer_requiring_deposit`: an offer that requires a deposit (rules 1 and 2 hold only for no-deposit offers).
 *  - `open_reward_ended`: one of the player's open rewards ends (rule 4; the wallet's `BONUS_ALREADY_ACTIVE`).
 *  - `offer_limit_raised`: the offer's own limit is raised or removed (rules 4 and 6).
 *  - `brand_limit_raised`: the brand's maximum of concurrent awards is raised (the wallet's `BONUS_ALREADY_ACTIVE`).
 *  - `conflicting_reward_ended`: the open reward the offer conflicts with ends (rule 5).
 *  - `valid_code`: the offer's code entered (rule 8).
 *  - `larger_deposit`: a deposit at or above the offer's minimum (rule 9).
 *  - `operator_award`: an operator awards it, which applies rules 1 to 4 only and holds no claim check (FR-006).
 *  - `wallet_opened`: the player's wallet in the currency is opened, by a first deposit (the wallet's `WALLET_NOT_FOUND`).
 */
object AllowedBy:
  val DuplicateCheckEnded    = "duplicate_check_ended"
  val CheckWindowPassed      = "check_window_passed"
  val NoDepositBlockCleared  = "no_deposit_block_cleared"
  val FraudFlagCleared       = "fraud_flag_cleared"
  val BrandDetectionOff      = "brand_detection_off"
  val OfferRequiringDeposit  = "offer_requiring_deposit"
  val OpenRewardEnded        = "open_reward_ended"
  val OfferLimitRaised       = "offer_limit_raised"
  val BrandLimitRaised       = "brand_limit_raised"
  val ConflictingRewardEnded = "conflicting_reward_ended"
  val ValidCode              = "valid_code"
  val LargerDeposit          = "larger_deposit"
  val OperatorAward          = "operator_award"
  val WalletOpened           = "wallet_opened"

  /** The conditions under which a grant refused for `reason` would pass, in the order the backoffice shows them. */
  def of(reason: String): Vector[String] =
    Decline.from(reason) match
      case Some(d) => of(d)
      case None =>
        reason.trim.toUpperCase(java.util.Locale.ROOT) match
          case "BONUS_ALREADY_ACTIVE" => Vector(OpenRewardEnded, BrandLimitRaised)
          case "WALLET_NOT_FOUND"     => Vector(WalletOpened)
          case _                      => Vector.empty

  def of(d: Decline): Vector[String] = d match
    case Decline.EligibilityCheckUnavailable => Vector(DuplicateCheckEnded, CheckWindowPassed, OfferRequiringDeposit, BrandDetectionOff)
    case Decline.NoDepositOfferBlocked       => Vector(NoDepositBlockCleared, OfferRequiringDeposit, BrandDetectionOff)
    case Decline.SuspectedDuplicate          => Vector(FraudFlagCleared, BrandDetectionOff)
    case Decline.ActiveBonusConflict         => Vector(OpenRewardEnded, OfferLimitRaised)
    case Decline.ConflictingGrant            => Vector(ConflictingRewardEnded, OperatorAward)
    case Decline.MaxClaimsReached            => Vector(OfferLimitRaised, OperatorAward)
    case Decline.InvalidCode                 => Vector(ValidCode, OperatorAward)
    case Decline.BelowMinDeposit             => Vector(LargerDeposit, OperatorAward)
    case Decline.ExcludedByPriorOffer | Decline.DepositNumberMismatch | Decline.ClaimWindowExpired | Decline.SegmentMismatch |
        Decline.VipTierTooLow | Decline.AlreadyClaimed => Vector(OperatorAward)
