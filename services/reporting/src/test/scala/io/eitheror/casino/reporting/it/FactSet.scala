package io.eitheror.casino.reporting.it

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal as Marked

import java.time.{Duration, Instant, LocalDate, LocalTime, ZoneId}
import java.util.UUID

/**
 * A known set of facts, published on the kit's broker as their owners would, and the figures each report must show
 * computed from the facts alone (research R17; SC-001, SC-004, SC-009): the rules of FR-005 written here again, not
 * read from the service, so a report that agrees with them agrees with the requirement. Every fact is at a time of the
 * brand's zone; players are registered before any of their money moves, so a fold knows whether each is a test
 * account. A published set is remembered by its kit, so a figure over the whole brand (the dashboard's) is the sum
 * over every set (`FactSet.Brand`).
 */
final class FactSet(val zone: ZoneId):
  import FactSet.*

  private val players  = Vector.newBuilder[f.PlayerRegistered]
  private val rest     = Vector.newBuilder[() => Kit => Unit]
  private var legs     = Vector.empty[Leg]
  private var tests    = Set.empty[String]
  private var txs      = Vector.empty[Tx]
  private var pays     = Vector.empty[Pay]
  private var wds      = Vector.empty[Wd]
  private var sess     = Vector.empty[Sess]
  private var rnds     = Vector.empty[Rnd]
  private var stats    = Vector.empty[Status]
  private var kycs     = Vector.empty[Status]
  private var logins   = Vector.empty[(String, Instant)]
  private var gs       = Vector.empty[G]
  private var recs     = Vector.empty[Rec]

  /** An instant of `day` at `time` (`HH:mm`) in the brand's zone. */
  def at(day: LocalDate, time: String): Instant = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant

  private def tx(): String = UUID.randomUUID().toString

  private def later(publish: Kit => Unit): Unit = rest += (() => publish)

  // ── players ─────────────────────────────────────────────────────────────────────────────────────

  def register(player: String, at: Instant, currency: String = "EUR", test: Boolean = false, country: String = "ZA",
      email: Option[String] = None): String =
    players += f.PlayerRegistered(player, at, None, None, None, Option(country).filter(_.nonEmpty), Some(currency), None, at,
      email = email.map(Marked(_)), testAccount = Some(test))
    if test then tests += player
    player

  private def statusFact(player: String, family: String, to: String, at: Instant): f.CasinoPlayersFact = family match
    case "player_suspended"     => f.PlayerSuspended(player, "active", to, "operator", Some("op-1"), Some("review"), at)
    case "player_self_excluded" => f.PlayerSelfExcluded(player, "active", to, "player", None, None, at, None, at)
    case "player_closed"        => f.PlayerClosed(player, "active", to, "operator", Some("op-1"), None, at)
    case "player_reinstated"    => f.PlayerReinstated(player, "suspended", to, "operator", Some("op-1"), None, at)
    case other                  => throw IllegalArgumentException(other)

  /** A status change (`player_suspended` → `suspended`, `player_self_excluded` → `self_excluded`, …). */
  def status(player: String, family: String, to: String, at: Instant): Unit =
    stats :+= Status(player, family, to, at)
    later(_.publish(f.CasinoPlayers, statusFact(player, family, to, at)))

  def kycVerified(player: String, at: Instant): Unit =
    kycs :+= Status(player, "player_kyc_verified", "verified", at)
    later(_.publish(f.CasinoPlayers, f.PlayerKycVerified(player, "unverified", "verified", "operator", Some("op-1"), None, at)))

  def loggedIn(player: String, at: Instant): Unit =
    logins :+= (player -> at)
    later(_.publish(f.CasinoPlayers, f.PlayerLoggedIn(player, s"login-${tx()}", at)))

  // ── casino.sessions and casino.rounds ───────────────────────────────────────────────────────────

  /** A login session, open unless it ended. */
  def login(player: String, session: String, started: Instant, ended: Option[Instant] = None, address: String = "203.0.113.9",
      agent: String = "Firefox/140", reason: String = "logout"): Unit =
    sess :+= Sess(player, session, "player", None, started, ended)
    later { kit =>
      kit.publish(f.CasinoSessions, f.PlayerSessionStarted(player, session, Marked(address), Marked(agent), started))
      ended.foreach(e => kit.publish(f.CasinoSessions, f.PlayerSessionEnded(player, session, reason, e)))
    }

  /** A game session under a login session, open unless it ended. */
  def gameSession(player: String, session: String, game: String, login: String, started: Instant, ended: Option[Instant] = None): Unit =
    sess :+= Sess(player, session, "game", Some(game), started, ended)
    later { kit =>
      kit.publish(f.CasinoSessions, f.GameSessionStarted(player, session, game, login, started))
      ended.foreach(e => kit.publish(f.CasinoSessions, f.GameSessionEnded(player, session, game, 5, e, Some("player"))))
    }

  /** The provider's settlement of a round, naming its game from the catalogue. */
  def settled(player: String, currency: String, round: String, provider: String, game: String, gameName: String, session: String,
      stake: Long, win: Long, at: Instant): Unit =
    rnds :+= Rnd(game, gameName, provider, at)
    later(_.publish(f.CasinoRounds, f.RoundSettled(player, cur(currency), round, provider, s"p-$round", game, session, m(stake), m(win),
      SignedMinorUnits(win - stake), if win > 0 then "win" else "loss", Vector.empty, Vector.empty, Vector.empty, None, at,
      Some(gameName), Some("slots"))))

  // ── money.transactions ──────────────────────────────────────────────────────────────────────────

  private def money(fact: f.MoneyTransactionsFact, leg: Option[Leg], ledger: String, amount: Long, reference: String): Unit =
    leg.foreach(l => legs :+= l)
    txs :+= Tx(fact.transactionId, fact.playerId, fact.currency.code, ledger, amount, fact.occurredAt, reference)
    rest += (() => (kit: Kit) => kit.publish(f.MoneyTransactions, fact, Some(fact.currency.code)))

  def deposit(player: String, currency: String, amount: Long, at: Instant): Unit =
    val c   = cur(currency)
    val ref = s"pay-${tx()}"
    money(f.DepositConfirmed(player, c, tx(), m(amount), ref, 1, s(amount), m(0), at, Some(s(amount)), Some(s(0)),
      Some(m(amount)), Some(m(0))), Some(Leg(player, currency, Deposit, amount, 0, None, at)), "deposit", amount, ref)

  /** A stake, `depositPart` of it from the deposit pool and `convertedPart` from the converted pool; the rest restricted. */
  def bet(player: String, currency: String, round: String, amount: Long, depositPart: Long, at: Instant, convertedPart: Long = 0,
      records: Option[Vector[f.RecordProgress]] = None): Unit =
    val restricted = amount - depositPart - convertedPart
    money(f.BetPlaced(player, cur(currency), tx(), round, "game-1", m(amount), if restricted > 0 then "restricted" else "real",
      s(10_000), m(0), at, Some(s(0)), Some(s(0)), Some(m(depositPart)), Some(m(convertedPart)), records),
      Some(Leg(player, currency, Bet, amount, depositPart, Some(round), at)), "bet", amount, round)

  def win(player: String, currency: String, round: String, amount: Long, depositPart: Long, at: Instant): Unit =
    money(f.WinCredited(player, cur(currency), tx(), round, "game-1", m(amount), "real", s(10_000), m(0), at, Some(s(0)), Some(s(0)),
      Some(m(depositPart)), Some(m(amount - depositPart))), Some(Leg(player, currency, Win, amount, depositPart, Some(round), at)),
      "win", amount, round)

  def rollback(player: String, currency: String, round: String, amount: Long, depositPart: Long, at: Instant): Unit =
    money(f.BetRolledBack(player, cur(currency), tx(), round, m(amount), s(10_000), m(0), at, Some(s(0)), Some(s(0)),
      Some(m(depositPart)), Some(m(amount - depositPart))), Some(Leg(player, currency, Rollback, amount, depositPart, Some(round), at)),
      "rollback", amount, round)

  def loss(player: String, currency: String, round: String, at: Instant): Unit =
    money(f.RoundSettledAsLoss(player, cur(currency), tx(), round, s(10_000), m(0), at), Some(Leg(player, currency, Loss, 0, 0, Some(round), at)),
      "loss", 0, round)

  /** A win paid outside a round (a free-spin or promotional win): GGR's, never the deposit pool's. */
  def credit(player: String, currency: String, amount: Long, at: Instant): Unit =
    val ref = s"ref-${tx()}"
    money(f.ProviderCredited(player, cur(currency), tx(), ref, m(amount), "real", None, s(10_000), m(0), at, Some(s(0)),
      Some(s(amount)), Some(m(0)), Some(m(amount))), Some(Leg(player, currency, Credit, amount, 0, None, at)), "game_provider_credit",
      amount, ref)

  private def record(award: String, status: String, balance: Long, progress: Long, requirement: Long) =
    f.RecordProgress(award, status, m(balance), m(progress), m(requirement))

  /** Restricted funds awarded: an open record of `amount` with its release requirement and no progress. */
  def award(player: String, currency: String, award: String, amount: Long, requirement: Long, at: Instant): Unit =
    recs :+= Rec(player, currency, award, "ACTIVE", amount, 0, requirement)
    money(f.RestrictedFundsAwarded(player, cur(currency), tx(), award, m(amount), m(requirement), s(0), m(amount), at,
      records = Some(Vector(record(award, "ACTIVE", amount, 0, requirement)))), None, "bonus_credit", amount, award)

  /** A restricted stake that moves a record's progress and balance. */
  def wager(player: String, currency: String, award: String, round: String, stake: Long, at: Instant, balance: Long, progress: Long,
      requirement: Long): Unit =
    recs :+= Rec(player, currency, award, "ACTIVE", balance, progress, requirement)
    bet(player, currency, round, stake, 0, at, records = Some(Vector(record(award, "ACTIVE", balance, progress, requirement))))

  /** A record forfeited: closed, no longer the brand's liability. */
  def forfeitAward(player: String, currency: String, award: String, amount: Long, at: Instant): Unit =
    recs :+= Rec(player, currency, award, "FORFEITED", 0, 0, 0)
    money(f.RestrictedFundsForfeited(player, cur(currency), tx(), award, m(amount), "expired", m(0), s(0), m(0), at,
      records = Some(Vector(record(award, "FORFEITED", 0, 0, 0)))), None, "bonus_cancel", amount, award)

  // ── casino.rewards, payments.payments, casino.withdrawals ───────────────────────────────────────

  private def other(player: String, currency: String, kind: String, amount: Long, at: Instant)(publish: Kit => Unit): Unit =
    legs :+= Leg(player, currency, kind, amount, 0, None, at)
    later(publish)

  /** A funds grant under an offer, active until an outcome; its amount is bonus cost on its day. Answers its id. */
  def grant(player: String, currency: String, amount: Long, at: Instant, offer: String = "offer-1", name: String = "Welcome",
      offerType: String = "deposit_match"): String =
    val bonus = s"bonus-${tx()}"
    gs :+= G(bonus, player, offer, name, offerType, currency, amount, at, "active")
    other(player, currency, Bonus, amount, at)(_.publish(f.CasinoRewards,
      f.BonusGranted(player, bonus, name, offer, offerType, m(amount), cur(currency), None, at), Some(currency)))
    bonus

  def bonus(player: String, currency: String, amount: Long, at: Instant): Unit = grant(player, currency, amount, at): Unit

  /** A grant's outcome: `converted`, `forfeited`, `cancelled` or `expired`. */
  def outcome(bonus: String, status: String, at: Instant): Unit =
    val g = gs.find(_.bonusId == bonus).getOrElse(throw IllegalArgumentException(bonus))
    gs = gs.map(x => if x.bonusId == bonus then x.copy(status = status) else x)
    val fact: f.CasinoRewardsFact = status match
      case "converted" => f.BonusCompleted(g.player, bonus, g.name, g.offer, g.offerType, at)
      case "forfeited" => f.BonusForfeited(g.player, bonus, g.name, g.offer, g.offerType, "wagering_expired", at)
      case "cancelled" => f.BonusCancelled(g.player, bonus, g.name, g.offer, g.offerType, m(g.amount), cur(g.currency), at)
      case "expired"   => f.GrantExpired(g.player, bonus, g.name, g.offer, g.offerType, at)
      case other       => throw IllegalArgumentException(other)
    later(_.publish(f.CasinoRewards, fact))

  def freeSpinsWon(player: String, currency: String, win: Long, at: Instant): Unit =
    other(player, currency, Bonus, win, at)(_.publish(f.CasinoRewards,
      f.FreeSpinsSettled(player, s"bonus-${tx()}", "Spins", "offer-2", "free_spins", m(win), cur(currency), at), Some(currency)))

  /** A dispute the brand lost on a deposit: a chargeback. */
  def chargeback(player: String, currency: String, amount: Long, at: Instant): Unit =
    other(player, currency, Chargeback, amount, at)(_.publish(f.PaymentsPayments,
      f.PaymentDisputeClosed(s"pay-${tx()}", player, "unity-finance", f.DisputeOutcome.Lost, at, Some(m(amount)), Some(cur(currency))),
      Some(currency)))

  /**
   * A payment through `provider`: an attempt when `initiated` is given, then `captured` or `failed` at `ended`, or
   * nothing more for one still open.
   */
  def payment(player: String, currency: String, provider: String, amount: Long, initiated: Option[Instant], outcome: String = "captured",
      ended: Option[Instant] = None): Unit =
    val id = s"pay-${tx()}"
    pays :+= Pay(player, currency, provider, amount, initiated, outcome, ended)
    later { kit =>
      initiated.foreach(i => kit.publish(f.PaymentsPayments, f.PaymentInitiated(id, player, provider, "card", m(amount), cur(currency), i),
        Some(currency)))
      ended.foreach { e =>
        outcome match
          case "captured" => kit.publish(f.PaymentsPayments, f.PaymentCaptured(id, player, provider, m(amount), cur(currency), e), Some(currency))
          case _          => kit.publish(f.PaymentsPayments, f.PaymentFailed(id, player, provider, "declined", "declined", e))
      }
    }

  /** A withdrawal requested and, when `completed` is given, completed, counted on its completion's day. */
  def withdrawal(player: String, currency: String, amount: Long, requested: Instant, completed: Instant, gateway: String = "bank"): Unit =
    withdrawalOf(player, currency, amount, requested, Some(completed), gateway)

  def withdrawalOf(player: String, currency: String, amount: Long, requested: Instant, completed: Option[Instant], gateway: String): Unit =
    val payout = s"payout-${tx()}"
    wds :+= Wd(player, currency, gateway, amount, requested, completed)
    completed.foreach(c => legs :+= Leg(player, currency, Withdrawal, amount, 0, None, c))
    later { kit =>
      kit.publish(f.CasinoWithdrawals, f.WithdrawalRequested(player, payout, m(amount), cur(currency), gateway, requested, Some(gateway)), Some(currency))
      completed.foreach(c => kit.publish(f.CasinoWithdrawals, f.WithdrawalCompleted(player, payout, m(amount), cur(currency), c, Some(gateway), Some(gateway))))
    }

  /** Every fact on the kit's broker: the registrations first, then the rest in the order they were added. */
  def publish(kit: Kit): Unit =
    // A registration's currency is no figure's: the brand holds figures in a currency once money moves in it.
    players.result().foreach(p => kit.publish(f.CasinoPlayers, p))
    rest.result().foreach(_()(kit))
    kit.remember(this)

  // ── what was published, for the expected figures ───────────────────────────────────────────────

  def registered: Vector[f.PlayerRegistered] = players.result()
  def testAccounts: Set[String]              = tests
  def transactions: Vector[Tx]               = txs
  def payments: Vector[Pay]                  = pays
  def withdrawals: Vector[Wd]                = wds
  def sessions: Vector[Sess]                 = sess
  def rounds: Vector[Rnd]                    = rnds
  def statuses: Vector[Status]               = stats
  def kycChanges: Vector[Status]             = kycs
  def logIns: Vector[(String, Instant)]      = logins
  def grants: Vector[G]                      = gs
  def records: Vector[Rec]                   = recs
  def allLegs: Vector[Leg]                   = legs

  // ── the expected figures ────────────────────────────────────────────────────────────────────────

  private def dayOf(at: Instant): LocalDate = at.atZone(zone).toLocalDate
  private def hourOf(at: Instant): Int      = at.atZone(zone).getHour

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
   * counts it (below). `upTo` keeps only the legs to the end of that hour of the day.
   */
  def figures(day: LocalDate, currency: String, excludeTestAccounts: Boolean = false, upTo: Int = 23): Expected =
    val own = legs.filter(l => l.currency == currency && dayOf(l.at) == day && hourOf(l.at) <= upTo && counted(l, excludeTestAccounts))
    def sum(kinds: String*) = own.filter(l => kinds.contains(l.kind)).map(_.amount).sum
    def count(kind: String) = own.count(_.kind == kind).toLong
    Expected(sum(Bet), sum(Win, Credit), sum(Rollback), if upTo >= 23 then ggrDaily(day, currency, excludeTestAccounts).values.sum else 0L,
      sum(Bonus), sum(Chargeback), sum(Deposit), sum(Withdrawal), count(Deposit), count(Withdrawal))

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

  /** A player's GGR and wagered (stakes less rollbacks) over days, on their own days, as the rankings rank them. */
  def playerGgr(from: LocalDate, to: LocalDate, currency: String): Map[String, (Long, Long)] =
    legs.filter(l => l.currency == currency && !dayOf(l.at).isBefore(from) && !dayOf(l.at).isAfter(to)).flatMap { l =>
      l.kind match
        case Bet          => Some(l.player -> (l.amount, l.amount))
        case Rollback     => Some(l.player -> (-l.amount, -l.amount))
        case Win | Credit => Some(l.player -> (-l.amount, 0L))
        case _            => None
    }.groupMapReduce(_._1)(_._2)((a, b) => (a._1 + b._1, a._2 + b._2))

  def currencies: Vector[String] = legs.map(_.currency).distinct.sorted

  /**
   * The ledger as FR-010 says it (a `bonus` filter matching every `bonus_*` type): the movements of the players named
   * (every one for `None`) from `from` (inclusive) to `to` (exclusive), newest first.
   */
  def ledger(from: Instant, to: Instant, tpe: String = "all", of: Option[Set[String]] = None): Vector[Tx] =
    txs.filter(t => !t.at.isBefore(from) && t.at.isBefore(to) && of.forall(_(t.player)))
      .filter(t => tpe == "all" || t.tpe == tpe || (tpe == "bonus" && t.tpe.startsWith("bonus_")))
      .sortBy(t => -t.at.toEpochMilli)

  /** A player's lifetimes in `currency` (FR-008): deposits, wagered (stakes less rollbacks) and won (wins and credits). */
  def lifetimes(player: String, currency: String): (Long, Long, Long) =
    val mine = legs.filter(l => l.player == player && l.currency == currency)
    def sum(kinds: String*) = mine.filter(l => kinds.contains(l.kind)).map(_.amount).sum
    (sum(Deposit), sum(Bet) - sum(Rollback), sum(Win, Credit))

  /** A player's status as their last status fact left it, `active` with none. */
  def statusOf(player: String): String = stats.filter(_.player == player).sortBy(_.at).lastOption.fold("active")(_.to)

  def kycOf(player: String): String = kycs.filter(_.player == player).sortBy(_.at).lastOption.fold("unverified")(_.to)

  def lastLogin(player: String): Option[Instant] = logins.filter(_._1 == player).map(_._2).maxOption

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

  /** A ledger row as the original wallet would name it: its type, its amount and the reference it shows. */
  final case class Tx(transactionId: String, player: String, currency: String, tpe: String, amount: Long, at: Instant, reference: String)
  final case class Pay(player: String, currency: String, provider: String, amount: Long, initiated: Option[Instant], outcome: String,
      ended: Option[Instant])
  final case class Wd(player: String, currency: String, gateway: String, amount: Long, requested: Instant, completed: Option[Instant])
  final case class Sess(player: String, id: String, kind: String, game: Option[String], started: Instant, ended: Option[Instant])
  final case class Rnd(game: String, gameName: String, provider: String, at: Instant)
  final case class Status(player: String, family: String, to: String, at: Instant)
  final case class G(bonusId: String, player: String, offer: String, name: String, offerType: String, currency: String, amount: Long,
      at: Instant, status: String)
  final case class Rec(player: String, currency: String, award: String, status: String, balance: Long, progress: Long, requirement: Long)

  /** A day's figures in minor units: bets are stakes less rollbacks, GGR bets less wins, NGR GGR less bonus and chargebacks. */
  final case class Expected(stakes: Long = 0, wins: Long = 0, rollbacks: Long = 0, pool: Long = 0, bonus: Long = 0,
      chargebacks: Long = 0, deposits: Long = 0, withdrawals: Long = 0, depositCount: Long = 0, withdrawalCount: Long = 0):
    def bets: Long        = stakes - rollbacks
    def ggr: Long         = bets - wins
    def ngr: Long         = ggr - bonus - chargebacks
    def netCashFlow: Long = deposits - withdrawals
    def +(o: Expected): Expected = Expected(stakes + o.stakes, wins + o.wins, rollbacks + o.rollbacks, pool + o.pool, bonus + o.bonus,
      chargebacks + o.chargebacks, deposits + o.deposits, withdrawals + o.withdrawals, depositCount + o.depositCount,
      withdrawalCount + o.withdrawalCount)

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
    set.payment("seed", "EUR", "unity-finance", 100, Some(at), "open")
    set.publish(kit)

  /**
   * What the whole brand must show: every set the kit published, folded as each set folds its own. The dashboard's
   * figures are the brand's, so a suite's expectations are over every suite's facts.
   */
  final class Brand(sets: Vector[FactSet], zone: ZoneId):
    private def dayOf(at: Instant) = at.atZone(zone).toLocalDate
    private def hourOf(at: Instant) = at.atZone(zone).getHour

    def tests: Set[String] = sets.flatMap(_.testAccounts).toSet
    private def leaveOut(exclude: Boolean): String => Boolean = p => exclude && tests(p)

    def figures(day: LocalDate, currency: String, exclude: Boolean = false, upTo: Int = 23): Expected =
      sets.map(_.figures(day, currency, exclude, upTo)).foldLeft(Expected())(_ + _)

    def registrations(day: LocalDate, exclude: Boolean = false, upTo: Int = 23): Long =
      sets.flatMap(_.registered).count(p => dayOf(p.registeredAt) == day && hourOf(p.registeredAt) <= upTo && !leaveOut(exclude)(p.playerId)).toLong

    def open(kind: String, exclude: Boolean = false): Long =
      sets.flatMap(_.sessions).count(s => s.kind == kind && s.ended.isEmpty && !leaveOut(exclude)(s.player)).toLong

    /** Per provider on `day`: attempts (initiated), completed (captured) and their value. */
    def providers(day: LocalDate, currency: String): Map[String, (Long, Long, Long)] =
      val ps = sets.flatMap(_.payments).filter(_.currency == currency)
      val names = ps.map(_.provider).distinct
      names.map { n =>
        val mine = ps.filter(_.provider == n)
        val attempts = mine.count(_.initiated.exists(dayOf(_) == day)).toLong
        val captured = mine.filter(p => p.outcome == "captured" && p.ended.exists(dayOf(_) == day))
        n -> (attempts, captured.size.toLong, captured.map(_.amount).sum)
      }.filter((_, v) => v != (0L, 0L, 0L)).toMap

    /** Over `from`–`to`, per gateway: requests, completions, and the seconds from request to completion summed. */
    def gateways(from: LocalDate, to: LocalDate, currency: String): Map[String, (Long, Long, Long)] =
      def in(t: Instant) = !dayOf(t).isBefore(from) && !dayOf(t).isAfter(to)
      val ws = sets.flatMap(_.withdrawals).filter(_.currency == currency)
      ws.map(_.gateway).distinct.map { g =>
        val mine = ws.filter(_.gateway == g)
        val done = mine.filter(_.completed.exists(in))
        g -> (mine.count(w => in(w.requested)).toLong, done.size.toLong,
          done.map(w => Duration.between(w.requested, w.completed.get).getSeconds).sum)
      }.filter((_, v) => v != (0L, 0L, 0L)).toMap

    /** Every open restricted funds record in `currency` as its last movement left it. */
    def openRecords(currency: String, exclude: Boolean = false): Vector[Rec] =
      sets.flatMap(_.records).filter(_.currency == currency).foldLeft(Vector.empty[Rec]) { (acc, r) =>
        acc.filterNot(x => x.player == r.player && x.award == r.award) :+ r
      }.filter(r => (r.status == "ACTIVE" || r.status == "CONVERTED_PENDING_CAP") && !leaveOut(exclude)(r.player))

    def countries(exclude: Boolean = false): Map[String, Long] =
      sets.flatMap(_.registered).filterNot(p => leaveOut(exclude)(p.playerId)).flatMap(_.country)
        .groupMapReduce(identity)(_ => 1L)(_ + _)

    def players: Map[String, f.PlayerRegistered] = sets.flatMap(_.registered).map(p => p.playerId -> p).toMap

    def playerGgr(from: LocalDate, to: LocalDate, currency: String): Map[String, (Long, Long)] =
      sets.map(_.playerGgr(from, to, currency)).foldLeft(Map.empty[String, (Long, Long)]) { (acc, m) =>
        m.foldLeft(acc) { case (a, (p, (g, w))) => a.updatedWith(p)(o => Some(o.fold((g, w))(x => (x._1 + g, x._2 + w)))) }
      }

    /** Game sessions started from `from` to the end of `to`, per game. */
    def gameStarts(from: LocalDate, to: LocalDate): Map[String, Long] =
      sets.flatMap(_.sessions).filter(s => s.kind == "game" && !dayOf(s.started).isBefore(from) && !dayOf(s.started).isAfter(to))
        .groupMapReduce(_.game.getOrElse(""))(_ => 1L)(_ + _)

    def latestRound(game: String): Option[Rnd] = sets.flatMap(_.rounds).filter(_.game == game).sortBy(_.at).lastOption

    /** Today's activity entries from `since`: `(eventId, eventType, player, at)`, newest first. */
    def activity(since: Instant): Vector[(String, String, String, Instant)] =
      val regs   = sets.flatMap(_.registered).map(p => (s"reg-${p.playerId}", "registration", p.playerId, p.registeredAt))
      val grants = sets.flatMap(_.grants).map(g => (s"bonus-${g.bonusId}", "bonus_grant", g.player, g.at))
      val status = sets.flatMap(_.statuses).collect {
        case s if s.family == "player_self_excluded" => (s"selfex-${s.player}", "self_exclusion", s.player, s.at)
        case s if s.family == "player_suspended"     => (s"susp-${s.player}", "suspension", s.player, s.at)
      }.groupMapReduce(_._1)(identity)((a, b) => if b._4.isBefore(a._4) then a else b).values.toVector
      (regs ++ grants ++ status).filter(!_._4.isBefore(since)).sortBy(e => (-e._4.toEpochMilli, e._1))
