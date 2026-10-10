package io.eitheror.casino.wallet.entity

import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.wallet.funding.{CapDraw, Forfeit, Leg, RecordUpdate}

import java.time.Instant

/**
 * Everything that happens to a wallet (data-model.md). Past tense, a closed set, and each carries
 * everything its fold needs, absolute values rather than deltas where a value is set, and the time
 * it happened: the fold reads no clock and computes nothing it could get wrong on replay.
 *
 * Every money event carries the key it was sent under, the command's digest and the outcome it was
 * answered with, so the fold records the key with the same event that moves the money (FR-002).
 * `realAfter` and `restrictedAfter` are what the publisher copies onto the fact (FR-031).
 *
 * Field names are the journal's JSON: never rename one; add fields with defaults.
 */
enum WalletEvent:
  case WalletOpenedFromMigration(key: String, digest: String, realBalance: Long, depositTurnoverPending: Long,
      records: Vector[AwardedRecord], totalDeposits: Long, depositOrdinal: Int, restrictedWithoutRecords: Long,
      at: Instant, outcome: Outcome, openBets: Vector[Bet] = Vector.empty,
      withdrawals: Vector[WithdrawalRequest] = Vector.empty, pendingRequestId: Option[String] = None)

  case DepositConfirmed(key: String, digest: String, transactionId: String, amount: Long, paymentProvider: String,
      terminal: String, psp: String, scheme: String, terms: TurnoverTerms, requiredTurnover: Long, depositOrdinal: Int,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  case BetPlaced(key: String, digest: String, bet: Bet, provider: String, conversions: Vector[Leg],
      recordUpdates: Vector[RecordUpdate], turnoverReduced: Long, balanceBefore: Long, balanceAfter: Long,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  case WinCredited(key: String, digest: String, transactionId: String, roundKey: String, gameId: String, amount: Long,
      realShare: Long, recordShares: Vector[Leg], walletType: String, settledBets: Vector[String],
      recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  case BetRolledBack(key: String, digest: String, transactionId: String, roundKey: String, amount: Long,
      realRestored: Long, recordRestored: Vector[Leg], voidedBets: Vector[String], recordUpdates: Vector[RecordUpdate],
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  case RoundSettledAsLoss(key: String, digest: String, transactionId: String, roundKey: String,
      lostBets: Vector[String], forfeited: Vector[Forfeit], recordUpdates: Vector[RecordUpdate], realAfter: Long,
      restrictedAfter: Long, at: Instant, outcome: Outcome)

  case RestrictedFundsAwarded(key: String, digest: String, transactionId: String, awarded: AwardedRecord,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  /** `cause`: forfeit | loss | adjustment. Each forfeit names the record, its award and the balance zeroed. */
  case RestrictedFundsForfeited(key: String, digest: String, transactionId: String, forfeits: Vector[Forfeit],
      cause: String, recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long, at: Instant,
      outcome: Outcome)

  /** A cancel: the clawback applied (clamped) and, when one was open, the record forfeited (FR-017). */
  case RestrictedFundsCancelled(key: String, digest: String, transactionId: String, forfeits: Vector[Forfeit],
      clawback: Long, balanceBefore: Long, recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long,
      at: Instant, outcome: Outcome)

  case PendingCapClosed(key: String, digest: String, transactionId: String, recordId: String,
      recordUpdates: Vector[RecordUpdate], at: Instant, outcome: Outcome)

  case PrimarySwitched(key: String, digest: String, recordId: String, newPriority: Long, at: Instant, outcome: Outcome)

  case HoldPlaced(key: String, digest: String, transactionId: String, requestId: String, amount: Long,
      forfeits: Vector[Forfeit], clawback: Long, capDraws: Vector[CapDraw], voided: Long, paid: Long,
      cancelGrants: Vector[String], confirmationRequired: Boolean, recordUpdates: Vector[RecordUpdate], realAfter: Long,
      restrictedAfter: Long, at: Instant, outcome: Outcome)

  /** `releasedAs`: cancelled | rejected | failed. */
  case HoldReleased(key: String, digest: String, transactionId: String, requestId: String, amount: Long,
      releasedAs: String, realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  case HoldConfirmed(key: String, digest: String, requestId: String, at: Instant, outcome: Outcome)

  case WithdrawalReversed(key: String, digest: String, transactionId: String, requestId: String, amount: Long,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  /** The reason is the operator's free text, personal under the player (FR-033); `category` is not. */
  case AdminAdjusted(key: String, digest: String, transactionId: String, target: String, amount: Long,
      reason: Personal[String], category: String, actorId: String, recordId: Option[String],
      forfeited: Option[Forfeit], recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long,
      at: Instant, outcome: Outcome)

  /** `landedOn`: real | restricted (FR-027). */
  case ProviderCredited(key: String, digest: String, transactionId: String, amount: Long, providerRef: String,
      reason: String, actorId: String, landedOn: String, recordId: Option[String], recordUpdates: Vector[RecordUpdate],
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome)

  case TurnoverOverrideSet(terms: Option[TurnoverTerms], actorId: String, at: Instant)

  /** A refusal recorded under its key, so a repeat is refused the same way (research R3). */
  case CommandRefused(key: String, digest: String, commandType: String, code: String, message: String,
      amount: Option[Long], at: Instant, outcome: Outcome)
