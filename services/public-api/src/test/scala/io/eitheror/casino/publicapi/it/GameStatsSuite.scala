package io.eitheror.casino.publicapi.it

import FakeGameProvider.game

import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** T072, ports of the original's `gameStats.test.ts` and `gameStatsEmpty.test.ts`. */
class GameStatsSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override val munitTimeout = 120.seconds
  private def key = Seeded.none

  private def catalogue(gs: String*): Unit =
    kit.provider.set(gs*)
    kit.moveClock(6.minutes)
    kit.v1("/games", Some(key.secret)): Unit
    kit.eventually("the catalogue reloaded")(Option.when(gs.forall(g => kit.v1(s"/games/${g.split('"')(3)}", Some(key.secret)).status == 200))(()))

  private def ago(h: Long) = kit.now.minus(JDuration.ofHours(h))

  test("rankings and one game's figures match a hand count, with when they were computed") {
    val (a, b, c) = (Games.code(), Games.code(), Games.code())
    catalogue(game(a), game(b), game(c))
    val p1 = FactSet.id(); val p2 = FactSet.id()
    val keys = Vector(
      Games.round(kit, a, p1, ago(1), 1000, 400), Games.round(kit, a, p2, ago(2), 500, 0, "BRL"), Games.round(kit, a, p1, ago(30), 200, 100),
      Games.round(kit, b, p1, ago(3), 300, 0), Games.round(kit, b, p2, ago(4), 300, 0), Games.round(kit, b, p2, ago(5), 300, 600),
      Games.round(kit, b, p1, ago(6), 999, 0, outcome = "cancelled"), Games.round(kit, c, p1, ago(24 * 20), 50, 0))
    Games.session(kit, a, p1, ago(1))
    Games.seen(kit, keys)
    val p = Games.compute(kit)
    val byRounds = kit.v1("/game-stats?period=7d&sort=rounds", Some(key.secret)).json
    val mine = byRounds.path("data").elements().asScala.filter(n => Set(a, b, c)(n.path("gameId").asText())).toVector
    assertEquals(mine.map(n => n.path("gameId").asText() -> n.path("rounds").asInt()), Vector(a -> 3, b -> 3).sortBy((g, _) => g))
    assertEquals(byRounds.path("computedAt").asText(), io.eitheror.casino.publicapi.wire.Times.of(p.currentSlot.get._2.computedAt.get))
    val stake = kit.v1("/game-stats?period=1d&sort=stake&currency=EUR", Some(key.secret)).json.path("data").elements().asScala
      .filter(n => Set(a, b)(n.path("gameId").asText())).toVector
    assertEquals(stake.map(n => n.path("gameId").asText()), Vector(a, b))
    assertEquals(stake.head.path("amounts").elements().asScala.map(x => x.path("currency").asText() -> x.path("stake").asText()).toVector,
      Vector("EUR" -> "10.00"))
    val one = kit.v1(s"/games/$c/stats", Some(key.secret)).json
    assertEquals(one.path("windows").path("1d").path("rounds").asInt(), 0)
    assertEquals(one.path("windows").path("30d").path("amounts").path(0).path("stake").asText(), "0.50")
    assertEquals(one.path("brandId").asText(), kit.brand)
  }

  test("a money sort without a currency, an unknown sort or period, and a limit over 100 are refused") {
    Vector("/game-stats?period=7d&sort=stake" -> "invalid_request", "/game-stats?period=2d&sort=rounds" -> "invalid_request",
      "/game-stats?period=7d&sort=bets" -> "invalid_request", "/game-stats?sort=rounds" -> "invalid_request",
      "/game-stats?period=7d&sort=rounds&currency=eur" -> "invalid_request", "/game-stats?period=7d&sort=rounds&limit=101" -> "limit_exceeded")
      .foreach { (path, code) =>
        val r = kit.v1(path, Some(key.secret))
        assertEquals((r.status, r.error), (400, code), path)
      }
  }

  test("a game not in the catalogue has no figures: 404") {
    assertEquals(kit.v1("/games/not-listed/stats", Some(key.secret)).status, 404)
  }
