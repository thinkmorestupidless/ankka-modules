package io.eitheror.casino.attribution.it

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/**
 * One player's wallet in one currency as `wallet` tells it on `money.transactions`: each fact carries the
 * real and restricted balances after it and, as spec 025's wallet does, the parts of its real movement
 * (deposit and converted) and both pools after it. The pools follow the wallet's rules: a deposit joins
 * the deposit pool, a conversion and a provider credit to real the converted pool; a stake's real leg is
 * taken from the converted pool first, a win is split in its stake's ratio (the deposit part rounded
 * down), and a rollback returns the parts the stake took. Each call publishes its fact and answers it,
 * for a test that delivers it again.
 */
final class WalletScript(kit: Kit, val player: String, currency: String = "EUR"):
  var real: Long       = 0
  var restricted: Long = 0
  var depositPool: Long   = 0
  var convertedPool: Long = 0

  /** Each round's stake: its real leg's deposit and converted parts. */
  private var stakes: Map[String, (Long, Long)] = Map.empty

  private val c = Currency.unsafe(currency)
  private def m(v: Long) = MinorUnits.unsafe(v)
  private def tx() = kit.fresh("tx")
  private def dp = Some(SignedMinorUnits(depositPool))
  private def cp = Some(SignedMinorUnits(convertedPool))

  /** Every fact published, and the dedupe ids of those that make a leg of a day's tally (research R10). */
  var published: Vector[MoneyTransactionsFact] = Vector.empty
  var legs: Vector[String]                     = Vector.empty

  private def sent[F <: MoneyTransactionsFact](f: F): F =
    val leg = f match
      case b: BetPlaced          => b.depositPartCents.exists(_.value != 0)
      case w: WinCredited        => w.depositPartCents.exists(_.value != 0)
      case r: BetRolledBack      => r.depositPartCents.exists(_.value != 0)
      case _: RoundSettledAsLoss => true
      case _                     => false
    published :+= f
    if leg then legs :+= io.eitheror.casino.attribution.money.RealMoney.dedupeId(f, None)
    kit.publish(MoneyTransactions, f)
    f

  def deposit(amount: Long, at: Instant = kit.now): DepositConfirmed =
    real += amount; depositPool += amount
    sent(DepositConfirmed(player, c, tx(), m(amount), kit.fresh("pay"), 1, SignedMinorUnits(real), m(restricted), at, dp, cp, Some(m(amount)), Some(m(0))))

  /** A bet of `amount`, `fromReal` of it from real money and the rest from restricted. */
  def bet(round: String, amount: Long, at: Instant = kit.now, fromReal: Option[Long] = None): BetPlaced =
    val r = fromReal.getOrElse(amount)
    val converted = math.min(r, math.max(convertedPool, 0L))
    val deposited = r - converted
    real -= r; restricted -= amount - r; convertedPool -= converted; depositPool -= deposited
    stakes += round -> (deposited, converted)
    sent(BetPlaced(player, c, tx(), round, "game-1", m(amount), "mixed", SignedMinorUnits(real), m(restricted), at, dp, cp,
      Some(m(deposited)), Some(m(converted))))

  def win(round: String, amount: Long, at: Instant = kit.now, toRestricted: Boolean = false): WinCredited =
    val (d, cv) = stakes.getOrElse(round, (0L, 0L))
    val deposited = if toRestricted then 0L else if d + cv == 0 then 0L else amount * d / (d + cv)
    val converted = if toRestricted then 0L else amount - deposited
    if toRestricted then restricted += amount else real += amount
    depositPool += deposited; convertedPool += converted
    sent(WinCredited(player, c, tx(), round, "game-1", m(amount), if toRestricted then "restricted" else "real",
      SignedMinorUnits(real), m(restricted), at, dp, cp, Some(m(deposited)), Some(m(converted))))

  /** Returns `amount` of the stake, deposit part first as the wallet's restore of the recorded parts does. */
  def rollback(round: String, amount: Long, at: Instant = kit.now): BetRolledBack =
    val (d, _) = stakes.getOrElse(round, (0L, 0L))
    val deposited = math.min(amount, d)
    val converted = amount - deposited
    real += amount; depositPool += deposited; convertedPool += converted
    sent(BetRolledBack(player, c, tx(), round, m(amount), SignedMinorUnits(real), m(restricted), at, dp, cp,
      Some(m(deposited)), Some(m(converted))))

  def loss(round: String, at: Instant = kit.now): RoundSettledAsLoss =
    sent(RoundSettledAsLoss(player, c, tx(), round, SignedMinorUnits(real), m(restricted), at))

  def award(awardId: String, amount: Long, at: Instant = kit.now): RestrictedFundsAwarded =
    restricted += amount
    sent(RestrictedFundsAwarded(player, c, tx(), awardId, m(amount), m(amount * 30), SignedMinorUnits(real), m(restricted), at))

  def convert(awardId: String, amount: Long, at: Instant = kit.now): RestrictedFundsConverted =
    restricted -= amount; real += amount; convertedPool += amount
    sent(RestrictedFundsConverted(player, c, tx(), awardId, m(amount), SignedMinorUnits(real), m(restricted), at, dp, cp, Some(m(0)), Some(m(amount))))

  def forfeit(awardId: String, amount: Long, at: Instant = kit.now): RestrictedFundsForfeited =
    restricted -= amount
    sent(RestrictedFundsForfeited(player, c, tx(), awardId, m(amount), "expired", m(0), SignedMinorUnits(real), m(restricted), at))

  def providerCredit(ref: String, amount: Long, landedOn: String, at: Instant = kit.now): ProviderCredited =
    if landedOn == "restricted" then restricted += amount else { real += amount; convertedPool += amount }
    val parts = if landedOn == "restricted" then (None, None) else (Some(m(0)), Some(m(amount)))
    sent(ProviderCredited(player, c, tx(), ref, m(amount), landedOn, None, SignedMinorUnits(real), m(restricted), at, dp, cp,
      parts._1, parts._2))

  /** Delivers a fact again, as the broker may. */
  def again(f: MoneyTransactionsFact): Unit = kit.publish(MoneyTransactions, f)
