package io.eitheror.casino.reporting.it

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.{Instant, LocalDate, LocalTime, ZoneId}
import java.util.UUID

/**
 * A known set of facts, published on the kit's broker as their owners would, and the figures each report must show
 * computed from the facts alone (research R17; SC-001, SC-004, SC-009): the rules of FR-005 written here again, not
 * read from the service, so a report that agrees with them agrees with the requirement. Every fact is at a time of the
 * brand's zone; players are registered before any of their money moves, so a fold knows whether each is a test
 * account.
 */
final class FactSet(val zone: ZoneId):
  import FactSet.*

  private val players = Vector.newBuilder[f.PlayerRegistered]
  private val rest    = Vector.newBuilder[() => Kit => Unit]
  private var legs    = Vector.empty[Leg]
  private var tests   = Set.empty[String]

  /** An instant of `day` at `time` (`HH:mm`) in the brand's zone. */
  def at(day: LocalDate, time: String): Instant = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant

  private def tx(): String = UUID.randomUUID().toString

  // ── players ─────────────────────────────────────────────────────────────────────────────────────

  def register(player: String, at: Instant, currency: String = "EUR", test: Boolean = false): String =
    players += f.PlayerRegistered(player, at, None, None, None, Some("ZA"), Some(currency), None, at, testAccount = Some(test))
    if test then tests += player
    player

  // ── money.transactions ──────────────────────────────────────────────────────────────────────────

  private def money(fact: f.MoneyTransactionsFact, leg: Leg): Unit =
    legs :+= leg
    rest += (() => (kit: Kit) => kit.publish(f.MoneyTransactions, fact, Some(leg.currency)))

  def deposit(player: String, currency: String, amount: Long, at: Instant): Unit =
    val c = cur(currency)
    money(f.DepositConfirmed(player, c, tx(), m(amount), s"pay-${tx()}", 1, s(amount), m(0), at, Some(s(amount)), Some(s(0)),
      Some(m(amount)), Some(m(0))), Leg(player, currency, Deposit, amount, 0, None, at))

  /** A stake, `depositPart` of it from the deposit pool and `convertedPart` from the converted pool; the rest restricted. */
  def bet(player: String, currency: String, round: String, amount: Long, depositPart: Long, at: Instant, convertedPart: Long = 0): Unit =
    val restricted = amount - depositPart - convertedPart
    money(f.BetPlaced(player, cur(currency), tx(), round, "game-1", m(amount), if restricted > 0 then "restricted" else "real",
      s(10_000), m(0), at, Some(s(0)), Some(s(0)), Some(m(depositPart)), Some(m(convertedPart))),
      Leg(player, currency, Bet, amount, depositPart, Some(round), at))

  def win(player: String, currency: String, round: String, amount: Long, depositPart: Long, at: Instant): Unit =
    money(f.WinCredited(player, cur(currency), tx(), round, "game-1", m(amount), "real", s(10_000), m(0), at, Some(s(0)), Some(s(0)),
      Some(m(depositPart)), Some(m(amount - depositPart))), Leg(player, currency, Win, amount, depositPart, Some(round), at))

  def rollback(player: String, currency: String, round: String, amount: Long, depositPart: Long, at: Instant): Unit =
    money(f.BetRolledBack(player, cur(currency), tx(), round, m(amount), s(10_000), m(0), at, Some(s(0)), Some(s(0)),
      Some(m(depositPart)), Some(m(amount - depositPart))), Leg(player, currency, Rollback, amount, depositPart, Some(round), at))

  def loss(player: String, currency: String, round: String, at: Instant): Unit =
    money(f.RoundSettledAsLoss(player, cur(currency), tx(), round, s(10_000), m(0), at), Leg(player, currency, Loss, 0, 0, Some(round), at))

  /** A win paid outside a round (a free-spin or promotional win): GGR's, never the deposit pool's. */
  def credit(player: String, currency: String, amount: Long, at: Instant): Unit =
    money(f.ProviderCredited(player, cur(currency), tx(), s"ref-${tx()}", m(amount), "real", None, s(10_000), m(0), at, Some(s(0)),
      Some(s(amount)), Some(m(0)), Some(m(amount))), Leg(player, currency, Credit, amount, 0, None, at))

  // ── casino.rewards, payments.payments, casino.withdrawals ───────────────────────────────────────

  private def other(player: String, currency: String, kind: String, amount: Long, at: Instant)(publish: Kit => Unit): Unit =
    legs :+= Leg(player, currency, kind, amount, 0, None, at)
    rest += (() => publish)

  def bonus(player: String, currency: String, amount: Long, at: Instant): Unit =
    other(player, currency, Bonus, amount, at)(_.publish(f.CasinoRewards,
      f.BonusGranted(player, s"bonus-${tx()}", "Welcome", "offer-1", "deposit_match", m(amount), cur(currency), None, at), Some(currency)))

  def freeSpinsWon(player: String, currency: String, win: Long, at: Instant): Unit =
    other(player, currency, Bonus, win, at)(_.publish(f.CasinoRewards,
      f.FreeSpinsSettled(player, s"bonus-${tx()}", "Spins", "offer-2", "free_spins", m(win), cur(currency), at), Some(currency)))

  /** A dispute the brand lost on a deposit: a chargeback. */
  def chargeback(player: String, currency: String, amount: Long, at: Instant): Unit =
    other(player, currency, Chargeback, amount, at)(_.publish(f.PaymentsPayments,
      f.PaymentDisputeClosed(s"pay-${tx()}", player, "unity-finance", f.DisputeOutcome.Lost, at, Some(m(amount)), Some(cur(currency))),
      Some(currency)))

  /** A withdrawal requested and completed, counted on its completion's day. */
  def withdrawal(player: String, currency: String, amount: Long, requested: Instant, completed: Instant): Unit =
    val payout = s"payout-${tx()}"
    other(player, currency, Withdrawal, amount, completed) { kit =>
      kit.publish(f.CasinoWithdrawals, f.WithdrawalRequested(player, payout, m(amount), cur(currency), "bank", requested, Some("bank")), Some(currency))
      kit.publish(f.CasinoWithdrawals, f.WithdrawalCompleted(player, payout, m(amount), cur(currency), completed, Some("bank"), Some("bank")))
    }

  /** Every fact on the kit's broker: the registrations first, then the rest in the order they were added. */
  def publish(kit: Kit): Unit =
    // A registration's currency is no figure's: the brand holds figures in a currency once money moves in it.
    players.result().foreach(p => kit.publish(f.CasinoPlayers, p))
    rest.result().foreach(_()(kit))

  // ── the expected figures ────────────────────────────────────────────────────────────────────────

  private def dayOf(at: Instant): LocalDate = at.atZone(zone).toLocalDate

  /**
   * A round's day, as attribution's `RoundIndex` holds it: the day of its first stake in the order the facts were
   * published; a leg of a round with no stake seen counts on its own day.
   */
  private lazy val roundDays: Map[(String, String, String), LocalDate] =
    legs.foldLeft(Map.empty[(String, String, String), LocalDate]) { (acc, l) =>
      (l.kind, l.round) match
        case (Bet, Some(r)) if !acc.contains((l.player, l.currency, r)) => acc.updated((l.player, l.currency, r), dayOf(l.at))
        case _                                                         => acc
    }

  private def counted(l: Leg, excludeTestAccounts: Boolean): Boolean = !(excludeTestAccounts && tests(l.player))

  /**
   * FR-005 written again: stakes, wins (a provider credit is one) and rollbacks on their own day; bonus cost, lost
   * disputes, deposits and completed withdrawals on their own day; the deposit pool's GGR as attribution's `RealMoney`
   * counts it (below).
   */
  def figures(day: LocalDate, currency: String, excludeTestAccounts: Boolean = false): Expected =
    val own = legs.filter(l => l.currency == currency && dayOf(l.at) == day && counted(l, excludeTestAccounts))
    def sum(kinds: String*) = own.filter(l => kinds.contains(l.kind)).map(_.amount).sum
    Expected(sum(Bet), sum(Win, Credit), sum(Rollback), ggrDaily(day, currency, excludeTestAccounts).values.sum, sum(Bonus),
      sum(Chargeback), sum(Deposit), sum(Withdrawal))

  def total(from: LocalDate, to: LocalDate, currency: String, excludeTestAccounts: Boolean = false): Expected =
    Iterator.iterate(from)(_.plusDays(1)).takeWhile(!_.isAfter(to)).map(figures(_, currency, excludeTestAccounts)).foldLeft(Expected())(_ + _)

  /**
   * Attribution's daily GGR per player (spec 012 FR-016, `RealMoney.counted` and `RoundIndex`), restated so SC-005 is
   * held to its rule and not to this service's: a bet's, a win's and a rollback's deposit parts on their round's day,
   * the stake adding and the win and the rollback taking away; a provider credit and a loss count nothing.
   */
  def ggrDaily(day: LocalDate, currency: String, excludeTestAccounts: Boolean = false): Map[String, Long] =
    legs.filter(l => l.currency == currency && counted(l, excludeTestAccounts)).flatMap { l =>
      val signed = l.kind match
        case Bet      => Some(l.depositPart)
        case Win      => Some(-l.depositPart)
        case Rollback => Some(-l.depositPart)
        case _        => None
      signed.map(v => (l.player, l.round.flatMap(r => roundDays.get((l.player, l.currency, r))).getOrElse(dayOf(l.at)), v))
    }.filter(_._2 == day).groupMapReduce(_._1)(_._3)(_ + _)

  def currencies: Vector[String] = legs.map(_.currency).distinct.sorted

