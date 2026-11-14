package io.eitheror.casino.publicapi.it

import Ranking.*

import scala.concurrent.duration.*
import scala.util.Random

/**
 * T080 (SC-007), port of the original's `playerRankingCrawl.test.ts`: 1,000 crawls of one ranking at random page sizes,
 * with real runs between pages, a run moving the order each time. Every crawl names the snapshot of its first page; it
 * is either complete with every player once, in that snapshot's order, or ended by `410` once two runs replaced it.
 */
class RankingCrawlSuite extends munit.FunSuite:
  override val munitTimeout = 900.seconds
  private val Crawls = 1000
  private val N      = 120

  test(s"$Crawls crawls: each is complete or 410, never a duplicate or a gap") {
    val random  = Random(256)
    val players = many(N, "PLN")
    val keys    = Vector.fill(4)(Keys.noFields(kit))
    var orders  = Map.empty[String, Vector[String]]
    def run(): Unit =
      // Moves the order: a player picked at random gains more GGR than any other has.
      Games.seen(kit, Vector(play(players(random.nextInt(N)), 1000000L + random.nextInt(1000000), "PLN")))
      val p = compute()
      orders += snapshot(p) -> Iterator.from(0).map(o => ggr("PLN", o * 100, 100, Some(snapshot(p)), k = keys.head))
        .takeWhile(r => r.status == 200 && ids(r).nonEmpty).flatMap(ids).toVector
    run()
    var (complete, expired, n) = (0, 0, 0)
    (0 until Crawls).foreach { crawl =>
      val limit = 20 + random.nextInt(81)
      val pages  = (N + limit - 1) / limit
      // Every hundred crawls: one run mid-crawl (it completes) and, later, two at its second page (it is refused).
      val runsAt = if crawl % 100 == 75 then Vector(1, 1) else if crawl % 100 == 25 then Vector(1 + random.nextInt(pages - 1)) else Vector.empty
      val first = ggr("PLN", 0, limit, k = keys(crawl % keys.size))
      assertEquals(first.status, 200)
      val snap  = first.json.path("snapshot").path("id").asText()
      val total = first.json.path("page").path("total").asInt()
      var seen  = ids(first)
      var page  = 1
      var ended = false
      while !ended && page * limit < total do
        runsAt.filter(_ == page).foreach(_ => run())
        n += 1
        val r = ggr("PLN", page * limit, limit, Some(snap), k = keys(n % keys.size))
        if r.status == 410 then { assertEquals(r.error, "snapshot_expired"); ended = true; expired += 1 }
        else
          assertEquals(r.status, 200)
          assertEquals(r.json.path("snapshot").path("id").asText(), snap)
          seen ++= ids(r)
          page += 1
      if !ended then
        assertEquals(seen.size, N, s"crawl $crawl")
        assertEquals(seen.distinct.size, N, s"crawl $crawl: a duplicate")
        assertEquals(seen, orders(snap), s"crawl $crawl: not the snapshot's order")
        complete += 1
    }
    assertEquals(complete + expired, Crawls)
    assert(expired > 0, "no crawl met a replaced snapshot")
    assert(complete > expired)
  }
