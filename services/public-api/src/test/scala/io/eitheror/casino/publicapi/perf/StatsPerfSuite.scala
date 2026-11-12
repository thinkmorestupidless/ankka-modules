package io.eitheror.casino.publicapi.perf

import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.it.{FactSet, Games, Keys, Reply, SharedKit}
import io.eitheror.casino.publicapi.records.{Records, Rounds, Transactions}
import io.eitheror.casino.publicapi.stats.GameFigures

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/**
 * The brand at its size (T093), on purpose only: `just public-api-perf` (`PUBLIC_API_PERF=1`).
 * `PUBLIC_API_PERF_ROUNDS` settled rounds (a million by default) over 30 days, across `PUBLIC_API_PERF_PLAYERS`
 * registered players (10,000) and 200 games, and one player with 10,000 deposits. Then the hourly run within five
 * minutes (SC-008); 95% of by-id reads within 500 ms and of full pages within one second (SC-002); and 95% of facts
 * published one at a time visible through the API within 30 seconds (SC-003). Each figure is printed.
 */
class StatsPerfSuite extends munit.FunSuite:
  override val munitTimeout = 600.minutes
  override def munitIgnore: Boolean = !sys.env.get("PUBLIC_API_PERF").contains("1")

  private lazy val kit = SharedKit.kit
  private lazy val key = Keys.noFields(kit)

  private val RoundCount  = sys.env.get("PUBLIC_API_PERF_ROUNDS").flatMap(_.toIntOption).getOrElse(1_000_000)
  private val PlayerCount = sys.env.get("PUBLIC_API_PERF_PLAYERS").flatMap(_.toIntOption).getOrElse(10_000)
  private val Heavy       = 10_000
  private val Samples     = 200

  private var players: Vector[String] = Vector.empty
  private var rounds: Vector[String]  = Vector.empty
  private var heavy: String           = ""

  private def p95(xs: Seq[Long]): Long = xs.sorted.apply(math.max(0, (xs.size * 95 + 99) / 100 - 1))
  private def timed(read: => Reply): Long =
    val t = System.nanoTime(); val r = read
    assertEquals(r.status, 200, r.text.take(200)); (System.nanoTime() - t) / 1_000_000

  override def beforeAll(): Unit =
    if !munitIgnore then
      val at = kit.now
      players = Vector.fill(PlayerCount)(FactSet.id())
      players.foreach(pl => kit.publish(f.CasinoPlayers, FactSet.registered(pl, at.minus(JDuration.ofDays(60)))))
      val started = System.nanoTime()
      rounds = (0 until RoundCount).toVector.map { i =>
        val back = JDuration.ofSeconds((i.toLong * 2_591_000L / RoundCount.max(1)) + 60)
        Games.round(kit, f"perf-g${i % 200}%03d", players(i % PlayerCount), at.minus(back), 100L + i % 900, (i % 3) * 150L)
      }
      heavy = players.head
      (0 until Heavy).foreach(i => kit.publish(f.MoneyTransactions, FactSet.deposit(heavy, FactSet.id(), 100L + i, at.minus(JDuration.ofSeconds(i + 120L)))))
      println(f"perf: published $RoundCount%,d rounds, $PlayerCount%,d players and $Heavy%,d deposits in ${(System.nanoTime() - started) / 1e9}%.1f s")
      kit.eventually("every round and deposit in the views", 300.minutes) {
        val r = kit.views.forView(Rounds).count(sql"payload::jsonb->>'gameId' LIKE 'perf-g%'")
        val d = kit.views.forView(Transactions).count(sql"payload::jsonb->>'playerId' = $heavy")
        Option.when(r >= RoundCount && d >= Heavy)(())
      }
      println(f"perf: the views held them ${(System.nanoTime() - started) / 1e9}%.1f s after the first publish")

  test("the hourly run over the brand's 30 days finishes within five minutes (SC-008)") {
    val t = System.nanoTime()
    val p = Games.compute(kit)
    val s = (System.nanoTime() - t) / 1e9
    println(f"perf: the run over $RoundCount%,d rounds took $s%.1f s")
    assert(p.currentSlot.isDefined)
    assert(s < 300, f"the run took $s%.1f s")
    // The perf games are in no catalogue, so the figures are read from the snapshot's row, not `/game-stats`.
    val (slot, _) = p.currentSlot.get
    val row = kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(GameFigures.key(slot, "perf-g000")))
      .call(GameFigures.get).invoke()
    assertEquals(row.flatMap(_.windows.get("30d")).map(_.rounds), Some((RoundCount + 199L) / 200))
  }

  test("95% of by-id reads within 500 ms and of full pages within one second (SC-002)") {
    val rnd = scala.util.Random(16)
    val byId = (1 to Samples).map { _ =>
      if rnd.nextBoolean() then timed(kit.v1(s"/rounds/${Records.roundId(rounds(rnd.nextInt(rounds.size)))}", Some(key.secret)))
      else timed(kit.v1(s"/players/${players(rnd.nextInt(players.size))}", Some(key.secret)))
    }
    val pages = (1 to Samples).map { i =>
      i % 3 match
        case 0 => timed(kit.v1(s"/players/$heavy/transactions?limit=100&offset=${rnd.nextInt(Heavy / 100) * 100}", Some(key.secret)))
        case 1 => timed(kit.v1(s"/players?limit=100&offset=${rnd.nextInt(PlayerCount / 100) * 100}", Some(key.secret)))
        case _ => timed(kit.v1(s"/players/${players(rnd.nextInt(players.size))}/rounds?limit=100", Some(key.secret)))
    }
    println(s"perf: by id p95 ${p95(byId)} ms (max ${byId.max}); full pages p95 ${p95(pages)} ms (max ${pages.max})")
    Vector("a player's 10,000 transactions", "the brand's players", "a player's rounds").zipWithIndex.foreach { (what, k) =>
      val xs = pages.zipWithIndex.collect { case (ms, i) if (i + 1) % 3 == k => ms }
      println(s"perf:   pages of $what: p95 ${p95(xs)} ms (max ${xs.max})")
    }
    assert(p95(byId) <= 500, s"by id p95 ${p95(byId)} ms")
    assert(p95(pages) <= 1000, s"pages p95 ${p95(pages)} ms")
  }

  test("95% of facts are visible through the API within 30 seconds (SC-003)") {
    val lags = (1 to Samples).map { _ =>
      val pl = players(scala.util.Random.nextInt(players.size))
      val k  = Games.round(kit, "perf-lag", pl, kit.now.minusSeconds(30), 100, 0)
      val t  = System.nanoTime()
      kit.eventually("the round served", 120.seconds)(Option.when(kit.v1(s"/rounds/${Records.roundId(k)}", Some(key.secret)).status == 200)(()))
      (System.nanoTime() - t) / 1_000_000
    }
    println(s"perf: publish to served p95 ${p95(lags)} ms (max ${lags.max})")
    assert(p95(lags) <= 30_000, s"p95 ${p95(lags)} ms")
  }
