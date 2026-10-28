package io.eitheror.casino.wallet.funding

/**
 * The funding resolver's types, ported from the original's
 * `starter-packs/wallet/handlers-go/internal/funding/types.go` field for field (research R2).
 *
 * Money is `Long` minor units and arithmetic is truncating integer division, as in Go. Statuses,
 * funding orders, allocations and policies stay the original's strings so its table tests port case
 * for case; the empty string is the original's "unset", with the same defaults.
 *
 * Nothing in this package knows about ankka: every function is pure over immutable values.
 */
object Status:
  val Active              = "ACTIVE"
  val ConvertedPendingCap = "CONVERTED_PENDING_CAP"
  val Forfeited           = "FORFEITED"
  val Closed              = "CLOSED"

  /** Open means ACTIVE or CONVERTED_PENDING_CAP (spec 002 FR-020). */
  def isOpen(status: String): Boolean = status == Active || status == ConvertedPendingCap

/**
 * The funding-relevant slice of the wallet (types.go `Wallet`). `pools` is the wallet's deposit and
 * converted pools (spec 025): the entity always passes them; `None` is the original's unstamped
 * wallet and exists only so the resolver's tables that predate the pools run unchanged, as the
 * original's do (`TestPlaceBetPools_UnstampedUnchanged`): no pool step, no parts.
 */
final case class Wallet(realBalance: Long, depositTurnoverPending: Long = 0L, restrictedBalance: Long = 0L,
    pools: Option[Pools] = None)

/**
 * One restricted-funds record with its rule snapshot (types.go `Record`). Records reach the resolver
 * filtered to open and ordered by priority then granted time (`Records.ordered`).
 */
final case class Record(
    id: String,
    grantRef: String = "",
    status: String = Status.Active,
    priority: Long = 0L,
    grantedAt: String = "",
    balance: Long = 0L,
    releaseRequirement: Long = 0L,
    releasedProgress: Long = 0L,
    maxConvertible: Long = 0L,
    remainingAllowance: Long = 0L,
    depositsSinceRelease: Long = 0L,
    openRoundCount: Long = 0L,
    /** None = no limit. */
    maxStake: Option[Long] = None,
    /** "" | block | cap */
    maxStakeEnforcement: String = "",
    /** None = fall through to the brand's rate. */
    contributionDefault: Option[Long] = None,
    /** key -> rate %; key = "game:<id>" | "provider:<id>" | "category:<id>" */
    contributionOverrides: Map[String, Long] = Map.empty,
    scopeInclude: Set[String] = Set.empty,
    scopeExclude: Set[String] = Set.empty,
    /** "" | zero_contribution | block */
    scopeViolationMode: String = "",
    /** "" | real_first | restricted_first | proportional | combined */
    fundingOrder: String = "",
    /** "" | follow_source | all_restricted */
    winAllocation: String = "",
    /** "" | funding_grant | all_open */
    releaseProgressScope: String = "",
    /** "" | none | funds | funds_and_winnings | cancel_promotion ("" = funds_and_winnings) */
    forfeitOnWithdrawal: String = ""
)

/** The bet's game, for scope and rate matching. */
final case class Game(provider: String = "", gameId: String = "", category: String = "")

/** The brand's and the request's switches PlaceBet consults (types.go `Flags`). */
final case class Flags(
    partialSplits: Boolean = true,
    bonusWageringCounts: Boolean = true,
    allowNegative: Boolean = false,
    defaultRate: Long = 100L
)

final case class Stake(amount: Long, game: Game = Game())

/** A resolver refusal; never returned with a plan. */
final case class Deny(code: String, message: String)

object Codes:
  val InsufficientFunds          = "INSUFFICIENT_FUNDS"
  val MaxStakeExceeded           = "MAX_STAKE_EXCEEDED"
  val ScopeBlocked               = "SCOPE_BLOCKED"
  val RestrictedFundsUnmigrated  = "RESTRICTED_FUNDS_UNMIGRATED"
  val RestrictedFundsUnavailable = "RESTRICTED_FUNDS_UNAVAILABLE"
  val NoOpenRestrictedFunds      = "NO_OPEN_RESTRICTED_FUNDS"
  val NoActiveBonus              = "NO_ACTIVE_BONUS"
  val NotPendingCap              = "NOT_PENDING_CAP"
  val DebitExceedsBalance        = "DEBIT_EXCEEDS_BALANCE"
  val GrantNotOpen               = "GRANT_NOT_OPEN"
  /** A pool step whose pools would not sum to real balance (spec 025; poolstep.go `CodePoolsInvariant`). */
  val PoolsInvariant             = "POOLS_INVARIANT"

  val WithdrawalRequestAlreadyPending         = "WITHDRAWAL_REQUEST_ALREADY_PENDING"
  val WithdrawalBlockedTurnover               = "WITHDRAWAL_BLOCKED_TURNOVER"
  val WithdrawalInsufficientAfterBonusForfeit = "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT"

/** The original's messages, verbatim. */
object Messages:
  val InsufficientFunds                       = "Insufficient funds in real and bonus wallets"
  val WithdrawalRequestAlreadyPending         = "A withdrawal request is already pending review"
  val InsufficientRealBalance                 = "Insufficient real balance for withdrawal"
  val WithdrawalBlockedTurnover               = "Deposit turnover requirements not yet met"
  val WithdrawalInsufficientAfterBonusForfeit = "insufficient real balance after bonus forfeiture"
  val NoActiveBonusToForfeit                  = "No active bonus to forfeit"
  val NoPendingCapToClose                     = "No CONVERTED_PENDING_CAP bonus to close"

/** One funding line against a record. The real leg travels separately. */
final case class Leg(recordId: String, amount: Long)

