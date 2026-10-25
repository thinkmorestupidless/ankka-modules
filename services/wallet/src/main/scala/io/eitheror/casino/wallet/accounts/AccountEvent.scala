package io.eitheror.casino.wallet.accounts

import io.eitheror.casino.wallet.entity.Outcome

import java.time.Instant

/**
 * Everything that happens to a non-player account (data-model.md). A closed set; each carries the
 * absolute balance after, so the fold computes nothing. The keyed events carry the key, the digest
 * and the outcome, so the fold records the key with the event that moves the money, as the wallet's.
 *
 * Field names are the journal's JSON: never rename one; add fields with defaults.
 */
enum AccountEvent:
  case AccountClaimed(owner: String, at: Instant)
  case AccountDebited(key: String, digest: String, transactionId: String, amount: Long, purpose: String, counterpartyPlayerId: String,
      reference: String, balanceAfter: Long, at: Instant, outcome: Outcome)
  case AccountCredited(key: String, digest: String, transactionId: String, amount: Long, purpose: String, counterpartyPlayerId: String,
      reference: String, balanceAfter: Long, at: Instant, outcome: Outcome)
  /** An operator's adjustment (FR-009). `reason` is the operator's text, never published; `category` is. */
  case AccountAdjusted(key: String, digest: String, transactionId: String, amount: Long, reason: String, category: String,
      actorId: String, balanceAfter: Long, creditLine: Long, at: Instant, outcome: Outcome)
  case CreditLineSet(creditLine: Long, actor: String, at: Instant)
  case CommandRefused(key: String, digest: String, commandType: String, code: String, message: String, details: Map[String, String],
      at: Instant, outcome: Outcome)
