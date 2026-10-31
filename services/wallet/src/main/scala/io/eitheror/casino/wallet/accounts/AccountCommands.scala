package io.eitheror.casino.wallet.accounts

import java.time.Instant

/**
 * The account's commands (data-model.md). `Debit`, `Credit` and `Adjust` move money: keyed, and
 * digested by `MoneyCommand.digestOf`'s rule, so a repeat answers the first outcome and a changed
 * command under a key is `KEY_REUSED`. `Claim` and `SetCreditLine` carry no key: a repeat of either
 * by the owner changes nothing.
 */
sealed trait AccountMoneyCommand extends Product:
  def key: String
  def amount: Long
  def at: Instant

/** The first move's claim: the caller becomes the owner of an account no one owns. */
final case class Claim(owner: String, at: Instant)

final case class Debit(key: String, owner: String, amount: Long, purpose: String, counterpartyPlayerId: String, reference: String,
    at: Instant) extends AccountMoneyCommand

final case class Credit(key: String, owner: String, amount: Long, purpose: String, counterpartyPlayerId: String, reference: String,
    at: Instant) extends AccountMoneyCommand

/** An operator's correction (FR-009): signed, with the operator's reason and a category. */
final case class AdjustAccount(key: String, amount: Long, reason: String, category: String, actorId: String, at: Instant)
    extends AccountMoneyCommand

/** The owner's credit line on a clearing account (FR-007); creates the account under the setter when absent. */
final case class SetCreditLine(creditLine: Long, owner: String, at: Instant)

final case class AccountOutcomeOf(key: String)
