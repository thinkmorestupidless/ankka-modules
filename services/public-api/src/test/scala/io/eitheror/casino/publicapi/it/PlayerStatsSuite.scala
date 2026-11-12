package io.eitheror.casino.publicapi.it

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.wire.Times
import Ranking.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** T079, port of the original's `routes/playerStats.test.ts`: one player's figures from the current snapshot. */
class PlayerStatsSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds

  test("every window equals a hand count, with the snapshot and the last active time") {
    val pl = player()
    registered(Map(pl -> "active"))
    val keys = Vector(Games.round(kit, "g-a", pl, ago(2), 1000, 400, "BRL"), Games.round(kit, "g-b", pl, ago(50), 500, 0, "BRL"),
      Games.round(kit, "g-a", pl, ago(24 * 20), 300, 600, "BRL"))
    Games.session(kit, "g-a", pl, ago(1))
    kit.publish(f.MoneyTransactions, FactSet.deposit(pl, FactSet.id(), 2500, ago(3), io.eitheror.casino.contract.money.Currency.unsafe("BRL")))
    Games.seen(kit, keys)
    kit.eventually("the deposit in the view")(Option.when(get(s"/players/$pl/transactions").json.path("page").path("total").asInt() == 1)(()))
    val p = compute()
    val r = get(s"/players/$pl/stats")
    assertEquals(r.status, 200)
    val n = r.json
    assertEquals(n.path("playerId").asText(), pl)
    assertEquals(n.path("snapshot").path("id").asText(), snapshot(p))
    assertEquals(n.path("snapshot").path("computedAt").asText(), Times.of(p.currentSlot.get._2.computedAt.get))
    assertEquals(n.path("lastActiveAt").asText(), Times.of(ago(1)))
    val w = n.path("windows")
    assertEquals(Vector("1d", "7d", "30d").map(p => (w.path(p).path("rounds").asInt(), w.path(p).path("sessions").asInt(), w.path(p).path("games").asInt())),
      Vector((1, 1, 1), (2, 1, 2), (3, 1, 2)))
    val m = w.path("30d").path("amounts").path(0)
    assertEquals((m.path("currency").asText(), m.path("stake").asText(), m.path("win").asText(), m.path("ggr").asText()), ("BRL", "18.00", "10.00", "8.00"))
    assertEquals((m.path("deposits").path("count").asInt(), m.path("deposits").path("amount").asText()), (1, "25.00"))
    assertEquals(m.path("withdrawals").path("count").asInt(), 0)
  }

  test("a player with no activity in the current snapshot reports zeros with the snapshot and no last active time") {
    val pl = player()
    registered(Map(pl -> "active"))
    val p = compute()
    val n = get(s"/players/$pl/stats").json
    assertEquals(n.path("snapshot").path("id").asText(), snapshot(p))
    assert(n.path("lastActiveAt").isNull)
    Vector("1d", "7d", "30d").foreach(w => assertEquals(n.path("windows").path(w).toString, """{"rounds":0,"sessions":0,"games":0,"amounts":[]}"""))
  }

  test("after a run the new snapshot answers: activity since the last run shows only once it is computed") {
    val pl = player()
    registered(Map(pl -> "active"))
    compute(): Unit
    Games.seen(kit, Vector(play(pl, 900, "BRL")))
    assertEquals(get(s"/players/$pl/stats").json.path("windows").path("1d").path("rounds").asInt(), 0)
    compute(): Unit
    assertEquals(get(s"/players/$pl/stats").json.path("windows").path("1d").path("rounds").asInt(), 1)
  }

  test("another brand's player and an unknown id are the same 404; a malformed id is 400") {
    val theirs = FactSet.id()
    SharedKit.other.publish(f.CasinoPlayers, FactSet.registered(theirs, ago(10)))
    SharedKit.other.eventually("their player")(SharedKit.other.views.forView(io.eitheror.casino.publicapi.records.Players).get(theirs))
    Vector(theirs, FactSet.id()).foreach { id =>
      val r = get(s"/players/$id/stats")
      assertEquals((r.status, r.error), (404, "not_found"))
    }
    assertEquals(get("/players/not-an-id/stats").status, 400)
  }

  test("a player's figures carry no identifying field, whatever the key allows") {
    val pl = player()
    registered(Map(pl -> "active"))
    val n = get(s"/players/$pl/stats", all).json
    assertEquals(n.fieldNames().asScala.toSet, Set("playerId", "snapshot", "lastActiveAt", "windows"))
  }
