package io.eitheror.casino.publicapi.games

import com.fasterxml.jackson.databind.ObjectMapper

import java.time.{Duration, Instant, LocalDate}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** T070, a port of the original's `catalogue.test.ts`: the mapper, the release gate and availability. */
class CatalogueSuite extends munit.FunSuite:
  private val mapper = new ObjectMapper()
  private def game(code: String, extra: String = "") =
    s"""{"gameId":"$code","provider":"st8","name":"Game $code","category":"slots","developerId":"dev-a","bonusTypes":["Free_Spins"],
       |"features":["Megaways","free_spins"],"themes":["Egypt"],"funMode":true,"enabled":true,"restrictedTerritories":["US"],
       |"prohibitedTerritories":[],"thumbnailUrl":null,"rtp":"96.5","volatility":3,"removedFromFeed":false$extra}""".stripMargin
  private def body(games: String*) = s"""{"games":[${games.mkString(",")}]}"""

  test("the contract's Game: tags the lower-cased sorted union, developerName defaulting to the developer, nulls absent") {
    val Some(Vector(g)) = CatalogueGame.parse(body(game("g1"))): @unchecked
    val v = g.view
    assertEquals(v.path("id").asText(), "g1")
    assertEquals(v.path("tags").elements().asScala.map(_.asText()).toVector, Vector("egypt", "free_spins", "megaways"))
    assertEquals(v.path("developerName").asText(), "dev-a")
    assertEquals((v.has("thumbnailUrl"), v.has("enabled"), v.has("provider"), v.path("volatility").asInt()), (false, false, false, 3))
  }

  test("a game without its required fields is dropped; one without a code is no game") {
    val parsed = CatalogueGame.parse(body(game("ok"), """{"gameId":"no-name","category":"slots","developerId":"d"}""", """{"name":"x"}""")).get
    assertEquals(parsed.map(_.code), Vector("ok"))
  }

  test("release gate: future excluded, the release day included, absent or unparseable included") {
    val today = LocalDate.parse("2026-10-21")
    assertEquals(CatalogueGame.releasedBy(Some("2026-10-22"), today), false)
    assertEquals(CatalogueGame.releasedBy(Some("2026-10-21"), today), true)
    assertEquals(CatalogueGame.releasedBy(Some("2026-10-20"), today), true)
    assertEquals(CatalogueGame.releasedBy(None, today), true)
    assertEquals(CatalogueGame.releasedBy(Some("soon"), today), true)
    assertEquals(CatalogueGame.releasedBy(Some("2026-02-30"), today), true)
    assertEquals(CatalogueGame.releasedBy(Some("2027-01-01T00:00:00Z"), today), true)
  }

  test("available: removed from the feed and unreleased games left out, in code order") {
    val games = CatalogueGame.parse(body(game("b"), game("a"), game("gone", ""","removedFromFeed":true""").replace(""""removedFromFeed":false,""", ""),
      game("later", ""","releaseDate":"2026-12-01""""))).get
    val c = Catalogue(() => games, Duration.ofMinutes(5), () => Instant.parse("2026-11-12T08:00:00Z"), 5.seconds)
    assertEquals(c.available().map(_.code), Vector("a", "b"))
    assertEquals(c.byCode("later"), None)
    assert(c.byCode("a").isDefined)
  }
