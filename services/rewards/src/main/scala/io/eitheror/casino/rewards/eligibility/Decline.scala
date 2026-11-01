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
