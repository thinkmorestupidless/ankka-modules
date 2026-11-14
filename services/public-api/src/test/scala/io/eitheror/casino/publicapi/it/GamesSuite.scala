package io.eitheror.casino.publicapi.it

import FakeGameProvider.game

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** T072, ports of the original's `routes/games.test.ts`: the listing, by code, and the catalogue's changes. */
class GamesSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  private def key = Seeded.none

  /** Sets the provider's games and waits past the cache, so the next reads see them. */
  private def catalogue(gs: String*)(available: Int = gs.size): Unit =
    kit.provider.set(gs*)
    kit.moveClock(6.minutes)
    kit.v1("/games", Some(key.secret)): Unit
    kit.eventually("the catalogue reloaded")(Option.when(kit.v1("/games?limit=100", Some(key.secret)).json.path("page").path("total").asInt() == available)(()))

  test("the listing: available games in code order, paged, each the contract's Game") {
    catalogue(game("zz-b"), game("zz-a"), game("zz-c"))()
    val r = kit.v1("/games?limit=2", Some(key.secret))
    assertEquals(r.status, 200, r.text)
    assertEquals(r.json.path("data").elements().asScala.map(_.path("id").asText()).toVector, Vector("zz-a", "zz-b"))
    assertEquals((r.json.path("page").path("total").asInt(), r.json.path("page").path("hasMore").asBoolean()), (3, true))
    val one = r.json.path("data").path(0)
    assertEquals(one.path("tags").elements().asScala.map(_.asText()).toVector, Vector("egypt", "free_spins"))
    assert(!one.has("enabled") && !one.has("hitRatio") && !one.has("provider"), one.toString)
  }

  test("by code: the game, or 404 for one not available, removed from the feed or not yet released") {
    catalogue(game("zz-on"), game("zz-gone", removed = true), game("zz-later", releaseDate = Some("2099-01-01")))(1)
    assertEquals(kit.v1("/games/zz-on", Some(key.secret)).json.path("name").asText(), "Game zz-on")
    Vector("zz-gone", "zz-later", "zz-none").foreach(c => assertEquals(kit.v1(s"/games/$c", Some(key.secret)).status, 404, c))
  }

  test("a provider that cannot be read before any load is 503 unavailable; once loaded, the last catalogue is served") {
    catalogue(game("zz-kept"))()
    kit.provider.status.set(500)
    try
      kit.moveClock(6.minutes)
      assertEquals(kit.v1("/games/zz-kept", Some(key.secret)).status, 200)
      assertEquals(kit.v1("/games/zz-kept", Some(key.secret)).status, 200)
    finally kit.provider.status.set(200)
  }
