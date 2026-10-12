package io.eitheror.casino.gameprovider.catalogue

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.gameprovider.catalogue.BrandCatalogueCodecs.given
import io.eitheror.casino.gameprovider.catalogue.GameCodecs.given

import java.time.Instant

/** The game, the brand's curation, and the decision between them (FR-021, FR-022, FR-024). */
class CatalogueEntitiesSuite extends munit.FunSuite:

  private val at     = Instant.parse("2026-10-09T03:00:00Z")
  private val fields = GameFields("Book", "studio-a", "Studio A", "slots", themes = Vector("egypt"), features = Vector("gamble"),
    bonusTypes = Vector("free_bets"), enabled = false)

  private def game(f: GameFields = fields, removed: Boolean = false) = GameState("st8", "book", Some(f), removed, Some(at))

  test("an upsert replaces every provider field and lists the game again") {
    val kit = KeyValueEntityTestKit.of(Game, "st8/book")
    kit.call(Game.upsert)(UpsertGame("st8", "book", fields, at))
    kit.call(Game.markRemoved)
    assert(kit.currentState.removedFromFeed)
    kit.call(Game.upsert)(UpsertGame("st8", "book", fields.copy(name = "Book II", enabled = true), at.plusSeconds(86400)))
    assertEquals((kit.currentState.removedFromFeed, kit.currentState.fields.map(_.name), kit.currentState.fields.map(_.enabled)),
      (false, Some("Book II"), Some(true)))
  }

  test("marking a game removed leaves its enabled flag as the provider last sent it") {
    val kit = KeyValueEntityTestKit.of(Game, "st8/book")
    kit.call(Game.upsert)(UpsertGame("st8", "book", fields, at))
    kit.call(Game.markRemoved)
    assertEquals(kit.currentState.fields.map(_.enabled), Some(false))
  }

  test("an unchanged upsert writes nothing") {
    val kit = KeyValueEntityTestKit.of(Game, "st8/book")
    kit.call(Game.upsert)(UpsertGame("st8", "book", fields, at))
    assert(!kit.call(Game.upsert)(UpsertGame("st8", "book", fields, at.plusSeconds(86400))).changed)
  }

  test("tags are bonus types, features and themes") {
    assertEquals(fields.tags.toSet, Set("free_bets", "gamble", "egypt"))
  }

  test("an override enabling a removed game is refused naming it, and the configuration is unchanged") {
    val kit    = KeyValueEntityTestKit.of(BrandCatalogueConfiguration, "goldvibe")
    val result = kit.call(BrandCatalogueConfiguration.set)(SetCatalogueConfiguration(CatalogueConfiguration(gameOverrides = Map("st8/book" -> true)), Set("st8/book")))
    assert(result.isError)
    assert(result.errorMessage.contains("game_removed") && result.errorMessage.contains("st8/book"), result.errorMessage)
    assertEquals(kit.currentState, CatalogueConfiguration())
  }

  test("an override disabling a removed game, or enabling one that is not removed, is kept") {
    val kit = KeyValueEntityTestKit.of(BrandCatalogueConfiguration, "goldvibe")
    val c   = CatalogueConfiguration(Set("dev"), Set("table"), Map("st8/book" -> false, "st8/other" -> true))
    assertEquals(kit.call(BrandCatalogueConfiguration.set)(SetCatalogueConfiguration(c, Set("st8/book"))).replyValue, c)
  }

  test("the decision, layer by layer: removed, then category, then developer, then the override, else the provider's flag") {
    val none = CatalogueConfiguration()
    assert(!Enablement.listed(game(), none), "the provider disabled it")
    assert(Enablement.listed(game(), none.copy(gameOverrides = Map("st8/book" -> true))), "the brand enables it")
    assert(Enablement.listed(game(), none.copy(gameOverrides = Map("book" -> true))), "a bare code, as the original's backoffice wrote it")
    assert(!Enablement.listed(game(removed = true), none.copy(gameOverrides = Map("st8/book" -> true))), "removed wins over the override")
    assert(!Enablement.listed(game(fields.copy(enabled = true)), none.copy(disabledCategories = Set("slots"))))
    assert(!Enablement.listed(game(fields.copy(enabled = true)), none.copy(disabledDevelopers = Set("studio-a"), gameOverrides = Map("st8/book" -> true))),
      "a disabled developer wins over the override")
    assert(!Enablement.listed(game(fields.copy(enabled = true)), none.copy(gameOverrides = Map("st8/book" -> false))))
  }

  test("every layer is shown to the operator") {
    val layers = Enablement.decide(game(), CatalogueConfiguration(Set("studio-a"), Set.empty, Map("st8/book" -> true)))
    assertEquals(layers, EnablementLayers(providerEnabled = false, removedFromFeed = false, individualOverride = Some(true),
      categoryDisabled = false, developerDisabled = true, effectiveEnabled = false))
  }
