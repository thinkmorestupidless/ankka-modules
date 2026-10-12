package io.eitheror.casino.gameprovider.catalogue

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.gameprovider.Json

import java.time.{Instant, LocalDate}
import java.util.concurrent.ConcurrentHashMap

/** A player's catalogue query (FR-023), every filter as the original's `GameRoutes` reads it. */
final case class CatalogueQuery(
    category: Option[String] = None,
    themes: Option[String] = None,
    features: Option[String] = None,
    bonusTypes: Option[String] = None,
    tag: Option[String] = None,
    developer: Option[String] = None,
    search: Option[String] = None,
    country: Option[String] = None,
    limit: Option[Int] = None,
    offset: Option[Int] = None
)

/** A code that names more than one provider's game, and the providers that carry it (FR-023). */
final case class Ambiguous(code: String, providers: Vector[String])

/**
 * The catalogue's reads (FR-022 to FR-024). The view is read whole and filtered here, as the original
 * filtered its cached list (`GameRoutes.scala:234-341`): a dimension filter matches a union of the
 * game's category and tags that a JSON containment query cannot express, and 10,000 rows is small. The
 * snapshot is kept ten seconds; the brand's configuration is read on every call, so a change applies at
 * once (FR-022).
 */
final class CatalogueReads(views: ViewClient, brand: String, components: ComponentClient, ttlMillis: Long = 10_000):
  @volatile private var snapshot: (Long, Vector[GameState]) = (0L, Vector.empty)

  def games(): Vector[GameState] =
    val (at, rows) = snapshot
    if System.currentTimeMillis() - at < ttlMillis then rows
    else
      val read = views.forView(Catalogue).all(100_000).map(_.game).filter(_.exists).sortBy(g => (g.code, g.provider))
      snapshot = (System.currentTimeMillis(), read)
      read

  /** Forgets the snapshot, after a refresh or in a test that needs the write it just made. */
  def invalidate(): Unit = snapshot = (0L, Vector.empty)

  def configuration(): CatalogueConfiguration =
    components.forKeyValueEntity(EntityId(brand)).call(BrandCatalogueConfiguration.get).invoke()

  def browse(q: CatalogueQuery, today: LocalDate): (Vector[GameState], Option[(Int, Option[Int])]) =
    val config = configuration()
    val before = games()
      .filter(g => Enablement.listed(g, config))
      .filter(g => CatalogueReads.matches(q.category, CatalogueReads.categoryTags(g)))
      .filter(g => CatalogueReads.matches(q.themes, CatalogueReads.categoryTags(g)))
      .filter(g => CatalogueReads.matches(q.features, CatalogueReads.categoryTags(g)))
      .filter(g => CatalogueReads.matches(q.bonusTypes, CatalogueReads.categoryTags(g)))
      .filter(g => CatalogueReads.matches(q.tag, CatalogueReads.tags(g)))
      .filter(g => CatalogueReads.matches(q.developer, Set(g.fields.get.developerId.toLowerCase, Catalogue.slug(g.fields.get.developerId))))
      .filter(g => q.search.forall(s => g.fields.get.name.toLowerCase.contains(s.trim.toLowerCase)))
      .filter(g => g.fields.get.deprecationDate.forall(d => scala.util.Try(LocalDate.parse(d).isAfter(today)).getOrElse(true)))
      .filter(g => CatalogueReads.allowedIn(g, q.country))
    q.limit match
      case None => before -> None
      case Some(raw) =>
        val total  = before.size
        val limit  = math.max(1, math.min(3000, raw))
        val offset = math.max(0, q.offset.getOrElse(0))
        val page   = before.slice(offset, offset + limit)
        page -> Some(total -> Option.when(offset + page.size < total)(offset + page.size))

  /** A batch by code, ignoring every other filter, as the single-game read decides (the provider's flag, not removed). */
  def batch(codes: Vector[String]): Vector[GameState] =
    val wanted = codes.map(_.trim).filter(_.nonEmpty).distinct.take(50).toSet
    games().filter(g => wanted(g.code) && g.fields.exists(_.enabled) && !g.removedFromFeed)

  /** One game by code: among the brand's providers, or that provider's when named; two carrying the code is ambiguous. */
  def one(code: String, provider: Option[String]): Either[Ambiguous, Option[GameState]] =
    val found = games().filter(g => g.code == code && provider.forall(_ == g.provider) && g.fields.exists(_.enabled) && !g.removedFromFeed)
    if found.map(_.provider).distinct.size > 1 then Left(Ambiguous(code, found.map(_.provider).distinct.sorted)) else Right(found.headOption)

  /** Resolves a code to its game whatever its listing, for the launch (FR-008) and an override's check. */
  def resolve(code: String, provider: Option[String]): Either[Ambiguous, Option[GameState]] =
    val found = games().filter(g => g.code == code && provider.forall(_ == g.provider))
    if found.map(_.provider).distinct.size > 1 then Left(Ambiguous(code, found.map(_.provider).distinct.sorted)) else Right(found.headOption)

  def developers(country: Option[String]): Vector[(String, String)] =
    val config = configuration()
    games().filter(g => Enablement.listed(g, config) && CatalogueReads.allowedIn(g, country))
      .map(g => g.fields.get.developerId -> g.fields.get.developerName).distinct.sortBy(_._2.toLowerCase)

  /** Ranked by rounds played at the brand (FR-023), disabled games left out at read time. */
  def popular(limit: Int): Vector[GameState] =
    val config = configuration()
    val byId   = games().map(g => Game.entityId(g.provider, g.code) -> g).toMap
    views.forView(GamePopularity).ordered(sql"1 = 1", jsonNumber("rounds") ++ SqlFragment.raw(" DESC"), 10_000)
      .flatMap(r => byId.get(Game.entityId(r.provider, r.code)))
      .filter(g => Enablement.listed(g, config))
      .take(limit)

