package io.eitheror.casino.simulator.st8

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.eitheror.casino.simulator.st8.GoJson.Value.*

import scala.jdk.CollectionConverters.*

/** A fake game (FR-001). */
final case class Game(code: String, name: String, category: String, developer: String, bonusTypes: Vector[String], features: Vector[String], themes: Vector[String]):
  /** The stand-in's `slugify`: lower case, spaces to hyphens (`main.go:581-583`). */
  def developerId: String = developer.replace(" ", "-").toLowerCase

/**
 * The catalogue: the three games the stand-in advertises, from `games.json`, and any an
 * installation adds with `SIMULATOR_GAMES_EXTRA` (research R10).
 */
final case class Catalogue(games: Vector[Game]):

  def has(code: String): Boolean = games.exists(_.code == code)

  /**
   * The provider's listing (`main.go:593-634`): games in seed order; developers and categories
   * sorted by id where the stand-in's come out of Go maps in no fixed order (research R7).
   */
  def listing: GoJson.Value =
    val categories = games.map(_.category).distinct.sorted.map(id => GoJson.sorted("id" -> Str(id), "name" -> Str(Catalogue.categoryName(id))))
    val developers = games.map(g => g.developerId -> g.developer).distinct.sortBy(_._1).map((id, name) => GoJson.sorted("developer_id" -> Str(id), "name" -> Str(name)))
    val listed = games.map { g =>
      GoJson.sorted(
        "bonus_types" -> GoJson.strArr(g.bonusTypes), "category" -> Str(g.category), "code" -> Str(g.code),
        "developer" -> Str(g.developerId), "enabled" -> Bool(true), "features" -> GoJson.strArr(g.features),
        "fun_mode" -> Bool(true), "name" -> Str(g.name), "prohibited_territories" -> Arr(Vector.empty),
        "restricted_territories" -> Arr(Vector.empty), "themes" -> GoJson.strArr(g.themes)
      )
    }
    GoJson.sorted("categories" -> Arr(categories), "developers" -> Arr(developers), "games" -> Arr(listed))

object Catalogue:

  private val mapper = ObjectMapper()

  /** `main.go:585-588`; a category an installation adds is named by its id, capitalised. */
  def categoryName(id: String): String = id match
    case "slots" => "Slots"
    case "table" => "Table Games"
    case other   => other.capitalize

  def load(extra: Option[String]): Catalogue =
    val shipped = parse(Http.resource("/games.json"))
    Catalogue(shipped ++ extra.map(parse).getOrElse(Vector.empty).filterNot(g => shipped.exists(_.code == g.code)))

  private def parse(text: String): Vector[Game] =
    def strings(n: JsonNode, f: String) = Option(n.get(f)).map(_.elements().asScala.toVector.map(_.asText())).getOrElse(Vector.empty)
    mapper.readTree(text).elements().asScala.toVector.map { n =>
      Game(n.get("code").asText(), n.get("name").asText(), n.get("category").asText(), n.get("developer").asText(),
        strings(n, "bonusTypes"), strings(n, "features"), strings(n, "themes"))
    }
