package io.eitheror.casino.publicapi.it

import Ranking.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** T079, port of the original's `routes/playerRanking.test.ts` over real runs on `SharedKit.ranked`. */
class PlayerRankingSuite extends munit.FunSuite:
  override val munitTimeout = 300.seconds

  test("250 players over three pages at the maximum size: every player once, descending, one snapshot") {
    val expected = many(250, "USD")
    val snap     = snapshot(compute())
    val pages    = Vector(0, 100, 200).map(o => ggr("USD", o))
    assertEquals(pages.map(_.status), Vector(200, 200, 200))
    assertEquals(pages.map(_.json.path("snapshot").path("id").asText()).distinct, Vector(snap))
    assertEquals(pages.flatMap(ids), expected)
    assertEquals(pages.flatMap(rows).map(_.path("rank").asInt()), (1 to 250).toVector)
    assertEquals(pages.map(_.json.path("page").path("total").asInt()).distinct, Vector(250))
    assertEquals(pages.map(_.json.path("page").path("hasMore").asBoolean()), Vector(true, true, false))
    val r = pages.head.json.path("ranking")
    assertEquals((r.path("period").asText(), r.path("sort").asText(), r.path("currency").asText()), ("1d", "ggr", "USD"))
    val top = rows(pages.head).head
    assertEquals((top.path("status").asText(), top.path("rounds").asInt(), top.path("amounts").path(0).path("ggr").asText()), ("ACTIVE", 1, "12.50"))
  }

  test("a run between pages: the named snapshot keeps its order, an unnamed read sees the new one; two runs later it is 410") {
    val ps   = many(30, "GBP")
    val s1   = snapshot(compute())
    val first = ggr("GBP", 0, 10, Some(s1))
    Games.seen(kit, Vector(play(ps.last, 100000, "GBP"), play(ps(15), 200000, "GBP")))
    val s2 = snapshot(compute())
    assertNotEquals(s1, s2)
    val rest = Vector(10, 20).map(o => ggr("GBP", o, 10, Some(s1)))
    assertEquals((first +: rest).flatMap(ids), ps)
    assertEquals(rest.map(_.json.path("snapshot").path("id").asText()).distinct, Vector(s1))
    val now = ggr("GBP", 0, 2)
    assertEquals(now.json.path("snapshot").path("id").asText(), s2)
    assertEquals(ids(now), Vector(ps(15), ps.last))
    compute(): Unit
    val gone = ggr("GBP", 10, 10, Some(s1))
    assertEquals((gone.status, gone.error), (410, "snapshot_expired"))
    assertEquals(ggr("GBP", 0, 10, Some(s2)).status, 200)
  }

  test("an unknown well-formed snapshot is 410; a malformed one is 400") {
    assertEquals(ggr("USD", snapshot = Some(FactSet.id())).status, 410)
    val bad = ggr("USD", snapshot = Some("not-an-id"))
    assertEquals((bad.status, bad.error), (400, "invalid_request"))
  }

  test("tied figures are ordered by player id on every read") {
    val ps = Vector.fill(5)(player())
    registered(ps.map(_ -> "active").toMap)
    Games.seen(kit, ps.map(play(_, 777, "CHF")))
    compute(): Unit
    val reads = Vector.fill(3)(ids(ggr("CHF")))
    assertEquals(reads.distinct, Vector(ps.sorted))
  }

  test("status filter: only those players, their unfiltered ranks with gaps, the filtered total") {
    val st = Vector("active", "closed", "active", "self_excluded", "suspended", "active")
    val ps = st.map(player(_))
    registered(ps.zip(st).toMap)
    Games.seen(kit, ps.zipWithIndex.map((pl, i) => play(pl, 6000L - i * 100, "SEK")))
    compute(): Unit
    val active = ggr("SEK", more = "&status=ACTIVE")
    assertEquals(ids(active), Vector(ps(0), ps(2), ps(5)))
    assertEquals(rows(active).map(_.path("rank").asInt()), Vector(1, 3, 6))
    assertEquals(active.json.path("page").path("total").asInt(), 3)
    assertEquals(active.json.path("ranking").path("status").path(0).asText(), "ACTIVE")
    val two = ggr("SEK", more = "&status=CLOSED,SELF_EXCLUDED")
    assertEquals(rows(two).map(r => r.path("rank").asInt() -> r.path("status").asText()), Vector(2 -> "CLOSED", 4 -> "SELF_EXCLUDED"))
    assertEquals(ids(ggr("SEK")).size, 6)
  }

  test("a status filter matching nobody is an empty page that still names the snapshot") {
    val r = ggr("USD", more = "&status=SUSPENDED")
    assertEquals((r.status, rows(r), r.json.path("page").path("total").asInt()), (200, Vector.empty, 0))
    assert(r.json.path("snapshot").path("id").asText().nonEmpty)
  }

  test("money sorts rank within their currency and need one; a currency on a count sort is ignored and not echoed") {
    val noCcy = get("/player-stats?period=1d&sort=ggr")
    assertEquals((noCcy.status, noCcy.error), (400, "invalid_request"))
    val counted = get("/player-stats?period=1d&sort=rounds&currency=USD&limit=1")
    assertEquals(counted.status, 200)
    assert(!counted.json.path("ranking").has("currency"))
    assertEquals(ids(ggr("NOK")), Vector.empty)
  }

  test("malformed parameters are 400") {
    Vector("/player-stats?sort=ggr&currency=USD", "/player-stats?period=2d&sort=ggr&currency=USD", "/player-stats?period=1d&sort=wins",
      "/player-stats?period=1d&sort=ggr&currency=usd", "/player-stats?period=1d&sort=rounds&status=ACTIVE,GONE",
      "/player-stats?period=1d&sort=rounds&limit=0").foreach { path =>
      val r = get(path)
      assertEquals((r.status, r.error), (400, "invalid_request"), path)
    }
    val over = get("/player-stats?period=1d&sort=rounds&limit=101")
    assertEquals((over.status, over.error), (400, "limit_exceeded"))
  }

  test("an offset past the end is an empty page with the total") {
    val r = ggr("SEK", offset = 500)
    assertEquals((rows(r), r.json.path("page").path("total").asInt()), (Vector.empty, 6))
  }

  test("an entry never carries an identifying field, whatever the key allows") {
    val r = ggr("USD", limit = 5, k = all)
    rows(r).foreach(n => assertEquals(n.fieldNames().asScala.toSet, Set("rank", "playerId", "status", "rounds", "sessions", "games", "amounts")))
  }
