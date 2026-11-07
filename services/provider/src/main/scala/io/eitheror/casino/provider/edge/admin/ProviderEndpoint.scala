package io.eitheror.casino.provider.edge.admin

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import io.eitheror.casino.contract.access.Routes
import com.thinkmorestupidless.ankka.runtime.ViewClient
import io.eitheror.casino.provider.core.{Round, RunCallbackRow, RunCallbacks, Session, SessionEntity}
import io.eitheror.casino.provider.edge.brands.{BrandCache, BrandEntry, BrandEntryEntity, BrandIndex}
import io.eitheror.casino.provider.edge.views.SessionsByBrand
import io.eitheror.casino.provider.st8.{GoJson, Http, Signer}
import io.eitheror.casino.provider.st8.GoJson.Value.*

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The provider's operator routes (`contracts/provider-routes.md`): the brand table, and later the
 * sessions, rounds, unanswered calls, keys and registry. Admitted as any staff token at the
 * `eitheror` realm; each route then checks the brand its path names, or the installation-wide
 * `games` role, in `Routes`' own words, since one provider serves many brands and the slug is the
 * path's.
 */
final class ProviderEndpoint(client: ComponentClient, views: ViewClient, cache: BrandCache, authenticate: => Acl = Oidc.authenticate())
    extends HttpEndpoint("/v1/provider"):

  val acl: Acl = Routes.staff(authenticate)

  /**
   * Answers `f` when the token may act on `slug` holding one of `roles` (none: any operator of it).
   * The installation's operator, holding `games`, acts on every brand the provider serves
   * (`contracts/provider-routes.md`: no brand role is needed with it).
   */
  private def asOperator(slug: String, roles: String*)(f: => Http.Answer): Http.Answer =
    if principal.roles.contains(ProviderEndpoint.GamesRole) then f
    else Routes.operatorRefusal(principal, slug, roles*).fold(f)(Http.error(403, _))

  /** Answers `f` when the token holds the installation-wide `games` role or `platform_admin`. */
  private def asInstallationOperator(f: => Http.Answer): Http.Answer =
    if Set(ProviderEndpoint.GamesRole, "platform_admin").exists(principal.roles.contains) then f
    else Http.error(403, s"${Routes.Forbidden}: the ${ProviderEndpoint.GamesRole} permission is needed")

  // ---- the brand table (T034) -----------------------------------------------------------------

  get("/brands") { () =>
    asInstallationOperator(Http.json(200, GoJson.obj("brands" -> Arr(cache.all.sortBy(_.slug).map(ProviderEndpoint.rendered)))))
  }

  get("/brands/{slug}") { (slug: String) =>
    asOperator(slug) {
      cache.entry(slug).fold(Http.error(404, s"no brand entry for $slug"))(e => Http.json(200, ProviderEndpoint.rendered(e)))
    }
  }

  putBody("/brands/{slug}") { (slug: String, text: String) =>
    asOperator(slug, ProviderEndpoint.GamesRole) {
      ProviderEndpoint.parse(slug, text) match
        case Left(problem) => Http.error(400, problem)
        case Right(entry) =>
          cache.all.find(o => o.site == entry.site && o.slug != slug) match
            case Some(other) => Http.error(409, s"site '${entry.site}' is brand ${other.slug}'s")
            case None =>
              try
                val held = client.forKeyValueEntity(EntityId(slug)).call(BrandEntryEntity.put).invoke(entry)
                client.forKeyValueEntity(EntityId(BrandIndex.Id)).call(BrandIndex.add).invoke(slug): Unit
                cache.put(held)
                Http.json(200, ProviderEndpoint.rendered(held))
              catch case e: CommandError => Http.error(400, e.message)
    }
  }

  // ---- sessions and rounds, per brand (T036) ---------------------------------------------------

  private def page(slug: String): (Vector[Session], Option[String]) =
    val (at, id) = SessionsByBrand.cursor(request.query.raw("after"))
    val rows     = views.forView(SessionsByBrand).ask(SessionsByBrand.byBrand, ProviderEndpoint.PageSize + 1, "brand" -> slug, "after_at" -> at, "after_id" -> id)
    val shown    = rows.take(ProviderEndpoint.PageSize)
    (shown, Option.when(rows.size > ProviderEndpoint.PageSize)(SessionsByBrand.cursorAfter(shown.last)))

  private def next(cursor: Option[String]) = "next" -> cursor.fold(Null)(Str(_))

  get("/brands/{slug}/sessions") { (slug: String) =>
    asOperator(slug) {
      val (sessions, cursor) = page(slug)
      Http.json(200, GoJson.obj("sessions" -> Arr(sessions.map(ProviderEndpoint.sessionJson)), next(cursor)))
    }
  }

  get("/brands/{slug}/rounds") { (slug: String) =>
    asOperator(slug) {
      val (sessions, cursor) = page(slug)
      val rounds = sessions.flatMap(s => s.roundOrder.flatMap(s.rounds.get).map(r => ProviderEndpoint.roundJson(s, r)))
      Http.json(200, GoJson.obj("rounds" -> Arr(rounds), next(cursor)))
    }
  }

  get("/brands/{slug}/rounds/{token}") { (slug: String, token: String) =>
    asOperator(slug) {
      val s = client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.get).invoke()
      // Another brand's session is not found here, as one that never was (FR-018).
      if !s.launched || s.brand != slug then Http.error(404, "session not found")
      else
        val callbacks = views.forView(RunCallbacks).ask(RunCallbacks.byRun, 1000, "run" -> s.runId, "after_at" -> "-1", "after_id" -> "")
        Http.json(200, GoJson.obj(
          "session"   -> ProviderEndpoint.sessionJson(s),
          "rounds"    -> Arr(s.roundOrder.flatMap(s.rounds.get).map(r => ProviderEndpoint.roundJson(s, r))),
          "callbacks" -> Arr(callbacks.map(ProviderEndpoint.callbackJson))
        ))
    }
  }

