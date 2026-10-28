package io.eitheror.casino.attribution.it

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/**
 * One player's wallet in one currency as `wallet` tells it on `money.transactions`: each fact carries the
 * real and restricted balances after it, so the service reads the real movement as `wallet` made it. Each
 * call publishes its fact and answers it, for a test that delivers it again.
 */
final class WalletScript(kit: Kit, val player: String, currency: String = "EUR"):
  var real: Long       = 0
  var restricted: Long = 0

  private val c = Currency.unsafe(currency)
  private def m(v: Long) = MinorUnits.unsafe(v)
  private def tx() = kit.fresh("tx")

  /** Every fact published, and the dedupe ids of those that make a leg of a day's tally (research R10). */
  var published: Vector[MoneyTransactionsFact] = Vector.empty
  var legs: Vector[String]                     = Vector.empty

  private def sent[F <: MoneyTransactionsFact](f: F): F =
    val before = if published.isEmpty then 0L else published.last.realBalanceAfter.value
    val moved  = f.realBalanceAfter.value != before
    val leg = f match
      case _: BetPlaced | _: WinCredited | _: BetRolledBack => moved
      case _: RoundSettledAsLoss                           => true
      case p: ProviderCredited                             => p.landedOn == "real" && moved
      case _                                               => false
    published :+= f
    if leg then legs :+= io.eitheror.casino.attribution.money.RealMoney.dedupeId(f, None)
    kit.publish(MoneyTransactions, f)
    f

  def deposit(amount: Long, at: Instant = kit.now): DepositConfirmed =
    real += amount
    sent(DepositConfirmed(player, c, tx(), m(amount), kit.fresh("pay"), 1, SignedMinorUnits(real), m(restricted), at))

  /** A bet of `amount`, `fromReal` of it from real money and the rest from restricted. */
  def bet(round: String, amount: Long, at: Instant = kit.now, fromReal: Option[Long] = None): BetPlaced =
    val r = fromReal.getOrElse(amount)
    real -= r; restricted -= amount - r
    sent(BetPlaced(player, c, tx(), round, "game-1", m(amount), "mixed", SignedMinorUnits(real), m(restricted), at))

  def win(round: String, amount: Long, at: Instant = kit.now, toRestricted: Boolean = false): WinCredited =
    if toRestricted then restricted += amount else real += amount
    sent(WinCredited(player, c, tx(), round, "game-1", m(amount), if toRestricted then "restricted" else "real",
      SignedMinorUnits(real), m(restricted), at))

  def rollback(round: String, amount: Long, at: Instant = kit.now): BetRolledBack =
    real += amount
    sent(BetRolledBack(player, c, tx(), round, m(amount), SignedMinorUnits(real), m(restricted), at))

  def loss(round: String, at: Instant = kit.now): RoundSettledAsLoss =
    sent(RoundSettledAsLoss(player, c, tx(), round, SignedMinorUnits(real), m(restricted), at))

  def award(awardId: String, amount: Long, at: Instant = kit.now): RestrictedFundsAwarded =
    restricted += amount
    sent(RestrictedFundsAwarded(player, c, tx(), awardId, m(amount), m(amount * 30), SignedMinorUnits(real), m(restricted), at))

  def convert(awardId: String, amount: Long, at: Instant = kit.now): RestrictedFundsConverted =
    restricted -= amount; real += amount
    sent(RestrictedFundsConverted(player, c, tx(), awardId, m(amount), SignedMinorUnits(real), m(restricted), at))

  def forfeit(awardId: String, amount: Long, at: Instant = kit.now): RestrictedFundsForfeited =
    restricted -= amount
    sent(RestrictedFundsForfeited(player, c, tx(), awardId, m(amount), "expired", m(0), SignedMinorUnits(real), m(restricted), at))

  def providerCredit(ref: String, amount: Long, landedOn: String, at: Instant = kit.now): ProviderCredited =
    if landedOn == "restricted" then restricted += amount else real += amount
    sent(ProviderCredited(player, c, tx(), ref, m(amount), landedOn, None, SignedMinorUnits(real), m(restricted), at))

  /** Delivers a fact again, as the broker may. */
  def again(f: MoneyTransactionsFact): Unit = kit.publish(MoneyTransactions, f)
