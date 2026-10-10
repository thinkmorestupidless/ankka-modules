package io.eitheror.casino.wallet.entity

import io.eitheror.casino.wallet.funding.{Clawback, Forfeit, Leg, Record}

import java.time.Instant

/**
 * A restricted-funds record as the wallet holds it: the resolver's record (balance, progress, rule
 * snapshot) plus what only the wallet keeps (spec 002 Key Entities): the bonus reference, the
 * expiry shown in status, when it was awarded, and the clawback baseline (FR-015a).
 */
final case class AwardedRecord(
    record: Record,
    bonusRef: String,
    expiresAt: Option[Instant],
    awardedAt: Instant,
    /** None for a record the migration carried over without the original's snapshot (FR-017). */
    baseline: Option[Clawback.Baseline]
):
  def id: String       = record.id
  def grantRef: String = record.grantRef
  def status: String   = record.status

/** A bet while its round is open, and for a while after (a late rollback finds it). */
final case class Bet(
    key: String,
    roundKey: String,
    transactionId: String,
    amount: Long,
    realLeg: Long,
    recordLegs: Vector[Leg],
    contributions: Vector[Leg],
    walletType: String,
    status: String,
    sessionId: String,
    gameId: String,
    placedAt: Instant
)

object BetStatus:
  val Open    = "OPEN"
  val Settled = "SETTLED"
  val Voided  = "VOIDED"
  val Loss    = "LOSS"

/** A withdrawal hold (FR-023 to FR-025). A confirmed one is kept so a reversal can find it. */
final case class WithdrawalRequest(
    requestId: String,
    transactionId: String,
    amount: Long,
    status: String,
    heldAt: Instant,
    closedAt: Option[Instant] = None,
    forfeited: Vector[String] = Vector.empty,
    clawbackApplied: Long = 0L,
    voided: Long = 0L,
    confirmationRequired: Boolean = false
)

object RequestStatus:
  val Pending   = "PENDING"
  val Released  = "RELEASED"
  val Confirmed = "CONFIRMED"
  val Reversed  = "REVERSED"

/** The deposit turnover terms: the multiplier and the original's `turnover_definition` (FR-004a). */
final case class TurnoverTerms(multiplier: Long, definition: String)

object TurnoverTerms:
  /** The original's default: once the amount, gross wagered (deposit.tl). */
  val Default: TurnoverTerms = TurnoverTerms(1L, "gross_wagered")

/** A confirmed deposit, kept in state for 30 days for the sums `responsible-gambling` decides on (R5). */
final case class DepositRecord(
    paymentReference: String,
    amount: Long,
    terms: TurnoverTerms,
    requiredTurnover: Long,
    status: String,
    confirmedAt: Instant
)

/** The deposit sums of the last 1, 7 and 30 days, exact at `asOf` (FR-030). */
final case class DepositSums(last24h: Long, last7d: Long, last30d: Long)

/** A record's rule snapshot as an award carries it (User Story 2); the resolver's field names. */
final case class Rules(
    maxStake: Option[Long] = None,
    maxStakeEnforcement: String = "",
    contributionDefault: Option[Long] = None,
    contributionOverrides: Map[String, Long] = Map.empty,
    scopeInclude: Set[String] = Set.empty,
    scopeExclude: Set[String] = Set.empty,
    scopeViolationMode: String = "",
    fundingOrder: String = "",
    winAllocation: String = "",
    releaseProgressScope: String = "",
    forfeitOnWithdrawal: String = ""
)

/** The brand's bet switches as the bet endpoint read them from `BrandConfigEntity` (FR-004). */
final case class BetSwitches(
    contributionRate: Long = 100L,
    bonusWageringCounts: Boolean = true,
    partialSplitsAllowed: Boolean = true
)

/**
 * What a key remembers and what every money command replies (FR-002): accepted (no code) or
 * refused (a code and the original's message), with the fields the routes return. One shape for
 * every command keeps the key store uniform; the endpoint renders the parts its route names.
 */
final case class Outcome(
    code: Option[String] = None,
    message: Option[String] = None,
    details: Map[String, String] = Map.empty,
    /** The command's wire name and amount: what `KEY_REUSED` names of the first command. */
    commandType: String = "",
    amount: Option[Long] = None,
    transactionId: Option[String] = None,
    realBalance: Long = 0L,
    restrictedBalance: Long = 0L,
    walletType: Option[String] = None,
    realLeg: Option[Long] = None,
    recordLegs: Vector[Leg] = Vector.empty,
    conversions: Vector[String] = Vector.empty,
    forfeited: Vector[String] = Vector.empty,
    clawbackApplied: Option[Long] = None,
    recordId: Option[String] = None,
    wageringRequirement: Option[Long] = None,
    priority: Option[Long] = None,
    alreadyPrimary: Option[Boolean] = None,
    confirmationRequired: Option[Boolean] = None,
    voided: Option[Long] = None,
    paid: Option[Long] = None,
    cancelGrants: Vector[String] = Vector.empty,
    landedOn: Option[String] = None,
    depositOrdinal: Option[Int] = None,
    turnoverRequired: Option[Long] = None,
    depositSums: Option[DepositSums] = None,
    closedRecordId: Option[String] = None,
    balanceBefore: Option[Long] = None,
    balanceAfter: Option[Long] = None
):
  def accepted: Boolean = code.isEmpty

/** The primary record's status as balance reports it (FR-028). */
final case class PrimaryStatus(recordId: String, grantRef: String, status: String, requirement: Long, wagered: Long, progressPercent: Long)

/** The balance read (FR-028). */
final case class Balance(realBalance: Long, restrictedBalance: Long, currency: String, primary: Option[PrimaryStatus])

/** One open record as the restricted-funds status lists it (FR-028, User Story 6 scenario 2). */
final case class RecordStatus(
    recordId: String,
    grantRef: String,
    bonusRef: String,
    status: String,
    priority: Long,
    grantedAt: String,
    expiresAt: Option[Instant],
    balance: Long,
    wageringRequirement: Long,
    releaseProgress: Long,
    maxConvertible: Long,
    remainingAllowance: Long,
    depositsSinceConversion: Long,
    openRoundCount: Long,
    /** The record's withdrawal policy (reads.feature: listed with each record). */
    forfeitOnWithdrawal: String = "funds_and_winnings"
)

final case class RestrictedFundsStatus(records: Vector[RecordStatus], headline: Option[RecordStatus], restrictedBalance: Long)

/** What a hold would do, moving nothing (FR-023a). */
final case class HoldPreview(
    refusal: Option[String],
    message: Option[String],
    confirmationRequired: Boolean,
    bonusBalance: Long,
    clawback: Long,
    totalForfeit: Long,
    mostWithdrawable: Long,
    forfeits: Vector[Forfeit]
)
