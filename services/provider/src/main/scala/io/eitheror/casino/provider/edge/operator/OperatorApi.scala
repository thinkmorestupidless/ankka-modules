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
    health: (String, String) => Boolean
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
          game(code).filter(g => entry.runs(g.code) && (!demo || g.demo)) match
            case None => refused(OperatorApi.UnknownGame)
            case Some(_) if entry.launchesRefused => refused(OperatorApi.KeyDocumentWithRealMoney)
            case Some(g) if !health(g.owner, g.code) => refused(OperatorApi.Unavailable)
            case Some(g) =>
              val token = body.string("token")
              // Nothing of `player_profile` is read or kept (data-model, Session).
              client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.launch).invoke(
                SessionEntity.Launch(entry.slug, body.string("player"), body.string("currency"), g.code, "default", token, 0L, Wiring.now(),
                  demo = demo, language = entry.language)
              ): Unit
              Http.json(200, GoJson.strings("game_url" -> s"${g.pageAddress}?token=$token"))
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

object OperatorApi:
  val AuthFailed  = "auth_failed"
  val UnknownGame = "unknown_game"
  val Unavailable = "unavailable"
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
