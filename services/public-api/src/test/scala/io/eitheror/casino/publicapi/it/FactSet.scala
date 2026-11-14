package io.eitheror.casino.publicapi.it

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant
import java.util.UUID

/**
 * Facts of every family the service reads, with known figures (T024, FR-039), built per brand so two instances hold
 * the same shapes with different ids. `ids` names what each `seed` made, by role.
 */
object FactSet:
  val eur: Currency               = Currency.unsafe("EUR")
  def m(v: Long): MinorUnits       = MinorUnits.unsafe(v)
  def s(v: Long): SignedMinorUnits = SignedMinorUnits(v)
  def p(v: String): Personal[String] = Personal(v)
  def id(): String                 = UUID.randomUUID().toString

  def registered(player: String, at: Instant, email: String = "ana@example.com", currency: String = "EUR"): f.PlayerRegistered =
    f.PlayerRegistered(player, at, None, None, None, Some("BR"), Some(currency), None, at, givenName = Some(p("Ana")),
      familyName = Some(p("Silva")), dateOfBirth = Some(p("1990-04-02")), telephone = Some(p("+5511999990000")),
      addressLine = Some(p("Rua 1")), city = Some(p("São Paulo")), postCode = Some(p("01000-000")), email = Some(p(email)),
      language = Some("pt"), stateProvince = Some(p("SP")))

  def sessionStarted(player: String, session: String, at: Instant, game: String = "st8-book"): f.GameSessionStarted =
    f.GameSessionStarted(player, session, game, id(), at)
  def sessionEnded(player: String, session: String, at: Instant, game: String = "st8-book"): f.GameSessionEnded =
    f.GameSessionEnded(player, session, game, 5, at, Some("player"))

  def opened(player: String, round: String, session: String, at: Instant, game: String = "st8-book", grant: Option[String] = None): f.RoundOpened =
    f.RoundOpened(player, eur, round, "st8", round.split('/').last, game, session, grant, at)
  def settled(player: String, round: String, session: String, at: Instant, stake: Long, win: Long, outcome: String = "win",
      game: String = "st8-book", real: Option[(Long, Long)] = None, grant: Option[String] = None, currency: Currency = eur): f.RoundSettled =
    f.RoundSettled(player, currency, round, "st8", round.split('/').last, game, session, m(stake), m(win), s(win - stake), outcome, Vector.empty,
      Vector.empty, Vector.empty, grant, at, openedAt = None, realStakeCents = real.map(r => m(r._1)), realWinCents = real.map(r => m(r._2)))

  def bet(player: String, tx: String, key: String, round: String, session: String, amount: Long, at: Instant, walletType: String = "real"): f.BetPlaced =
    f.BetPlaced(player, eur, tx, key, "st8-book", m(amount), walletType, s(0), m(0), at, gameRoundId = Some(round), sessionId = Some(session))
  def win(player: String, tx: String, key: String, round: String, session: String, amount: Long, at: Instant): f.WinCredited =
    f.WinCredited(player, eur, tx, key, "st8-book", m(amount), "real", s(0), m(0), at, gameRoundId = Some(round), sessionId = Some(session))
  def rolledBack(player: String, tx: String, key: String, round: String, amount: Long, at: Instant): f.BetRolledBack =
    f.BetRolledBack(player, eur, tx, key, m(amount), s(0), m(0), at, gameRoundId = Some(round))
  def loss(player: String, tx: String, key: String, round: String, at: Instant): f.RoundSettledAsLoss =
    f.RoundSettledAsLoss(player, eur, tx, key, s(0), m(0), at, gameRoundId = Some(round))
  def deposit(player: String, tx: String, amount: Long, at: Instant, currency: Currency = eur): f.DepositConfirmed =
    f.DepositConfirmed(player, currency, tx, m(amount), s"pay-$tx", 1, s(amount), m(0), at)
  def hold(player: String, tx: String, payout: String, amount: Long, at: Instant, voided: Long = 0): f.HoldPlaced =
    f.HoldPlaced(player, eur, tx, payout, m(amount), m(voided), s(0), m(0), at)
  def completed(player: String, payout: String, amount: Long, at: Instant): f.WithdrawalCompleted =
    f.WithdrawalCompleted(player, payout, m(amount), eur, at)
  def granted(player: String, bonus: String, amount: Long, at: Instant, expires: Option[Instant] = None): f.BonusGranted =
    f.BonusGranted(player, bonus, "Welcome", "offer-1", "deposit_match", m(amount), eur, None, at, expiresAt = expires)
  def awarded(player: String, tx: String, award: String, amount: Long, requirement: Long, at: Instant): f.RestrictedFundsAwarded =
    f.RestrictedFundsAwarded(player, eur, tx, award, m(amount), m(requirement), s(0), m(amount), at)

  /** What `seed` made, by role. */
  final case class Seeded(players: Vector[String], sessions: Vector[String], rounds: Vector[String], bets: Vector[String],
      transactions: Vector[String], bonuses: Vector[String], heavy: String)

  /**
   * Three players registered an hour apart; the first with two game sessions, three rounds (a win, a loss, a cancelled
   * one), their bets and transactions, a deposit, a completed withdrawal and a bonus; `heavy`, the third, with `heavyCount`
   * deposits a second apart (paging).
   */
  def seed(kit: Kit, at: Instant, heavyCount: Int = 0): Seeded =
    val players = Vector.fill(3)(id())
    players.zipWithIndex.foreach((pl, i) => kit.publish(f.CasinoPlayers, registered(pl, at.plusSeconds(3600L * i), s"p$i@example.com")))
    val pl       = players.head
    val sessions = Vector(id(), id())
    kit.publish(f.CasinoSessions, sessionStarted(pl, sessions(0), at.plusSeconds(60)))
    kit.publish(f.CasinoSessions, sessionEnded(pl, sessions(0), at.plusSeconds(600)))
    kit.publish(f.CasinoSessions, sessionStarted(pl, sessions(1), at.plusSeconds(700)))
    val keys   = Vector.tabulate(3)(i => s"st8/r-${id()}-$i")
    val outcomes = Vector("win", "loss", "cancelled")
    val betIds = Vector.fill(3)(id())
    val txs    = Vector.newBuilder[String]
    keys.zipWithIndex.foreach { (k, i) =>
      val t = at.plusSeconds(100L + i * 10)
      kit.publish(f.CasinoRounds, opened(pl, k, sessions(0), t))
      kit.publish(f.MoneyTransactions, bet(pl, betIds(i), s"w-$i", k, sessions(0), 100L * (i + 1), t.plusSeconds(1)))
      txs += betIds(i)
      outcomes(i) match
        case "win" =>
          val w = id(); txs += w
          kit.publish(f.MoneyTransactions, win(pl, w, s"w-$i", k, sessions(0), 250, t.plusSeconds(2)))
        case "loss" =>
          val l = id(); txs += l
          kit.publish(f.MoneyTransactions, loss(pl, l, s"w-$i", k, t.plusSeconds(2)))
        case _ =>
          val r = id(); txs += r
          kit.publish(f.MoneyTransactions, rolledBack(pl, r, s"w-$i", k, 300, t.plusSeconds(2)))
      val (stake, won) = outcomes(i) match { case "win" => (100L, 250L); case "loss" => (200L, 0L); case _ => (300L, 0L) }
      kit.publish(f.CasinoRounds, settled(pl, k, sessions(0), t.plusSeconds(3), stake, won, outcomes(i), real = Some(stake -> won)))
    }
    val dep = id(); txs += dep
    kit.publish(f.MoneyTransactions, deposit(pl, dep, 5000, at.plusSeconds(30)))
    val payout = id(); val h = id(); txs += h
    kit.publish(f.MoneyTransactions, hold(pl, h, payout, 1000, at.plusSeconds(900)))
    kit.publish(f.CasinoWithdrawals, completed(pl, payout, 1000, at.plusSeconds(950)))
    val bonus = id(); val aw = id(); txs += aw
    kit.publish(f.CasinoRewards, granted(pl, bonus, 1000, at.plusSeconds(40), Some(at.plusSeconds(86400 * 7))))
    kit.publish(f.MoneyTransactions, awarded(pl, aw, bonus, 1000, 30000, at.plusSeconds(41)))
    val heavy = players(2)
    (0 until heavyCount).foreach(i => kit.publish(f.MoneyTransactions, deposit(heavy, id(), 100L + i, at.plusSeconds(4000L + i))))
    Seeded(players, sessions, keys.map(io.eitheror.casino.publicapi.records.Records.roundId), betIds, txs.result(), Vector(bonus), heavy)