/**
 * A bet's funding split: the real leg and the record legs, and the parts of the real leg the bet
 * recorded (spec 025; types.go `Legs.RealSplit`). `None` is a bet with no recorded parts: its win
 * and its rollback go to the converted pool.
 */
final case class Legs(real: Long = 0L, records: Vector[Leg] = Vector.empty, realParts: Option[Parts] = None)

/** One touched record's absolute values after a plan (types.go `RecordUpdate`). */
final case class RecordUpdate(
    id: String,
    balance: Long,
    releaseRequirement: Long,
    releasedProgress: Long,
    maxConvertible: Long,
    remainingAllowance: Long,
    depositsSinceRelease: Long,
    openRoundCount: Long,
    status: String,
    closedNow: Boolean,
    grantRef: String
):
  /** The record with these values written onto it. */
  def applyTo(r: Record): Record =
    r.copy(
      balance = balance,
      releaseRequirement = releaseRequirement,
      releasedProgress = releasedProgress,
      maxConvertible = maxConvertible,
      remainingAllowance = remainingAllowance,
      depositsSinceRelease = depositsSinceRelease,
      openRoundCount = openRoundCount,
      status = status
    )

object RecordUpdate:
  def from(r: Record, closedNow: Boolean): RecordUpdate =
    RecordUpdate(r.id, r.balance, r.releaseRequirement, r.releasedProgress, r.maxConvertible, r.remainingAllowance,
      r.depositsSinceRelease, r.openRoundCount, r.status, closedNow, r.grantRef)

/** Σ balance over the open records after a plan; `present` false when nothing is open and nothing closed. */
final case class Projection(present: Boolean = false, restrictedBalance: Long = 0L)

final case class Plan(
    realLeg: Long,
    recordLegs: Vector[Leg],
    contributions: Vector[Leg],
    converting: Vector[String],
    recordUpdates: Vector[RecordUpdate],
    newRealBalance: Long,
    balanceBefore: Long,
    balanceAfter: Long,
    walletType: String,
    projection: Projection,
    /** The real leg's parts (spec 025 FR-003); zero on an unstamped wallet. */
    parts: Parts = Parts.zero,
    newPools: Option[Pools] = None
)

final case class WinPlan(
    realShare: Long,
    recordShares: Vector[Leg],
    newRealBalance: Long,
    recordUpdates: Vector[RecordUpdate],
    walletType: String,
    splitRealAmount: Long,
    splitBonusAmount: Long,
    orphanedToReal: Vector[Leg],
    projection: Projection,
    /** The real share's parts (spec 025 FR-004). */
    parts: Parts = Parts.zero,
    newPools: Option[Pools] = None
)

final case class RollbackPlan(
    realRestored: Long,
    recordRestored: Vector[Leg],
    progressReversed: Vector[Leg],
    newRealBalance: Long,
    recordUpdates: Vector[RecordUpdate],
    walletType: String,
    projection: Projection,
    /** The parts restored (spec 025 FR-005). */
    parts: Parts = Parts.zero,
    newPools: Option[Pools] = None
)

final case class LossPlan(
    forfeited: Vector[String],
    recordUpdates: Vector[RecordUpdate],
    walletType: String,
    projection: Projection
)

final case class ClosePlan(
    closedRecords: Vector[String],
    recordUpdates: Vector[RecordUpdate],
    projection: Projection,
    forfeitedAmount: Long = 0L
)

final case class CancelPlan(
    closedRecords: Vector[String],
    recordUpdates: Vector[RecordUpdate],
    projection: Projection,
    bonusBalanceBefore: Long,
    clawbackApplied: Long,
    newRealBalance: Long,
    /** The clawback's parts, converted first (spec 025 FR-009). */
    parts: Parts = Parts.zero,
    newPools: Option[Pools] = None
)

final case class AdjustPlan(
    recordId: String,
    balanceBefore: Long,
    balanceAfter: Long,
    recordUpdates: Vector[RecordUpdate],
    projection: Projection,
    closedRecords: Vector[String] = Vector.empty
)

final case class CreditPlan(
    /** "restricted" | "real" */
    target: String,
    recordId: String = "",
    balanceBefore: Long = 0L,
    balanceAfter: Long = 0L,
    recordUpdates: Vector[RecordUpdate] = Vector.empty,
    newRealBalance: Long = 0L,
    projection: Projection = Projection(),
    /** On real: all converted (spec 025 FR-010). */
    parts: Parts = Parts.zero,
    newPools: Option[Pools] = None
)

object Policy:
  val None             = "none"
  val Funds            = "funds"
  val FundsAndWinnings = "funds_and_winnings"
  val CancelPromotion  = "cancel_promotion"

final case class WithdrawInput(
    amount: Long,
    /** Computed by the wallet from the forfeited records' baselines (FR-015a), applied at most once. */
    clawback: Long = 0L,
    confirm: Boolean = false,
    pendingWithdrawalRequestRef: String = ""
)

final case class Forfeit(recordId: String, amount: Long, policy: String, cancelGrant: Boolean)

final case class CapDraw(recordId: String, fromDeposits: Long, fromAllowance: Long, closes: Boolean)

final case class WithdrawPlan(
    forfeits: Vector[Forfeit],
    clawback: Long,
    capDraws: Vector[CapDraw],
    paid: Long,
    voided: Long,
    newReal: Long,
    restrictedBalance: Long,
    recordUpdates: Vector[RecordUpdate],
    cancelGrants: Vector[String],
    requiresConfirmation: Boolean,
    /** The three movements' parts, applied in this order (spec 025 FR-008; withdraw.go `stepPoolsSeq`). */
    clawbackParts: Parts = Parts.zero,
    voidedParts: Parts = Parts.zero,
    paidParts: Parts = Parts.zero,
    newPools: Option[Pools] = None
)
