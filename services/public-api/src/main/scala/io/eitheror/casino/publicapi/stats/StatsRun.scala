package io.eitheror.casino.publicapi.stats

import io.eitheror.casino.publicapi.records.{RoundState, SessionState, TransactionState}

import java.time.{Duration, Instant}

/** A window's money in one currency, in minor units (`ggr = stake − win`). */
final case class Amounts(currency: String, stakeCents: Long = 0L, winCents: Long = 0L):
  def ggrCents: Long = stakeCents - winCents

/** A game's figures in one window (the contract's `StatsWindow`). */
final case class GameWindow(rounds: Long = 0L, sessions: Long = 0L, uniquePlayers: Long = 0L, amounts: Vector[Amounts] = Vector.empty)

/** A player's money in one currency, with settled deposits and completed withdrawals (the contract's `PlayerAmounts`). */
final case class PlayerAmounts(currency: String, stakeCents: Long = 0L, winCents: Long = 0L, deposits: Long = 0L, depositCents: Long = 0L,
    withdrawals: Long = 0L, withdrawalCents: Long = 0L):
  def ggrCents: Long = stakeCents - winCents

/** A player's figures in one window (the contract's `PlayerWindow`). */
final case class PlayerWindow(rounds: Long = 0L, sessions: Long = 0L, games: Long = 0L, amounts: Vector[PlayerAmounts] = Vector.empty)

/** One run's figures: by game and by player, each by period. */
final case class Figures(games: Map[String, Map[String, GameWindow]], players: Map[String, Map[String, PlayerWindow]],
    lastActive: Map[String, Instant])

/**
 * The run of data-model.md, a pure fold over the rows of the 30 days to `at` (original `GameStatsRecompute`,
 * `PlayerStatsRecompute`): a cancelled round counts nowhere; any other round counts toward rounds, its player and its
 * game, and, unless it is a free-spins round, its real stake and win in its currency (bonus-funded money is excluded by
 * the real parts, G7); a session counts toward sessions; a completed deposit or withdrawal toward its player's count and
 * amount. A row counts in a window when its time is after `at − window` and not after `at`.
 */
object StatsRun:
  val Periods: Vector[(String, Duration)] = Vector("1d" -> Duration.ofDays(1), "7d" -> Duration.ofDays(7), "30d" -> Duration.ofDays(30))
  val Longest: Duration = Duration.ofDays(30)

  private def within(at: Instant, w: Duration)(t: Instant): Boolean = t.isAfter(at.minus(w)) && !t.isAfter(at)

  def counted(r: RoundState): Boolean = r.status != "cancelled" && r.startedAt.isDefined && r.gameId.nonEmpty

  def run(at: Instant, rounds: Iterable[RoundState], sessions: Iterable[SessionState], transactions: Iterable[TransactionState]): Figures =
    val inLongest = within(at, Longest)
    val rs = rounds.filter(r => counted(r) && inLongest(r.startedAt.get)).toVector
    val ss = sessions.filter(_.startedAt.exists(inLongest)).toVector
    val ts = transactions.filter(t => t.status == "completed" && t.createdAt.exists(inLongest) &&
      (t.typeWord == "deposit" || t.typeWord == "withdrawal")).toVector

    def gameWindow(rounds: Vector[RoundState], sessions: Vector[SessionState]): GameWindow =
      GameWindow(rounds.size.toLong, sessions.size.toLong, rounds.map(_.playerId).distinct.size.toLong, money(rounds))

    def money(rounds: Vector[RoundState]): Vector[Amounts] =
      rounds.filter(_.freeRoundsGrant.isEmpty).groupBy(_.currency).toVector.sortBy(_._1).map { (ccy, xs) =>
        Amounts(ccy, xs.map(_.realStakeCents.getOrElse(0L)).sum, xs.map(_.realWinCents.getOrElse(0L)).sum)
      }

    val games = (rs.map(_.gameId) ++ ss.flatMap(_.gameId)).distinct.map { g =>
      g -> Periods.map { (p, w) =>
        val in = within(at, w)
        p -> gameWindow(rs.filter(r => r.gameId == g && in(r.startedAt.get)), ss.filter(s => s.gameId.contains(g) && in(s.startedAt.get)))
      }.toMap
    }.toMap

    val players = (rs.map(_.playerId) ++ ss.map(_.playerId) ++ ts.map(_.playerId)).distinct.map { pl =>
      val mine = rs.filter(_.playerId == pl); val sess = ss.filter(_.playerId == pl); val money = ts.filter(_.playerId == pl)
      pl -> Periods.map { (p, w) =>
        val in = within(at, w)
        val r  = mine.filter(x => in(x.startedAt.get)); val s = sess.filter(x => in(x.startedAt.get)); val m = money.filter(x => in(x.createdAt.get))
        val real = r.filter(_.freeRoundsGrant.isEmpty)
        val ccys = (real.map(_.currency) ++ m.map(_.currency)).distinct.sorted
        p -> PlayerWindow(r.size.toLong, s.size.toLong, r.map(_.gameId).distinct.size.toLong, ccys.map { c =>
          val rc = real.filter(_.currency == c); val dep = m.filter(x => x.currency == c && x.typeWord == "deposit")
          val wd = m.filter(x => x.currency == c && x.typeWord == "withdrawal")
          PlayerAmounts(c, rc.map(_.realStakeCents.getOrElse(0L)).sum, rc.map(_.realWinCents.getOrElse(0L)).sum,
            dep.size.toLong, dep.map(_.amountCents).sum, wd.size.toLong, wd.map(_.amountCents).sum)
        })
      }.toMap
    }.toMap

    val lastActive = (rs.flatMap(r => r.startedAt.map(r.playerId -> _)) ++ ss.flatMap(s => s.startedAt.map(s.playerId -> _)))
      .filter((_, t) => inLongest(t)).groupMapReduce(_._1)(_._2)((a, b) => if a.isAfter(b) then a else b)
    Figures(games, players, lastActive)

  /** A game's score under a sort, or none when it has no activity there (for money: in that currency). */
  def score(w: GameWindow, sort: String, currency: Option[String]): Option[Long] = sort match
    case "rounds"   => Some(w.rounds).filter(_ > 0)
    case "sessions" => Some(w.sessions).filter(_ > 0)
    case "players"  => Some(w.uniquePlayers).filter(_ > 0)
    case "stake"    => w.amounts.find(a => currency.contains(a.currency)).map(_.stakeCents)
    case "ggr"      => w.amounts.find(a => currency.contains(a.currency)).map(_.ggrCents)
    case _          => None

  /** Games ranked descending by score, ties by id; a money sort shows only the currency asked for (original `stats.ts`). */
  def rankGames(byGame: Iterable[(String, GameWindow)], sort: String, currency: Option[String], limit: Int): Vector[(String, GameWindow)] =
    byGame.toVector.flatMap((g, w) => score(w, sort, currency).map(s => (g, w, s)))
      .sortBy((g, _, s) => (-s, g)).take(limit)
      .map((g, w, _) => g -> (if sort == "stake" || sort == "ggr" then w.copy(amounts = w.amounts.filter(a => currency.contains(a.currency))) else w))
