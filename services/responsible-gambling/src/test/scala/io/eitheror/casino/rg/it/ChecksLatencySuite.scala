package io.eitheror.casino.rg.it

import io.eitheror.casino.rg.Json

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*

/**
 * T027 (SC-004): 1,000 asks of each check, each answered in under 100 ms at the 99th percentile, with no call
 * out (`FakePlayers` receives nothing). Asked over HTTP one at a time, as a caller's step asks, against a
 * profile with a limit and a break so every check reads a profile with something in it.
 */
class ChecksLatencySuite extends munit.FunSuite:
  override val munitTimeout = 5.minutes

  private lazy val kit = SharedKit.kit

  private val Asks = 1_000

  private def p99(f: => Reply): (Long, Int) =
    (1 to 50).foreach(_ => f) // warm the path
    val times = (1 to Asks).map { _ =>
      val start = System.nanoTime()
      val r     = f
      ((System.nanoTime() - start) / 1_000_000, r.status)
    }
    assert(times.forall((_, s) => s == 200 || s == 403), times.map(_._2).distinct.toString)
    (times.map(_._1).sorted.apply((Asks * 99) / 100 - 1), times.size)

  test("each check: p99 under 100 ms over 1,000 asks, nothing called out"):
    kit.reset()
    val p = s"p-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.json("PUT", kit.playerPath(s"/$p/limits/DEPOSIT_DAILY"), Json.obj("value" -> "100000", "currency" -> "EUR"), kit.asPlayer(p)).status, 200)
    val q = s"p-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.json("PUT", kit.playerPath(s"/$q/cooling-off"), Json.obj("until" -> kit.now().plus(Duration.ofDays(2)).toString), kit.asPlayer(q)).status, 200)

    val checks = Vector(
      "may-deposit" -> (() => kit.json("POST", kit.servicePath("/may-deposit"), Json.obj("playerId" -> p, "currency" -> "EUR", "amount" -> 100,
        "last24h" -> 0, "last7d" -> 0, "last30d" -> 0), kit.asService("wallet"))),
      "may-bet" -> (() => kit.json("POST", kit.servicePath("/may-bet"), Json.obj("playerId" -> p, "currency" -> "EUR", "amount" -> 100),
        kit.asService("game-provider"))),
      "may-log-in" -> (() => kit.json("POST", kit.servicePath("/may-log-in"), Json.obj("playerId" -> q), kit.asService("players"))),
      "may-play" -> (() => kit.request("GET", kit.servicePath(s"/$q/may-play"), None, kit.asService("game-provider")))
    )
    val results = checks.map((name, ask) => name -> p99(ask()))
    results.foreach((name, r) => println(s"$name: p99 ${r._1} ms over ${r._2} asks"))
    assertEquals(results.filter((_, r) => r._1 >= 100), Vector.empty)
    assertEquals(kit.players.calls, Vector.empty, "a check calls nothing out")
