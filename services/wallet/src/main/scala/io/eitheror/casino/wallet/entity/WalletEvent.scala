package io.eitheror.casino.wallet.entity

import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.wallet.funding.{CapDraw, Forfeit, Leg, Parts, RecordUpdate}

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
 * Since spec 025 every event with a `realAfter` also carries the two pools after it (`depositAfter`,
 * `convertedAfter`, which the fold sets), and each that moves real balance the parts the resolver
 * decided for that movement (`parts`; a bet's are on its `Bet`; a hold's are its three movements').
 *
 * Field names are the journal's JSON: never rename one; add fields with defaults.
 */
enum WalletEvent:
  case WalletOpenedFromMigration(key: String, digest: String, realBalance: Long, depositTurnoverPending: Long,
      records: Vector[AwardedRecord], totalDeposits: Long, depositOrdinal: Int, restrictedWithoutRecords: Long,
      at: Instant, outcome: Outcome, openBets: Vector[Bet] = Vector.empty,
      withdrawals: Vector[WithdrawalRequest] = Vector.empty, pendingRequestId: Option[String] = None,
      depositPool: Long = 0L, convertedPool: Long = 0L)

  case DepositConfirmed(key: String, digest: String, transactionId: String, amount: Long, paymentProvider: String,
      terminal: String, psp: String, scheme: String, terms: TurnoverTerms, requiredTurnover: Long, depositOrdinal: Int,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome, parts: Parts = Parts.zero,
      depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case BetPlaced(key: String, digest: String, bet: Bet, provider: String, conversions: Vector[Leg],
      recordUpdates: Vector[RecordUpdate], turnoverReduced: Long, balanceBefore: Long, balanceAfter: Long,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome, depositAfter: Long = 0L,
      convertedAfter: Long = 0L)

  case WinCredited(key: String, digest: String, transactionId: String, roundKey: String, gameId: String, amount: Long,
      realShare: Long, recordShares: Vector[Leg], walletType: String, settledBets: Vector[String],
      recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome,
      parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L,
      turnoverClosed: Boolean = false)

  case BetRolledBack(key: String, digest: String, transactionId: String, roundKey: String, amount: Long,
      realRestored: Long, recordRestored: Vector[Leg], voidedBets: Vector[String], recordUpdates: Vector[RecordUpdate],
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome, parts: Parts = Parts.zero,
      depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case RoundSettledAsLoss(key: String, digest: String, transactionId: String, roundKey: String,
      lostBets: Vector[String], forfeited: Vector[Forfeit], recordUpdates: Vector[RecordUpdate], realAfter: Long,
      restrictedAfter: Long, at: Instant, outcome: Outcome, depositAfter: Long = 0L, convertedAfter: Long = 0L,
      turnoverClosed: Boolean = false)

  case RestrictedFundsAwarded(key: String, digest: String, transactionId: String, awarded: AwardedRecord,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome, depositAfter: Long = 0L,
      convertedAfter: Long = 0L)

  /** `cause`: forfeit | loss | adjustment. Each forfeit names the record, its award and the balance zeroed. */
  case RestrictedFundsForfeited(key: String, digest: String, transactionId: String, forfeits: Vector[Forfeit],
      cause: String, recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long, at: Instant,
      outcome: Outcome, depositAfter: Long = 0L, convertedAfter: Long = 0L)

  /** A cancel: the clawback applied (clamped) and, when one was open, the record forfeited (FR-017). */
  case RestrictedFundsCancelled(key: String, digest: String, transactionId: String, forfeits: Vector[Forfeit],
      clawback: Long, balanceBefore: Long, recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long,
      at: Instant, outcome: Outcome, parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case PendingCapClosed(key: String, digest: String, transactionId: String, recordId: String,
      recordUpdates: Vector[RecordUpdate], at: Instant, outcome: Outcome)

  case PrimarySwitched(key: String, digest: String, recordId: String, newPriority: Long, at: Instant, outcome: Outcome)

  /** The parts of the clawback, the void and the payout, taken in that order (spec 025 FR-008). */
  case HoldPlaced(key: String, digest: String, transactionId: String, requestId: String, amount: Long,
      forfeits: Vector[Forfeit], clawback: Long, capDraws: Vector[CapDraw], voided: Long, paid: Long,
      cancelGrants: Vector[String], confirmationRequired: Boolean, recordUpdates: Vector[RecordUpdate], realAfter: Long,
      restrictedAfter: Long, at: Instant, outcome: Outcome, clawbackParts: Parts = Parts.zero,
      voidedParts: Parts = Parts.zero, paidParts: Parts = Parts.zero, depositAfter: Long = 0L,
      convertedAfter: Long = 0L)

  /** `releasedAs`: cancelled | rejected | failed. */
  case HoldReleased(key: String, digest: String, transactionId: String, requestId: String, amount: Long,
      releasedAs: String, realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome,
      parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case HoldConfirmed(key: String, digest: String, requestId: String, at: Instant, outcome: Outcome)

  case WithdrawalReversed(key: String, digest: String, transactionId: String, requestId: String, amount: Long,
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome, parts: Parts = Parts.zero,
      depositAfter: Long = 0L, convertedAfter: Long = 0L)

  /** The reason is the operator's free text, personal under the player (FR-033); `category` is not. */
  case AdminAdjusted(key: String, digest: String, transactionId: String, target: String, amount: Long,
      reason: Personal[String], category: String, actorId: String, recordId: Option[String],
      forfeited: Option[Forfeit], recordUpdates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long,
      at: Instant, outcome: Outcome, parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L,
      turnoverClosed: Boolean = false)

  /** `landedOn`: real | restricted (FR-027). */
  case ProviderCredited(key: String, digest: String, transactionId: String, amount: Long, providerRef: String,
      reason: String, actorId: String, landedOn: String, recordId: Option[String], recordUpdates: Vector[RecordUpdate],
      realAfter: Long, restrictedAfter: Long, at: Instant, outcome: Outcome, parts: Parts = Parts.zero,
      depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case TurnoverOverrideSet(terms: Option[TurnoverTerms], actorId: String, at: Instant)

  // ── spec 022 (data-model.md): each carries the reserved balance after beside real and restricted, and,
  // as every money event, both pools after it and the parts of any real balance it moves (spec 025) ──

  case ReservationPlaced(key: String, digest: String, transactionId: String, orderId: String, amount: Long,
      realAfter: Long, restrictedAfter: Long, reservedAfter: Long, at: Instant, outcome: Outcome,
      depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case ReservationReleased(key: String, digest: String, transactionId: String, orderId: String, amount: Long,
      realAfter: Long, restrictedAfter: Long, reservedAfter: Long, at: Instant, outcome: Outcome,
      depositAfter: Long = 0L, convertedAfter: Long = 0L)

  /** The buyer's side: real − amount; the order's reservation − amount − unused, removed at 0. */
  case SettlementDebited(key: String, digest: String, transactionId: String, tradeId: String, orderId: String, amount: Long,
      unused: Long, counterparty: String, realAfter: Long, restrictedAfter: Long, reservedAfter: Long, at: Instant, outcome: Outcome,
      parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L)

  case SettlementCredited(key: String, digest: String, transactionId: String, tradeId: String, amount: Long, counterparty: String,
      realAfter: Long, restrictedAfter: Long, reservedAfter: Long, at: Instant, outcome: Outcome,
      parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L)

  /** With `orderId`, charged from that order's reservation (FR-008a): the reservation − amount − unused, removed at 0. */
  case MovedToAccount(key: String, digest: String, transactionId: String, accountKind: String, accountName: String, purpose: String,
      reference: String, amount: Long, realAfter: Long, restrictedAfter: Long, reservedAfter: Long, at: Instant, outcome: Outcome,
      parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L, orderId: Option[String] = None, unused: Long = 0L)

  case MovedFromAccount(key: String, digest: String, transactionId: String, accountKind: String, accountName: String, purpose: String,
      reference: String, amount: Long, realAfter: Long, restrictedAfter: Long, reservedAfter: Long, at: Instant, outcome: Outcome,
      parts: Parts = Parts.zero, depositAfter: Long = 0L, convertedAfter: Long = 0L)

  /** A refusal recorded under its key, so a repeat is refused the same way (research R3). */
  case CommandRefused(key: String, digest: String, commandType: String, code: String, message: String,
      amount: Option[Long], at: Instant, outcome: Outcome)
