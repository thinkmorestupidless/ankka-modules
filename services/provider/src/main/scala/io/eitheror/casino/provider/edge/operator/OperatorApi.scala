package io.eitheror.casino.provider.edge.operator

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.provider.core.{SessionEntity, Wiring}
import io.eitheror.casino.provider.edge.brands.{BrandCache, BrandEntry}
import io.eitheror.casino.provider.edge.registry.{Game, GameEntity, GameIndex}
import io.eitheror.casino.provider.st8.*
import io.eitheror.casino.provider.st8.GoJson.Value.*

import java.util.concurrent.{Executors, TimeUnit}
import scala.util.Try
import scala.util.control.NonFatal

/**
 * ST8's operator API as the house provider serves it (spec 026 R4, R5; the stand-in's wire, spec 020
 * `contracts/st8-wire.md`): `launch` and the games list, with free rounds beside them. Public, and
 * admitted by the brand's signature: the brand is found by the `site` its call names, and the
 * `x-st8-sign` header is verified under that entry's operator public key, over the body for a POST
 * and over the query as written for a GET, as the brand's adapter signs them. A call that fails it
 * is answered `auth_failed` with nothing stored.
 *
 * `health` is how a launch learns whether a game can take it: the game service's `/v1/game/health`,
 * by its owner's name, given 2 s.
 */
