package io.eitheror.casino.wallet.accounts

import io.eitheror.casino.contract.money.{Key, KeyedOutcomes}
import io.eitheror.casino.wallet.entity.Outcome

/**
 * A non-player account (spec 022 FR-006, data-model.md): money that belongs to no player, held for a
 * market (`pool`) or a clearing pool (`clearing`) in one currency. The first service to move money
 * through it is its owner, and only the owner moves it. A pool account never goes below zero; a
 * clearing account goes as far below as its credit line (research R7).
 *
 * Bounded as the wallet's key store: the last `RecentKeys` keys with their digest and outcome.
 */
final case class AccountState(
    accountId: String,
    created: Boolean = false,
    /** The service principal of the first move, `service:<project>/<name>`. */
    owner: String = "",
    /** Clearing accounts only: how far below zero the balance may go. */
    creditLine: Long = 0L,
    /** Signed: never below `floor`. */
    balance: Long = 0L,
    seen: KeyedOutcomes[Outcome] = KeyedOutcomes(),
    keyOrder: Vector[String] = Vector.empty
):
  def kind: String     = AccountState.kindOf(accountId)
  def name: String     = AccountState.nameOf(accountId)
  def currency: String = accountId.drop(accountId.lastIndexOf('/') + 1)

  /** The lowest the balance may go: 0 for a pool account, minus the credit line for a clearing one. */
  def floor: Long = if kind == AccountState.Pool then 0L else -creditLine

  private[accounts] def remember(key: String, digest: String, outcome: Outcome): AccountState =
    val recorded = seen.record(Key.unsafe(key), digest, outcome)
    val order    = if seen.seen.contains(key) then keyOrder else keyOrder :+ key
    val evicted  = order.dropRight(AccountState.RecentKeys)
    copy(seen = KeyedOutcomes(recorded.seen -- evicted), keyOrder = order.drop(evicted.size))

object AccountState:
  val Pool     = "pool"
  val Clearing = "clearing"
  val Kinds: Set[String] = Set(Pool, Clearing)

  /** The keys an account remembers, as the wallet's (spec 002 R17). */
  val RecentKeys: Int = 1000

  def kindOf(accountId: String): String = accountId.takeWhile(_ != '/')
  def nameOf(accountId: String): String =
    accountId.substring(accountId.indexOf('/') + 1, accountId.lastIndexOf('/'))

/** What the balance query answers (FR-012); `found` is false for an account no one has moved money through. */
final case class AccountBalance(accountId: String, found: Boolean, balance: Long, owner: String, creditLine: Long, floor: Long,
    currency: String)
