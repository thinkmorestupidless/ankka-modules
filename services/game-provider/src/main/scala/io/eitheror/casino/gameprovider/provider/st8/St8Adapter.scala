package io.eitheror.casino.gameprovider.provider.st8

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.catalogue.GameFields
import io.eitheror.casino.gameprovider.config.ProviderConfiguration
import io.eitheror.casino.gameprovider.provider.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.util.Try

/**
 * ST8's operator API (contracts/provider-api.md): every request signed with the brand's operator
 * private key, over the body for a POST and over the query string for a GET; HTTP/1.1, which the
 * original forced because ST8 closes with a reset. The name is ST8's own; another name speaks the same
 * protocol under another provider's prefix, which is how the tests stand up a second provider.
 */
final class St8Adapter(val name: String = "st8", timeout: Duration = Duration.ofSeconds(10)) extends ProviderAdapter:

  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build()

  private def key(c: ProviderConfiguration) =
    St8Signature.privateKey(c.operatorPrivateKey).left.map(e => s"the operator private key is unusable: $e")

  private def post(c: ProviderConfiguration, path: String, body: JsonNode): Either[String, (Int, Option[JsonNode])] =
    val text = Json.render(body)
    key(c).flatMap { k =>
      send(HttpRequest.newBuilder(URI.create(c.apiUrl.stripSuffix("/") + path)).timeout(timeout)
        .header("Content-Type", "application/json").header(St8Signature.Header, St8Signature.sign(text, k))
        .POST(HttpRequest.BodyPublishers.ofString(text, UTF_8)).build())
    }

  /** A GET signed over its query string as written, before encoding (`site=…`, `bonus_id=…&site=…`). */
  private def get(c: ProviderConfiguration, path: String, query: Vector[(String, String)]): Either[String, (Int, Option[JsonNode])] =
    val signed  = query.map((k, v) => s"$k=$v").mkString("&")
    val encoded = query.map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")
    key(c).flatMap { k =>
      send(HttpRequest.newBuilder(URI.create(s"${c.apiUrl.stripSuffix("/")}$path?$encoded")).timeout(timeout)
        .header(St8Signature.Header, St8Signature.sign(signed, k)).GET().build())
    }

  private def enc(s: String) = java.net.URLEncoder.encode(s, UTF_8).replace("+", "%20")

  private def send(request: HttpRequest): Either[String, (Int, Option[JsonNode])] =
    Try(client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))).toEither.left
      .map(e => s"the provider is unreachable: ${e.getClass.getSimpleName}: ${e.getMessage}")
      .map(r => r.statusCode -> Json.parse(r.body))

  def launch(c: ProviderConfiguration, r: LaunchRequest): Either[String, String] =
    val body = Json.obj(
      "game_code" -> r.gameCode, "currency" -> r.currency, "token" -> r.token, "player" -> r.playerId, "fun_mode" -> r.funMode,
      "site" -> Json.obj("id" -> r.siteId, "lobby_url" -> r.lobbyUrl, "deposit_url" -> r.depositUrl, "wallet_url" -> r.walletUrl),
      "player_profile" -> Json.obj("id" -> r.playerId, "default_currency" -> r.defaultCurrency, "reg_country" -> r.regCountry,
        "jurisdiction" -> r.jurisdiction, "birth_date" -> r.birthDate, "reg_date" -> r.regDate, "bet_limits" -> r.betLimits)
    )
    post(c, "/api/operator/v1/launch", body).flatMap {
      case (200, Some(answer)) => answer.text("game_url").toRight(s"the provider answered no game_url: ${Json.render(answer).take(200)}")
      case (status, answer)    => Left(s"the provider answered $status: ${answer.map(Json.render).getOrElse("").take(200)}")
    }

  def games(c: ProviderConfiguration): Either[String, Vector[ListedGame]] =
    get(c, "/api/operator/v1/games", Vector("site" -> c.site)).flatMap {
      case (status, Some(doc)) if status / 100 == 2 && doc.has("games") => Right(St8Adapter.readGames(doc))
      case (status, _)                                                   => Left(s"the game list answered $status")
    }

  def createBonus(c: ProviderConfiguration, r: BonusRequest): Either[String, String] =
    post(c, "/api/operator/v1/bonus/create", Json.obj(
      "bonus_id" -> r.grantId, "players" -> Json.arr(r.playerId), "count" -> r.count, "value" -> r.spinValue, "type" -> "free_bets",
      "currency" -> r.currency, "game_codes" -> Json.arr(r.gameCodes*), "site" -> c.site, "start_time" -> r.startTime,
      "duration" -> r.durationSeconds
    )).map((_, answer) => St8Adapter.bonusStatus(answer))

  def bonus(c: ProviderConfiguration, grantId: String): Either[String, String] =
    get(c, "/api/operator/v1/bonus/get", Vector("bonus_id" -> grantId, "site" -> c.site)).map((_, a) => St8Adapter.bonusStatus(a))

  def cancelBonus(c: ProviderConfiguration, grantId: String, playerId: String): Either[String, String] =
    post(c, "/api/operator/v1/bonus/cancel", Json.obj("bonus_id" -> grantId, "player" -> playerId, "site" -> c.site))
      .map((_, a) => St8Adapter.bonusStatus(a))

  def spinValues(c: ProviderConfiguration, gameCodes: Vector[String], currency: String): Either[String, Vector[String]] =
    get(c, "/api/operator/v1/bonus/offers",
      Vector("game_codes" -> gameCodes.mkString(","), "currency" -> currency, "type" -> "free_bets", "site" -> c.site)).flatMap {
      case (status, Some(doc)) if status / 100 == 2 && doc.text("status").contains("ok") =>
        Right(doc.objects("offers").flatMap(_.text("value")).distinct.sortBy(v => Try(BigDecimal(v)).getOrElse(BigDecimal(0))))
      case (status, _) => Left(s"spin values answered $status")
    }

