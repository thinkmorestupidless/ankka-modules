package io.eitheror.casino.publicapi.stats

import io.eitheror.casino.publicapi.api.PlayerStatsRoutes
import io.eitheror.casino.publicapi.records.{RoundState, SessionState, TransactionState}

import java.time.Instant
import scala.jdk.CollectionConverters.*

/** T078, port of the original's `playerStatsMapper.test.ts`: a player's windows, their ranks, and how both are served. */
class PlayerFiguresSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-12T08:00:00Z")
  private def ago(hours: Long) = at.minusSeconds(hours * 3600)

  private var n = 0
  private def round(player: String, hours: Long, stake: Long, win: Long, game: String = "g1", ccy: String = "EUR") =
    n += 1
    RoundState(id = s"r$n", playerId = player, gameId = game, currency = ccy, startedAt = Some(ago(hours)), stakeCents = stake,
      winCents = win, realStakeCents = Some(stake), realWinCents = Some(win), status = "settled")
  private def money(player: String, kind: String, cents: Long, hours: Long, status: String = "completed") =
    n += 1; TransactionState(s"t$n", player, kind, cents, "EUR", None, status, Some(ago(hours)))

  private val f = StatsRun.run(at,
    Vector(round("p1", 2, 10000, 4050, "g1"), round("p1", 50, 300, 0, "g2"), round("p1", 24 * 6, 200, 0, "g3"), round("p2", 3, 500, 0),
      round("p3", 4, 100, 900), round("p4", 24 * 40, 100, 0)),
    Vector(SessionState("s1", "p1", Some("g1"), Some(ago(1))), SessionState("s2", "p5", Some("g1"), Some(ago(24 * 29)))),
    Vector(money("p1", "deposit", 5000, 5), money("p1", "deposit", 2500, 24 * 3), money("p1", "withdrawal", 2000, 6),
      money("p1", "withdrawal_reversed", 2000, 7), money("p2", "deposit", 100, 24 * 8)))

  test("a player's three windows: rounds, sessions, games, money, deposits and completed withdrawals") {
    val w = f.players("p1")
    assertEquals((w("1d").rounds, w("1d").sessions, w("1d").games), (1L, 1L, 1L))
    assertEquals((w("7d").rounds, w("7d").games), (3L, 3L))
    assertEquals(w("7d").amounts, Vector(PlayerAmounts("EUR", 10500, 4050, 2, 7500, 1, 2000)))
    assertEquals(w("7d").amounts.head.ggrCents, 6450L)
    assertEquals(w("1d").amounts.head.deposits, 1L)
  }

  test("a reversed withdrawal is not counted as a withdrawal") {
    assertEquals(f.players("p1")("30d").amounts.head.withdrawals, 1L)
  }

  test("lastActiveAt is the latest round or session in 30 days; none for a player with only older activity") {
    assertEquals(f.lastActive("p1"), ago(1))
    assertEquals(f.lastActive("p5"), ago(24 * 29))
    assert(!f.lastActive.contains("p4"))
    assert(!f.players.contains("p4"))
  }

  test("ranks: descending, ties by player id, only where the player has the figure; a negative GGR still ranks") {
    val r = StatsRun.rankPlayers(f.players)
    assertEquals(Vector("p1", "p2", "p3").map(r(_).get("1d_ggr_EUR")), Vector(Some(1), Some(2), Some(3)))
    assertEquals(r("p3")("1d_ggr_EUR"), 3)
    assertEquals(Vector("p1", "p2", "p3").map(r(_)("1d_rounds")), Vector(1, 2, 3))
    assertEquals(r("p5").get("1d_rounds"), None)
    assertEquals(r("p5")("30d_sessions"), 2)
    assertEquals(r("p2").get("7d_deposits_EUR"), Some(2))
    assertEquals(r("p2")("30d_deposits_EUR"), 2)
    assertEquals(r("p5").get("7d_ggr_EUR"), None)
  }

  test("a ranking entry carries its rank, status and the window's figures with money in major units") {
    val row = PlayerFiguresState("a", "S", "p1", "SUSPENDED", Some(ago(1)), f.players("p1"), Map("7d_ggr_EUR" -> 42))
    val e   = PlayerStatsRoutes.entry(row, "7d", "7d_ggr_EUR")
    assertEquals((e.path("rank").asInt(), e.path("playerId").asText(), e.path("status").asText(), e.path("rounds").asInt()), (42, "p1", "SUSPENDED", 3))
    val a = e.path("amounts").path(0)
    assertEquals((a.path("currency").asText(), a.path("stake").asText(), a.path("win").asText(), a.path("ggr").asText()), ("EUR", "105.00", "40.50", "64.50"))
    assertEquals((a.path("deposits").path("count").asInt(), a.path("deposits").path("amount").asText()), (2, "75.00"))
    assertEquals(a.path("withdrawals").path("amount").asText(), "20.00")
    assertEquals(e.fieldNames().asScala.toSet, Set("rank", "playerId", "status", "rounds", "sessions", "games", "amounts"))
  }

  test("a window with no activity is zeros with no amounts") {
    val z = PlayerStatsRoutes.window(com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), PlayerWindow())
    assertEquals(z.toString, """{"rounds":0,"sessions":0,"games":0,"amounts":[]}""")
  }