object CatalogueReads:
  private val instances = ConcurrentHashMap[ViewClient, CatalogueReads]()

  def shared(views: ViewClient, brand: String, components: ComponentClient): CatalogueReads =
    instances.computeIfAbsent(views, v => CatalogueReads(v, brand, components))

  def tags(g: GameState): Set[String] = g.fields.get.tags.filter(_.nonEmpty).map(_.toLowerCase).toSet

  /** Tags plus the category: what every dimension filter is checked against (`categoryTagsOf`). */
  def categoryTags(g: GameState): Set[String] = tags(g) ++ Option.when(g.fields.get.category.nonEmpty)(g.fields.get.category.toLowerCase)

  /** Absent always matches; present matches any of its comma-separated values. */
  def matches(filter: Option[String], against: Set[String]): Boolean =
    filter.forall(raw => raw.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).exists(against.contains))

  def allowedIn(g: GameState, country: Option[String]): Boolean =
    country.forall { c =>
      val code = c.toUpperCase
      !g.fields.get.restrictedTerritories.map(_.toUpperCase).contains(code) && !g.fields.get.prohibitedTerritories.map(_.toUpperCase).contains(code)
    }

  /** The original's `gameToJson`, plus the provider (Clarifications 2026-10-09). */
  def toJson(g: GameState): ObjectNode =
    val f = g.fields.get
    Json.obj(
      "gameId" -> g.code, "provider" -> g.provider, "name" -> f.name, "category" -> f.category, "developerId" -> f.developerId,
      "developerName" -> f.developerName, "tags" -> f.tags, "enabled" -> f.enabled, "funMode" -> f.funMode,
      "bonusTypes" -> f.bonusTypes, "features" -> f.features, "themes" -> f.themes,
      "restrictedTerritories" -> f.restrictedTerritories, "prohibitedTerritories" -> f.prohibitedTerritories,
      "thumbnailUrl" -> Option(f.thumbnailUrl).filter(_.nonEmpty).getOrElse(null), "rtp" -> f.rtp.getOrElse(null),
      "volatility" -> f.volatility.map(_.toLong).getOrElse(null), "hitRatio" -> f.hitRatio.getOrElse(null),
      "maxPayoutCoeff" -> f.maxPayoutCoeff.getOrElse(null), "releaseDate" -> f.releaseDate.getOrElse(null),
      "deprecationDate" -> f.deprecationDate.getOrElse(null)
    )

  /** The operator's row: every game with each layer of the decision (FR-024, `gameCatalogEntryToJson`). */
  def operatorJson(g: GameState, config: CatalogueConfiguration): ObjectNode =
    val f = g.fields.get
    val l = Enablement.decide(g, config)
    Json.obj(
      "code" -> g.code, "provider" -> g.provider, "name" -> f.name, "category" -> f.category, "developer" -> f.developerId,
      "thumbnailUrl" -> Option(f.thumbnailUrl).filter(_.nonEmpty).getOrElse(null), "st8Enabled" -> l.providerEnabled,
      "removedFromFeed" -> l.removedFromFeed, "individualOverride" -> l.individualOverride.getOrElse(null),
      "categoryDisabled" -> l.categoryDisabled, "developerDisabled" -> l.developerDisabled, "effectiveEnabled" -> l.effectiveEnabled
    )

  def at(now: Instant): LocalDate = now.atZone(java.time.ZoneOffset.UTC).toLocalDate
