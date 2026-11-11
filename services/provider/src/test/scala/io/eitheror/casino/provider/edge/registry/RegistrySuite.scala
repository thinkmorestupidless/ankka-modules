package io.eitheror.casino.provider.edge.registry

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

/** The registry (spec 026 FR-007, data-model "Game"). */
class RegistrySuite extends munit.FunSuite with LogCapturing:

  private val first = Instant.parse("2026-11-07T10:00:00Z")
  private val later = Instant.parse("2026-11-08T10:00:00Z")

  private def game(owner: String, at: Instant = first, name: String = "Loteca") =
    Game("loteca", owner, name, "Lottery", "", Vector("free_bets"), Vector.empty, Vector("football"),
      Vector.empty, Vector("GB"), None, demo = true, "https://pools-games.127.0.0.1.sslip.io/play", at, at)

  private def kit = KeyValueEntityTestKit.of(GameEntity, "loteca")

  test("a game registers under its code, its developer eitheror") {
    val g = kit.call(GameEntity.register)(game("pools")).replyValue
    assertEquals(g.owner, "pools")
    assertEquals(g.developer, Game.Developer)
  }

  test("the owner registering again updates the game and keeps when it was first registered") {
    val k = kit
    k.call(GameEntity.register)(game("pools")): Unit
    val g = k.call(GameEntity.register)(game("pools", later, "Loteca 14")).replyValue
    assertEquals(g.name, "Loteca 14")
    assertEquals(g.registeredAt, first)
    assertEquals(g.updatedAt, later)
  }

  test("another service registering a code that is held is refused naming the holder") {
    val k = kit
    k.call(GameEntity.register)(game("pools")): Unit
    val refused = k.call(GameEntity.register)(game("slots"))
    assert(refused.errorMessage.startsWith("code_taken"), refused.errorMessage)
    assert(refused.errorMessage.contains("pools"), refused.errorMessage)
    assertEquals(k.call(GameEntity.get).replyValue.owner, "pools")
  }

  test("only the owner deregisters its game; an operator removes any") {
    val k = kit
    k.call(GameEntity.register)(game("pools")): Unit
    assert(k.call(GameEntity.deregister)("slots").errorMessage.startsWith("forbidden"))
    k.call(GameEntity.deregister)("pools"): Unit
    assert(k.call(GameEntity.get).errorMessage.contains("not found"))
    k.call(GameEntity.register)(game("slots")): Unit
    assertEquals(k.call(GameEntity.remove).replyValue.map(_.owner), Some("slots"))
    assert(k.call(GameEntity.remove).errorMessage.contains("not found"))
  }

  test("deregistering a code nobody holds is nothing") {
    assert(!kit.call(GameEntity.deregister)("pools").changed)
  }

  test("the index lists each code once, sorted, and forgets a dropped one") {
    val k = KeyValueEntityTestKit.of(GameIndex, GameIndex.Id)
    k.call(GameIndex.add)("test-table"): Unit
    k.call(GameIndex.add)("loteca"): Unit
    assert(!k.call(GameIndex.add)("loteca").changed)
    assertEquals(k.call(GameIndex.codes).replyValue, Vector("loteca", "test-table"))
    assertEquals(k.call(GameIndex.drop)("loteca").replyValue, Vector("test-table"))
  }