object ProviderEndpoint:

  /** Rows per page of a brand's sessions or rounds. */
  val PageSize = 100

  private def instant(millis: Long): GoJson.Value = Str(java.time.Instant.ofEpochMilli(millis).toString)

  def sessionJson(s: Session): GoJson.Value = GoJson.obj(
    "launchToken" -> Str(s.launchToken), "playerId" -> Str(s.playerId), "gameCode" -> Str(s.gameCode), "currency" -> Str(s.currency),
    "demo" -> Bool(s.demo), "state" -> Str(s.state), "launchedAt" -> instant(s.launchedAt), "rounds" -> Num(s.rounds.size.toString)
  )

  def roundJson(s: Session, r: Round): GoJson.Value = GoJson.obj(
    "launchToken" -> Str(s.launchToken), "gameCode" -> Str(s.gameCode), "roundId" -> Str(r.roundId), "state" -> Str(r.state),
    "free" -> Bool(r.free), "debits" -> GoJson.strArr(r.debits), "credits" -> GoJson.strArr(r.credits), "cancels" -> GoJson.strArr(r.cancels)
  )

  def callbackJson(c: RunCallbackRow): GoJson.Value = GoJson.obj(
    "callbackId" -> Str(c.callbackId), "kind" -> Str(c.kind), "outcome" -> Str(c.outcome), "attempts" -> Num(c.attempts.toString),
    "status" -> c.status.fold(Null)(Str(_)), "balance" -> c.balance.fold(Null)(Str(_)), "firstSentAt" -> instant(c.firstSentAt),
    "nextRetryAt" -> c.nextRetryAt.fold(Null)(instant), "body" -> Str(c.body)
  )

  /** The realm role an installation's operator of the house provider holds (`brands/_realm`). */
  val GamesRole = "games"

  val DefaultProviderWaitMillis     = 5000L
  val DefaultKeyRefreshBoundSeconds = 60L

  private val mapper = ObjectMapper()

  private val Strings  = Set("baseAddress", "site", "operatorPublicKey", "language", "caPem")
  private val Booleans = Set("testBrand", "keyDocument", "realMoney")
  private val Ints     = Set("providerWaitMillis", "keyRefreshBoundSeconds")
  private val Known    = Strings ++ Booleans ++ Ints + "games"

  /** An entry as `contracts/brand-entry.md` writes one, or the first problem, naming the field. */
  def parse(slug: String, text: String): Either[String, BrandEntry] =
    Try(mapper.readTree(text)).toOption.collect { case o: ObjectNode => o } match
      case None => Left("the brand entry is not a JSON object")
      case Some(o) =>
        def present(f: String): Option[JsonNode] = Option(o.get(f)).filterNot(_.isNull)
        val fields = o.fieldNames().asScala.toVector
        val wrongType =
          Strings.find(f => present(f).exists(!_.isTextual)).map(f => s"$f must be a string")
            .orElse(Booleans.find(f => present(f).exists(!_.isBoolean)).map(f => s"$f must be true or false"))
            .orElse(Ints.find(f => present(f).exists(n => !n.canConvertToInt || !n.isIntegralNumber)).map(f => s"$f must be a whole number"))
            .orElse(present("games").filter(n => !n.isArray || n.elements().asScala.exists(!_.isTextual)).map(_ => "games must be a list of codes"))
        def text(f: String)  = present(f).map(_.asText()).getOrElse("")
        def flag(f: String)  = present(f).exists(_.asBoolean())
        def long(f: String, d: Long) = present(f).map(_.asLong()).getOrElse(d)
        val problem =
          Option.when(fields.contains("operatorPrivateKey"))("operatorPrivateKey is never accepted: the provider holds only the brand's public key")
            .orElse(fields.find(!Known.contains(_)).map(f => s"no field '$f'"))
            .orElse(wrongType)
            .orElse(Vector("baseAddress", "site", "operatorPublicKey", "language").find(text(_).isEmpty).map(f => s"$f is required"))
            .orElse(Vector("testBrand", "realMoney").find(present(_).isEmpty).map(f => s"$f is required"))
            .orElse(Option.when(!text("baseAddress").matches("https?://[^?#\\s]+"))("baseAddress must be an http(s) address without a query"))
            .orElse(Option.when(Try(Signer.publicKey(text("operatorPublicKey"))).isFailure)("operatorPublicKey is not a P-256 public key in base64 DER"))
            .orElse(present("caPem").map(_.asText()).filterNot(c => c.startsWith("env:") && c.length > 4 || c.contains("-----BEGIN CERTIFICATE-----"))
              .map(_ => "caPem must be env:<VAR> or a PEM certificate"))
        problem match
          case Some(p) => Left(p)
          case None =>
            val entry = BrandEntry(
              slug, text("baseAddress"), text("site"), text("operatorPublicKey"), text("language"), flag("testBrand"),
              present("games").map(_.elements().asScala.map(_.asText()).toVector).getOrElse(Vector.empty), flag("keyDocument"),
              long("providerWaitMillis", DefaultProviderWaitMillis), long("keyRefreshBoundSeconds", DefaultKeyRefreshBoundSeconds),
              present("caPem").map(_.asText()), flag("realMoney")
            )
            BrandEntry.problems(entry).headOption.toLeft(entry)

  /** An entry as the routes answer it: the fields it was written with, the slug beside them. */
  def rendered(e: BrandEntry): GoJson.Value = GoJson.obj(
    "slug" -> Str(e.slug), "baseAddress" -> Str(e.baseAddress), "site" -> Str(e.site), "operatorPublicKey" -> Str(e.operatorPublicKey),
    "language" -> Str(e.language), "testBrand" -> Bool(e.testBrand), "games" -> GoJson.strArr(e.games), "keyDocument" -> Bool(e.keyDocument),
    "providerWaitMillis" -> Num(e.providerWaitMillis.toString), "keyRefreshBoundSeconds" -> Num(e.keyRefreshBoundSeconds.toString),
    "caPem" -> e.caPem.fold(Null)(Str(_)), "realMoney" -> Bool(e.realMoney)
  )