object FactSet:
  val Deposit    = "deposit"
  val Bet        = "bet"
  val Win        = "win"
  val Rollback   = "rollback"
  val Loss       = "loss"
  val Credit     = "credit"
  val Bonus      = "bonus"
  val Chargeback = "chargeback"
  val Withdrawal = "withdrawal"

  final case class Leg(player: String, currency: String, kind: String, amount: Long, depositPart: Long, round: Option[String], at: Instant)

  /** A day's figures in minor units: bets are stakes less rollbacks, GGR bets less wins, NGR GGR less bonus and chargebacks. */
  final case class Expected(stakes: Long = 0, wins: Long = 0, rollbacks: Long = 0, pool: Long = 0, bonus: Long = 0,
      chargebacks: Long = 0, deposits: Long = 0, withdrawals: Long = 0):
    def bets: Long        = stakes - rollbacks
    def ggr: Long         = bets - wins
    def ngr: Long         = ggr - bonus - chargebacks
    def netCashFlow: Long = deposits - withdrawals
    def +(o: Expected): Expected = Expected(stakes + o.stakes, wins + o.wins, rollbacks + o.rollbacks, pool + o.pool, bonus + o.bonus,
      chargebacks + o.chargebacks, deposits + o.deposits, withdrawals + o.withdrawals)

  private def cur(code: String): Currency = Currency.unsafe(code)
  private def m(v: Long): MinorUnits      = MinorUnits.unsafe(v)
  private def s(v: Long): SignedMinorUnits = SignedMinorUnits(v)

  /** One fact on each topic `Days` folds, at the midnight of `day`, so every view is complete from it (R8). */
  def seed(kit: Kit, day: LocalDate): Unit =
    val set = FactSet(kit.zone)
    val at  = day.atStartOfDay(kit.zone).toInstant
    set.register("seed", at)
    set.deposit("seed", "EUR", 100, at)
    set.bonus("seed", "EUR", 0, at)
    set.withdrawal("seed", "EUR", 50, at, at.plusSeconds(60))
    set.publish(kit)
    kit.publish(f.PaymentsPayments, f.PaymentInitiated("pay-seed", "seed", "unity-finance", "card", m(100), cur("EUR"), at), Some("EUR"))
