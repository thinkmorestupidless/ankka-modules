package io.eitheror.casino.publicapi.stats

import io.eitheror.casino.publicapi.records.{RoundState, SessionState, TransactionState}

import java.time.Instant

/** T071: data-model.md's run over a fixed set of rows against a hand count. */
class StatsRunSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-12T08:00:00Z")
  private def ago(hours: Long) = at.minusSeconds(hours * 3600)

  private var n = 0
  private def round(game: String, player: String, hours: Long, stake: Long, win: Long, ccy: String = "BRL", status: String = "settled",
      free: Boolean = false) =
    n += 1
    RoundState(id = s"r$n", playerId = player, gameId = game, currency = ccy, startedAt = Some(ago(hours)), stakeCents = stake + 100,
      winCents = win, realStakeCents = Some(stake), realWinCents = Some(win), freeRoundsGrant = Option.when(free)("g"), status = status)
  private def session(game: String, player: String, hours: Long) =
    n += 1; SessionState(s"s$n", player, Some(game), Some(ago(hours)))
  private def money(player: String, kind: String, cents: Long, hours: Long, status: String = "completed") =
    n += 1; TransactionState(s"t$n", player, kind, cents, "BRL", None, status, Some(ago(hours)))

  private val rounds = Vector(
    round("g1", "p1", 1, 1000, 400), round("g1", "p2", 2, 500, 0), round("g1", "p1", 30, 200, 100),
    round("g1", "p1", 3, 9999, 9999, status = "cancelled"),
    round("g1", "p3", 4, 0, 0, free = true),
    round("g1", "p2", 5, 700, 800, ccy = "USD"),
    round("g2", "p1", 24 * 10, 300, 0),
    round("g2", "p9", 24 * 40, 300, 0))
  private val sessions = Vector(session("g1", "p1", 1), session("g1", "p3", 4), session("g2", "p1", 24 * 10))
  private val tx = Vector(money("p1", "deposit", 5000, 2), money("p1", "withdrawal", 1000, 3), money("p1", "deposit", 1, 2, "pending"),
    money("p2", "deposit", 2000, 24 * 8))

  private val f = StatsRun.run(at, rounds, sessions, tx)

  test("a game's day: cancelled rounds nowhere, free spins counted without money, per currency") {
    val d = f.games("g1")("1d")
    assertEquals((d.rounds, d.sessions, d.uniquePlayers), (4L, 2L, 3L))
    assertEquals(d.amounts, Vector(Amounts("BRL", 1500, 400), Amounts("USD", 700, 800)))
    assertEquals(d.amounts.map(_.ggrCents), Vector(1100L, -100L))
  }

  test("the longer windows take the older rows; nothing older than 30 days counts") {
    assertEquals(f.games("g1")("7d").rounds, 5L)
    assertEquals(f.games("g1")("7d").amounts.head, Amounts("BRL", 1700, 500))
    assertEquals(f.games("g2")("1d"), GameWindow())
    assertEquals((f.games("g2")("30d").rounds, f.games("g2")("30d").uniquePlayers), (1L, 1L))
    assert(!f.players.contains("p9"))
  }

  test("a player's window: rounds, sessions, games, money and completed deposits and withdrawals") {
    val d = f.players("p1")("1d")
    assertEquals((d.rounds, d.sessions, d.games), (1L, 1L, 1L))
    assertEquals(d.amounts, Vector(PlayerAmounts("BRL", 1000, 400, 1, 5000, 1, 1000)))
    assertEquals(f.players("p1")("30d").games, 2L)
    assertEquals(f.players("p2")("7d").amounts.find(_.currency == "BRL").map(_.deposits), Some(0L))
    assertEquals(f.players("p2")("30d").amounts.find(_.currency == "BRL").map(_.depositCents), Some(2000L))
    assertEquals(f.lastActive("p1"), ago(1))
  }

  test("ranking: descending, ties by id, a game with nothing in the window absent, money in the currency asked for only") {
    val w = Map("b" -> GameWindow(rounds = 2), "a" -> GameWindow(rounds = 2), "c" -> GameWindow(rounds = 5), "z" -> GameWindow())
    assertEquals(StatsRun.rankGames(w, "rounds", None, 10).map(_._1), Vector("c", "a", "b"))
    assertEquals(StatsRun.rankGames(w, "rounds", None, 2).map(_._1), Vector("c", "a"))
    val g = StatsRun.rankGames(f.games.view.mapValues(_("1d")).toMap, "ggr", Some("USD"), 10)
    assertEquals(g.map(_._1), Vector("g1"))
    assertEquals(g.head._2.amounts.map(_.currency), Vector("USD"))
    assertEquals(StatsRun.rankGames(f.games.view.mapValues(_("1d")).toMap, "stake", Some("EUR"), 10), Vector.empty)
  }
