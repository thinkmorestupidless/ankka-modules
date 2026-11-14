package io.eitheror.casino.publicapi.games

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** One game as `game-provider` serves it to this service (facts.md G8), with the contract's `Game` built from it. */
final case class CatalogueGame(code: String, removedFromFeed: Boolean, releaseDate: Option[String], view: ObjectNode)

object CatalogueGame:
  private val mapper = new ObjectMapper()
  private val log    = org.slf4j.LoggerFactory.getLogger("public-api")
  private val Code   = "^[A-Za-z0-9._-]{1,64}$".r
  private val Date   = "^\\d{4}-\\d{2}-\\d{2}$".r

  private def text(n: JsonNode, k: String): Option[String] = Option(n.get(k)).filter(_.isTextual).map(_.asText()).filter(_.nonEmpty)
  private def list(n: JsonNode, k: String): Vector[String] =
    Option(n.get(k)).filter(_.isArray).fold(Vector.empty)(_.elements().asScala.filter(_.isTextual).map(_.asText()).toVector)

  /** A strict ISO calendar date, else none (original `calendarDate`). */
  def calendarDate(v: Option[String]): Option[String] =
    v.filter(Date.matches).filter(d => Try(LocalDate.parse(d)).toOption.exists(_.toString == d))

  /** `GameItem.isReleasedBy`: the release day counts; no date, or one that does not parse, counts as released. */
  def releasedBy(releaseDate: Option[String], today: LocalDate): Boolean = calendarDate(releaseDate).forall(d => !LocalDate.parse(d).isAfter(today))

  /**
   * The contract's `Game` (original `mappers/game.ts`): `tags` the lower-cased, sorted union of bonus types, features
   * and themes; optional fields absent, never null. None without a code; a game without its required fields is dropped
   * and logged, as the original drops it.
   */
  def from(n: JsonNode): Option[CatalogueGame] =
    text(n, "gameId").flatMap { code =>
      val name = text(n, "name"); val category = text(n, "category"); val developer = text(n, "developerId")
      val missing = Vector("game_code" -> Some(code).filter(Code.matches), "name" -> name, "category" -> category, "developer" -> developer)
        .collect { case (k, None) => k }
      if missing.nonEmpty then
        log.warn("record dropped: missing required contract fields resource=games record_id={} missing_fields={}", code, missing.mkString(","))
        None
      else
        val bonusTypes = list(n, "bonusTypes"); val features = list(n, "features"); val themes = list(n, "themes")
        val v = mapper.createObjectNode().put("id", code).put("name", name.get).put("category", category.get).put("developerId", developer.get)
          .put("developerName", text(n, "developerName").getOrElse(developer.get))
          .put("funMode", Option(n.get("funMode")).exists(_.asBoolean(false)))
        def arr(k: String, xs: Vector[String]) = { val a = v.putArray(k); xs.foreach(a.add); () }
        arr("bonusTypes", bonusTypes); arr("features", features); arr("themes", themes)
        arr("tags", (bonusTypes ++ features ++ themes).filter(_.nonEmpty).map(_.toLowerCase).distinct.sorted)
        arr("restrictedTerritories", list(n, "restrictedTerritories")); arr("prohibitedTerritories", list(n, "prohibitedTerritories"))
        text(n, "thumbnailUrl").foreach(v.put("thumbnailUrl", _))
        text(n, "rtp").foreach(v.put("rtp", _))
        Option(n.get("volatility")).filter(_.isIntegralNumber).foreach(x => v.put("volatility", x.asLong()))
        text(n, "hitRatio").foreach(v.put("hitRatio", _))
        text(n, "maxPayoutCoeff").foreach(v.put("maxPayoutCoeff", _))
        calendarDate(text(n, "releaseDate")).foreach(v.put("releaseDate", _))
        calendarDate(text(n, "deprecationDate")).foreach(v.put("deprecationDate", _))
        Some(CatalogueGame(code, Option(n.get("removedFromFeed")).exists(_.asBoolean(false)), text(n, "releaseDate"), v))
    }

  def parse(body: String): Option[Vector[CatalogueGame]] =
    Try(mapper.readTree(body)).toOption.flatMap(n => Option(n).map(_.get("games"))).filter(n => n != null && n.isArray)
      .map(_.elements().asScala.toVector.flatMap(from).sortBy(_.code))

/**
 * One value kept for `ttl` (original `ttlCache.ts`): the first load blocks its callers and fails them when it fails,
 * nothing cached; one load at a time; past the ttl the old value is served while one reload runs, and a failed reload
 * keeps it, to be tried again by the next request past the ttl.
 */
final class TtlCache[A](ttl: Duration, now: () => Instant)(using ec: ExecutionContext):
  private final case class Entry(value: A, loadedAt: Instant)
  private val entry    = AtomicReference[Option[Entry]](None)
  private val inFlight = AtomicReference[Option[Future[A]]](None)

  def get(load: () => A, wait: scala.concurrent.duration.FiniteDuration): A =
    val held = entry.get
    held match
      case Some(e) if Duration.between(e.loadedAt, now()).compareTo(ttl) < 0 => e.value
      case Some(e) => loading(load): Unit; e.value
      case None    => Await.result(loading(load), wait)

  private def loading(load: () => A): Future[A] = synchronized {
    inFlight.get.getOrElse {
      val p = Promise[A]()
      inFlight.set(Some(p.future))
      Future(load()).onComplete { r =>
        r.foreach(v => entry.set(Some(Entry(v, now()))))
        synchronized(inFlight.set(None))
        p.complete(r)
      }
      p.future
    }
  }

/**
 * The brand's available games (FR-027): `game-provider`'s listed games (its enablement and the brand's switches
 * applied there) less those removed from the feed and those not yet released today in UTC, in code order.
 */
final class Catalogue(load: () => Vector[CatalogueGame], ttl: Duration, now: () => Instant, wait: scala.concurrent.duration.FiniteDuration)(using ExecutionContext):
  private val cache = TtlCache[Vector[CatalogueGame]](ttl, now)

  def available(): Vector[CatalogueGame] =
    val today = LocalDate.ofInstant(now(), ZoneOffset.UTC)
    cache.get(load, wait).filter(g => !g.removedFromFeed && CatalogueGame.releasedBy(g.releaseDate, today))

  def byCode(code: String): Option[CatalogueGame] = available().find(_.code == code)

object Catalogue:
  val Provider = "game-provider"

  /** `GET /v1/casino/brands/{brand}/game-provider/games` (T015), as this service; anything but 200 is a failed load. */
  def fromProvider(services: ServiceClients, brand: String): () => Vector[CatalogueGame] = () =>
    val r    = services(Provider).request("GET", s"/v1/casino/brands/$brand/game-provider/games", None, None, Nil)
    val body = String(r.body, UTF_8)
    if r.status != 200 then throw IllegalStateException(s"game-provider answered ${r.status}")
    CatalogueGame.parse(body).getOrElse(throw IllegalStateException("game-provider's games cannot be read"))