object St8Adapter:
  /** `bonus.instances[0].status` in the original's groups (`St8BonusClient.scala:31-36, 180-195`); absent is processing. */
  def bonusStatus(answer: Option[JsonNode]): String =
    val raw = answer.flatMap(a => Option(a.get("bonus"))).flatMap(_.objects("instances").headOption).flatMap(_.text("status"))
      .orElse(answer.flatMap(_.text("status")).filter(s => s != "ok"))
    raw.map(_.toLowerCase) match
      case Some("active" | "accepted")            => BonusStatus.Active
      case Some("canceled" | "cancelled")         => BonusStatus.Cancelled
      case Some("rejected")                       => BonusStatus.Rejected
      case Some("failed" | "error")               => BonusStatus.Failed
      case Some("processing" | "pending") | None  => BonusStatus.Processing
      case Some(_)                                => BonusStatus.Processing

  /** ST8's game list in the seeder's mapping; a game with no code is skipped; a missing thumbnail is built as the original did. */
  def readGames(doc: JsonNode): Vector[ListedGame] =
    val developers = doc.objects("developers").flatMap(d => for id <- d.text("developer_id"); n <- d.text("name") yield id -> n).toMap
    doc.objects("games").flatMap { g =>
      g.text("code").map { code =>
        val developer = g.textOr("developer", "")
        ListedGame(code, GameFields(
          name = g.textOr("name", ""),
          developerId = developer,
          developerName = developers.getOrElse(developer, developer),
          category = g.textOr("category", ""),
          themes = g.strings("themes"),
          features = g.strings("features"),
          bonusTypes = g.strings("bonus_types"),
          restrictedTerritories = g.strings("restricted_territories"),
          prohibitedTerritories = g.strings("prohibited_territories"),
          deprecationDate = g.text("deprecation_date"),
          releaseDate = g.text("release_date"),
          rtp = g.text("rtp"),
          volatility = g.long("volatility").map(_.toInt),
          hitRatio = g.text("hit_ratio"),
          maxPayoutCoeff = g.text("max_payout_coeff"),
          thumbnailUrl = g.text("thumbnail_url").getOrElse(s"https://luckmedia.link/$code/thumb.webp"),
          funMode = g.bool("fun_mode").getOrElse(false),
          enabled = g.bool("enabled").getOrElse(false)
        ))
      }
    }