final class OperatorApi(
    client: ComponentClient,
    cache: BrandCache,
    health: (String, String) => Boolean,
    games: String => com.thinkmorestupidless.ankka.sdk.ServiceClient
) extends HttpEndpoint("/api/operator/v1"):

  val acl: Acl = Routes.public

  private def refused(word: String): Http.Answer = Http.json(200, GoJson.sorted("error" -> Str(word), "status" -> Str("error")))

  private def signedBy(entry: BrandEntry, signed: String): Boolean =
    request.header(Signer.Header).exists(h => Try(Signer.verify(signed.getBytes("UTF-8"), h, Signer.publicKey(entry.operatorPublicKey))).getOrElse(false))

  /** The brand a call names by `site`, if its signature verifies under that brand's key. */
  private def admitted(site: Option[String], signed: String)(f: BrandEntry => Http.Answer): Http.Answer =
    site.flatMap(cache.bySite) match
      case Some(entry) if signedBy(entry, signed) => f(entry)
      case _                                      => refused(OperatorApi.AuthFailed)

  private def heldByAnother(token: String, slug: String): Boolean =
    token.nonEmpty &&
      (try
        val s = client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.get).invoke()
        s.launched && s.brand != slug
      catch case _: CommandError => false) // a ticket never launched is held by no one

  private def game(code: String): Option[Game] =
    try Some(client.forKeyValueEntity(EntityId(code)).call(GameEntity.get).invoke())
    catch case _: CommandError => None

  postBody("/launch") { (text: String) =>
    Http.read(text, strings = Set("game_code", "currency", "token", "player"), booleans = Set("fun_mode")) match
      case None => Http.badRequest
      case Some(body) =>
        admitted(OperatorApi.site(body.raw.get("site")), text) { entry =>
          val code = body.string("game_code")
          val demo = Option(body.raw.get("fun_mode")).exists(_.asBoolean(false))
          // A brand that takes real money holds the key itself; an entry naming the document for one
          // refuses every launch, whatever it asks for (FR-020).
          if entry.launchesRefused then refused(s"${OperatorApi.KeyDocumentWithRealMoney} in the entry of ${entry.slug}")
          else game(code).filter(g => entry.runs(g.code) && (!demo || g.demo)) match
            case None => refused(OperatorApi.UnknownGame)
            case Some(g) if !health(g.owner, g.code) => refused(OperatorApi.Unavailable)
            case Some(g) if heldByAnother(body.string("token"), entry.slug) =>
              // A session belongs to one brand (FR-018): a ticket another brand launched is not
              // this brand's to launch again, and its session is never answered across.
              GameHealth.log.warn(s"launch from ${entry.slug} refused: its ticket is a session of another brand")
              refused(OperatorApi.AuthFailed)
            case Some(g) =>
              val token = body.string("token")
              // Nothing of `player_profile` is read or kept (data-model, Session).
              // Every stake and settle renders the session's currency by its exponent: one contract.money
              // does not know is refused here, not at the first stake.
              if !OperatorApi.knownCurrency(body.string("currency")) then refused(OperatorApi.InvalidCurrency)
              else
                try
                  client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.launchOnce).invoke(
                    SessionEntity.Launch(entry.slug, body.string("player"), body.string("currency"), g.code, "default", token, 0L, Wiring.now(),
                      demo = demo, language = entry.language, owner = g.owner)
                  ): Unit
                  io.eitheror.casino.provider.edge.admin.Metrics.launch(entry.slug, g.code)
                  Http.json(200, GoJson.strings("game_url" -> s"${g.pageAddress}?token=$token"))
                catch case e: CommandError if e.message.startsWith(SessionEntity.TicketUsed) => refused(SessionEntity.TicketUsed)
        }
  }

  private def listing(entry: BrandEntry): Http.Answer =
    val codes = client.forKeyValueEntity(EntityId(GameIndex.Id)).call(GameIndex.codes).invoke()
    Http.json(200, OperatorApi.listing(codes.flatMap(game).filter(g => entry.runs(g.code))))

  // A GET is signed over its query as the adapter writes it: `site=<site>`.
  get("/games") { () =>
    val site = query.raw("site")
    admitted(site, site.fold("")(s => s"site=$s"))(listing)
  }

  postBody("/games") { (text: String) =>
    val site = Http.read(text).flatMap(b => OperatorApi.site(b.raw.get("site")))
    admitted(site, text)(listing)
  }

  // ---- free rounds (T041, research R9): handed to the game, routed by the index -------------------

  private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

  /** A call to the game service that owns a bonus's game; its answer as JSON, or `None` when it did not answer. */
  private def onGame(owner: String, method: String, path: String, body: Option[com.fasterxml.jackson.databind.JsonNode])
      : Option[(Int, com.fasterxml.jackson.databind.JsonNode)] =
    try
      val r = games(owner).request(method, path, body.map(mapper.writeValueAsBytes), body.map(_ => "application/json"), Nil)
      Some(r.status -> Option(mapper.readTree(r.body)).getOrElse(mapper.createObjectNode()))
    catch case NonFatal(e) =>
      GameHealth.log.warn(s"game service $owner did not answer $method $path: $e")
      None

  /**
   * The game's answer as the brand reads one: the stand-in's envelope when the game holds the bonus,
   * or the game's own word (`rejected`, `failed`) as the status, which the brand's adapter maps.
   */
  private def answered(bonusId: String, game: Option[(Int, com.fasterxml.jackson.databind.JsonNode)]): Http.Answer =
    game match
      case None => Http.json(200, GoJson.strings("status" -> "failed"))
      case Some((status, _)) if status == 404 => Http.json(200, GoJson.strings("status" -> "error"))
      case Some((_, node)) =>
        val word = St8Protocol.text(node, "status").getOrElse("failed")
        if word == "rejected" || word == "failed" then Http.json(200, GoJson.strings("status" -> word))
        else
          import scala.jdk.CollectionConverters.*
          val instances = Option(node.get("instances")).toVector.flatMap(_.elements().asScala).map { i =>
            GoJson.sorted("player" -> Str(St8Protocol.text(i, "player").getOrElse("")),
              "remaining" -> Num(Option(i.get("remaining")).map(_.asLong()).getOrElse(0L).toString),
              "status" -> Str(St8Protocol.text(i, "status").getOrElse("")))
          }
          Http.json(200, GoJson.sorted("bonus" -> GoJson.sorted("bonus_id" -> Str(bonusId), "instances" -> Arr(instances)), "status" -> Str("ok")))

  private def route(bonusId: String, entry: BrandEntry): Option[BonusRoute] =
    try Some(client.forKeyValueEntity(EntityId(bonusId)).call(BonusIndex.get).invoke()).filter(_.brand == entry.slug)
    catch case _: CommandError => None

  postBody("/bonus/create") { (text: String) =>
    Http.read(text, strings = Set("bonus_id", "value", "type", "currency"), stringArrays = Set("players", "game_codes"), flexInts = Set("count", "duration")) match
      case None => Http.badRequest
      case Some(body) =>
        admitted(OperatorApi.site(body.raw.get("site")), text) { entry =>
          // One game per bonus: every code must be one game's, registered and the brand's to run.
          val found = body.strings("game_codes").distinct.map(game)
          found match
            case Vector(Some(g)) if entry.runs(g.code) =>
              val bonusId = body.string("bonus_id")
              val seconds = body.flexInt("duration")
              val ask = mapper.createObjectNode()
              ask.put("bonusId", bonusId).put("brand", entry.slug).put("code", g.code)
                .put("count", body.flexInt("count")).put("value", body.string("value")).put("currency", body.string("currency"))
              val players = ask.putArray("players")
              body.strings("players").foreach(players.add)
              // The brand asks a duration in seconds; the game takes whole days, never fewer than asked.
              if seconds > 0 then ask.put("durationDays", math.ceil(seconds / 86400.0).toInt): Unit
              try
                client.forKeyValueEntity(EntityId(bonusId)).call(BonusIndex.put).invoke(BonusRoute(bonusId, entry.slug, g.code, g.owner, Wiring.now())): Unit
                answered(bonusId, onGame(g.owner, "POST", "/v1/game/bonus", Some(ask)))
              // A bonus id another brand holds: the stand-in's word for a bonus it cannot take (review nit).
              catch case _: CommandError => Http.json(200, GoJson.strings("status" -> "error"))
            case _ => refused(OperatorApi.UnknownGame)
        }
  }

  // Signed over its query as the adapter writes it: `bonus_id=<id>&site=<site>`.
  get("/bonus/get") { () =>
    val id   = query.raw("bonus_id").getOrElse("")
    val site = query.raw("site")
    admitted(site, s"bonus_id=$id&site=${site.getOrElse("")}") { entry =>
      route(id, entry).fold(Http.json(200, GoJson.strings("status" -> "error")))(r => answered(id, onGame(r.owner, "GET", s"/v1/game/bonus/$id", None)))
    }
  }

  postBody("/bonus/cancel") { (text: String) =>
    Http.read(text, strings = Set("bonus_id", "player")) match
      case None => Http.badRequest
      case Some(body) =>
        admitted(OperatorApi.site(body.raw.get("site")), text) { entry =>
          val id = body.string("bonus_id")
          route(id, entry).fold(Http.json(200, GoJson.strings("status" -> "error"))) { r =>
            val ask = mapper.createObjectNode().put("player", body.string("player"))
            answered(id, onGame(r.owner, "POST", s"/v1/game/bonus/$id/cancel", Some(ask)))
          }
        }
  }

object OperatorApi:
  val AuthFailed  = "auth_failed"
  val UnknownGame = "unknown_game"
  val Unavailable = "unavailable"
  /** A launch in a currency ISO 4217 does not list (review): refused at launch, before any stake renders it. */
  val InvalidCurrency = "invalid_currency"

  /**
   * An ISO 4217 code: `contract.money`'s shape (three capitals) and listed by the JDK's table, whose
   * exponent `Amounts` renders by. `XXQ` has the shape and is no currency.
   */
  def knownCurrency(code: String): Boolean =
    io.eitheror.casino.contract.money.Currency.from(code).isRight && scala.util.Try(java.util.Currency.getInstance(code).getDefaultFractionDigits).toOption.exists(_ >= 0)
  /** An entry stating real money that names the key document (FR-020): every launch is refused naming it. */
  val KeyDocumentWithRealMoney = "brand_entry_refused: keyDocument with realMoney"

  /** `site` as a brand sends it: a string, or an object whose `id` names it. */
  def site(node: com.fasterxml.jackson.databind.JsonNode): Option[String] =
    Option(node).filter(!_.isNull).map(s => if s.isObject then St8Protocol.text(s, "id").getOrElse(s.toString) else s.asText()).filter(_.nonEmpty)

  /**
   * The games list in the stand-in's shape (`main.go:593-634`, spec 020 R7): games in registry
   * order, developers and categories sorted by id; `fun_mode` and the territories per game.
   */
  def listing(games: Vector[Game]): GoJson.Value =
    val categories = games.map(_.category).distinct.sorted.map(id => GoJson.sorted("id" -> Str(id), "name" -> Str(Catalogue.categoryName(id))))
    val developers = games.map(_.developer).distinct.sorted.map(d => GoJson.sorted("developer_id" -> Str(d), "name" -> Str(d)))
    val listed = games.map { g =>
      GoJson.sorted(
        "bonus_types" -> GoJson.strArr(g.bonusTypes), "category" -> Str(g.category), "code" -> Str(g.code),
        "developer" -> Str(g.developer), "enabled" -> Bool(true), "features" -> GoJson.strArr(g.features),
        "fun_mode" -> Bool(g.demo), "name" -> Str(g.name), "prohibited_territories" -> GoJson.strArr(g.prohibitedTerritories),
        "restricted_territories" -> GoJson.strArr(g.restrictedTerritories), "themes" -> GoJson.strArr(g.themes)
      )
    }
    GoJson.sorted("categories" -> Arr(categories), "developers" -> Arr(developers), "games" -> Arr(listed))

/** A game service's health, asked by name with a bound of its own (2 s); anything but a 2xx is down. */
final class GameHealth(services: String => com.thinkmorestupidless.ankka.sdk.ServiceClient, within: Long = 2000L):
  private val pool = Executors.newVirtualThreadPerTaskExecutor()

  def apply(owner: String, code: String): Boolean =
    val asked = pool.submit(() => services(owner).request("GET", "/v1/game/health", None, None, Nil).status / 100 == 2)
    try asked.get(within, TimeUnit.MILLISECONDS)
    catch case NonFatal(e) => { asked.cancel(true); GameHealth.log.warn(s"game service $owner did not answer its health check for $code: $e"); false }

object GameHealth:
  private[operator] val log = org.slf4j.LoggerFactory.getLogger(classOf[GameHealth])
