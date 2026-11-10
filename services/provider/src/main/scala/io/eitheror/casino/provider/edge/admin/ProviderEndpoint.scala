package io.eitheror.casino.provider.edge.admin

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import io.eitheror.casino.contract.access.Routes
import com.thinkmorestupidless.ankka.runtime.ViewClient
import io.eitheror.casino.provider.core.{Abandonment, Callback, CallbackEntity, Deliver, Round, RunCallbackRow, RunCallbacks, Session, SessionEntity, Wiring}
import io.eitheror.casino.provider.edge.brands.{BrandCache, BrandEntry, BrandEntryEntity, BrandIndex}
import io.eitheror.casino.provider.edge.registry.{GameEntity, GameIndex}
import io.eitheror.casino.provider.edge.views.{DayRow, RoundsByDay, SessionsByBrand, SettledRound}
import io.eitheror.casino.provider.st8.{GoJson, Http, Keys, Signer}
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
final class ProviderEndpoint(client: ComponentClient, views: ViewClient, cache: BrandCache, keys: Keys, authenticate: => Acl = Oidc.authenticate(),
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC)
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

  /**
   * Answers `f` when the token holds the installation-wide `games` role, or one of `also`
   * (`contracts/provider-routes.md`: `platform_admin` reads the brand table and does nothing else here).
   */
  private def asInstallationOperator(also: String*)(f: => Http.Answer): Http.Answer =
    if (ProviderEndpoint.GamesRole +: also).exists(principal.roles.contains) then f
    else Http.error(403, s"${Routes.Forbidden}: the ${ProviderEndpoint.GamesRole} permission is needed")

  // ---- the brand table (T034) -----------------------------------------------------------------

  get("/brands") { () =>
    asInstallationOperator("platform_admin")(Http.json(200, GoJson.obj("brands" -> Arr(cache.all.sortBy(_.slug).map(ProviderEndpoint.rendered)))))
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
                act(ProviderActs.BrandEntryWritten, "brand-entry", slug, slug,
                  "site" -> held.site, "testBrand" -> held.testBrand.toString, "realMoney" -> held.realMoney.toString,
                  "keyDocument" -> held.keyDocument.toString, "games" -> held.games.mkString(","))
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
      request.query.raw("day") match
        // `?day=` lists the rounds settled that day in the reporting zone, whole (the export's read).
        case Some(day) =>
          bounds(day).fold(Http.error(400, s"'$day' is not a day: YYYY-MM-DD")) { (from, until) =>
            val rounds = settledBetween(slug, from, until).flatMap { row =>
              row.settled.filter(r => r.settledAt >= from && r.settledAt < until).flatMap(r => row.session.rounds.get(r.roundId))
                .map(ProviderEndpoint.roundJson(row.session, _))
            }
            Http.json(200, GoJson.obj("rounds" -> Arr(rounds), "next" -> Null))
          }
        case None =>
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

  // ---- the signing key (T038): three operator acts, never a timer (FR-020) -----------------------

  private def keyState: GoJson.Value = GoJson.obj(
    "current" -> Str(keys.currentKeyId), "next" -> keys.nextKeyId.fold(Null)(Str(_)),
    "previous" -> keys.previousKeyId.fold(Null)(Str(_)), "withdrawn" -> keys.withdrawnKeyId.fold(Null)(Str(_))
  )

  get("/keys") { () => asInstallationOperator()(Http.json(200, keyState)) }

  private def keyAct(action: String, kid: String): Unit =
    act(action, "signing-key", kid, ProviderActs.InstallationBrand, "kid" -> kid)

  /** Publishes the next key; signing is unchanged. Starting again during a rotation answers it as it stands. */
  post("/keys/start") { () =>
    asInstallationOperator() {
      val rotating = keys.nextKeyId
      val next     = keys.publishNext()
      if rotating.isEmpty then keyAct(ProviderActs.KeyRotationStarted, next)
      Http.json(200, keyState)
    }
  }

  /** Signs every later call with the next key; the old one stays listed until it is withdrawn. */
  post("/keys/switch") { () =>
    asInstallationOperator() {
      if keys.nextKeyId.isEmpty then Http.error(409, "no_rotation: start a rotation before switching")
      else
        keyAct(ProviderActs.KeySwitched, keys.promote())
        Http.json(200, keyState)
    }
  }

  /** Removes the key signing stopped with from the document, and deletes its private half. */
  post("/keys/withdraw") { () =>
    asInstallationOperator() {
      keys.previousKeyId match
        case None => Http.error(409, "nothing_to_withdraw: switch before withdrawing")
        case Some(old) =>
          keys.withdrawForGood()
          keyAct(ProviderActs.KeyWithdrawn, old)
          Http.json(200, keyState)
    }
  }

  // ---- the audit (T046): every act recorded after the command it audits -------------------------

  /** Records one operator act; `ProviderAudit` publishes it on `platform.audit`. The actor is the staff token's subject. */
  private def act(action: String, subjectKind: String, subjectId: String, brand: String, changes: (String, String)*): Unit =
    client.forEventSourcedEntity(EntityId(ProviderActs.id(subjectKind, subjectId))).call(ProviderActs.record).invoke(
      Act(action, principal.subject, Wiring.now(), subjectKind, subjectId, brand, changes.map(ActChange(_, _)).toVector)
    ): Unit

  // ---- unanswered calls and abandoning one (T044, T045) ---------------------------------------------

  get("/brands/{slug}/unanswered") { (slug: String) =>
    asOperator(slug) {
      val rows = views.forView(RunCallbacks).ask(RunCallbacks.unanswered, 1000, "brand" -> slug)
      Http.json(200, GoJson.obj("unanswered" -> Arr(rows.map(ProviderEndpoint.callbackJson))))
    }
  }

  /**
   * Gives up on a call the brand has not acknowledged (FR-021): its delivery ends and no further
   * attempt is made; the call is listed as abandoned, with the operator and the reason.
   */
  postBody("/brands/{slug}/abandon/{callbackId}") { (slug: String, callbackId: String, text: String) =>
    asOperator(slug, ProviderEndpoint.GamesRole) {
      val reason = Http.read(text, strings = Set("reason")).map(_.string("reason").trim).getOrElse("")
      val held   = Try(client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.get).invoke()).toOption
      // Another brand's call is not found here, as one that never was (FR-018).
      held.filter(_.brand == slug) match
        case None                      => Http.error(404, "callback not found")
        case Some(_) if reason.isEmpty => Http.error(400, "reason is required")
        case Some(c) =>
          val abandonment = Abandonment(principal.subject, reason, Wiring.now())
          try
            try client.forWorkflow(EntityId(callbackId)).call(Deliver.abandon).invoke(abandonment): Unit
            catch case e: CommandError if e.code == ErrorCode.NotFound =>
              // No delivery runs for it (a stake waits on its first answer): the record is ended directly.
              client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.abandon).invoke(abandonment): Unit
            act(ProviderActs.CallbackAbandoned, "callback", callbackId, slug, "reason" -> reason, "kind" -> c.kind)
            Http.json(200, GoJson.obj("callbackId" -> Str(callbackId), "outcome" -> Str(Callback.Abandoned),
              "abandonedBy" -> Str(abandonment.by), "reason" -> Str(reason)))
          catch case e: CommandError => Http.error(409, e.message)
    }
  }

  // ---- the day export (T044, research R14) ------------------------------------------------------------

  /** The day's bounds in the installation's reporting zone, as milliseconds, or none when `day` is not a date. */
  private def bounds(day: String): Option[(Long, Long)] =
    Try(java.time.LocalDate.parse(day)).toOption.map { d =>
      (d.atStartOfDay(zone).toInstant.toEpochMilli, d.plusDays(1).atStartOfDay(zone).toInstant.toEpochMilli)
    }

  /** Every session of `slug` with a round settled in `[from, until)`, read page by page. */
  /** The outcome of the call that settled a round: its last cancel when cancelled, else its last credit (a callback id is its transaction or cancel id). */
  private def settlement(r: SettledRound): String =
    val id = if r.outcome == Round.Cancelled then r.cancels.lastOption else r.credits.lastOption
    id.flatMap(i => try Some(client.forEventSourcedEntity(EntityId(i)).call(CallbackEntity.get).invoke().outcome) catch case _: CommandError => None)
      .getOrElse("")

  private def settledBetween(slug: String, from: Long, until: Long): Vector[DayRow] =
    Iterator.unfold(Option(("-1", ""))) {
      case None => None
      case Some((at, id)) =>
        val rows = views.forView(RoundsByDay).ask(RoundsByDay.settledBetween, ProviderEndpoint.ExportPage,
          "brand" -> slug, "from" -> from.toString, "until" -> until.toString, "after_at" -> at, "after_id" -> id)
        val more = Option.when(rows.size == ProviderEndpoint.ExportPage)(rows.last).map(r => (r.session.launchedAt.toString, r.session.launchToken))
        if rows.isEmpty then None else Some((rows, more))
    }.flatten.toVector

  get("/brands/{slug}/export/{day}") { (slug: String, day: String) =>
    asOperator(slug) {
      bounds(day) match
        case None => Http.error(400, s"'$day' is not a day: YYYY-MM-DD")
        case Some((from, until)) =>
          val lines = settledBetween(slug, from, until).flatMap { row =>
            row.settled.filter(r => r.settledAt >= from && r.settledAt < until).map(r => GoJson.marshal(ProviderEndpoint.exportJson(row.session, r, zone, settlement(r))))
          }
          Http.raw(200, "application/x-ndjson", lines.map(_ + "\n").mkString)
    }
  }

  // ---- the registry (T046) --------------------------------------------------------------------------

  get("/registry") { () =>
    asInstallationOperator() {
      val codes = client.forKeyValueEntity(EntityId(GameIndex.Id)).call(GameIndex.codes).invoke()
      val games = codes.flatMap(c => Try(client.forKeyValueEntity(EntityId(c)).call(GameEntity.get).invoke()).toOption)
      Http.json(200, GoJson.obj("games" -> Arr(games.map(g => GoJson.obj(
        "code" -> Str(g.code), "owner" -> Str(g.owner), "name" -> Str(g.name), "category" -> Str(g.category), "demo" -> Bool(g.demo),
        "pageAddress" -> Str(g.pageAddress)
      )))))
    }
  }

  /** An operator removes a game: no launch reaches it again; its open sessions keep being served. */
  delete("/registry/{code}") { (code: String) =>
    asInstallationOperator() {
      val held = Try(client.forKeyValueEntity(EntityId(code)).call(GameEntity.get).invoke()).toOption
      held match
        case None => Http.error(404, s"no game $code")
        case Some(g) =>
          client.forKeyValueEntity(EntityId(code)).call(GameEntity.remove).invoke(): Unit
          act(ProviderActs.GameRemoved, "game", code, ProviderActs.InstallationBrand, "owner" -> g.owner)
          Http.json(200, GoJson.obj("code" -> Str(code), "removed" -> Bool(true)))
    }
  }

  // ---- metrics (T047, FR-025) ---------------------------------------------------------------------------

  get("/metrics") { () =>
    asInstallationOperator() {
      val unanswered = cache.all.map(e => e.slug -> views.forView(RunCallbacks).ask(RunCallbacks.unanswered, 10000, "brand" -> e.slug)
        .count(_.outcome != Callback.Abandoned).toLong).toMap
      Http.raw(200, "text/plain; version=0.0.4", Metrics.render(unanswered))
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

  /**
   * One provider call as an operator reads it (FR-021): the content as sent, each attempt with the
   * signing key's id, when it was sent, the answer's word and balance and the time taken; the retry
   * state, and who abandoned it and why.
   */
  def callbackJson(c: RunCallbackRow): GoJson.Value = GoJson.obj(
    "callbackId" -> Str(c.callbackId), "kind" -> Str(c.kind), "route" -> Str(c.route), "outcome" -> Str(c.outcome),
    "attempts" -> Num(c.attempts.toString), "status" -> c.status.fold(Null)(Str(_)), "balance" -> c.balance.fold(Null)(Str(_)),
    "firstSentAt" -> instant(c.firstSentAt), "nextRetryAt" -> c.nextRetryAt.fold(Null)(instant), "body" -> Str(c.body),
    "sent" -> Arr(c.attemptsDetail.map(a => GoJson.obj(
      "attempt" -> Num(a.attempt.toString), "keyId" -> Str(a.keyId), "sentAt" -> instant(a.sentAt), "tookMillis" -> Num(a.tookMillis.toString),
      "httpStatus" -> a.httpStatus.fold(Null)(s => Num(s.toString)), "status" -> a.status.fold(Null)(Str(_)),
      "balance" -> a.balance.fold(Null)(Str(_)), "error" -> a.error.fold(Null)(Str(_))
    ))),
    "abandoned" -> c.abandoned.fold(Null)(a => GoJson.obj("by" -> Str(a.by), "reason" -> Str(a.reason), "at" -> instant(a.at)))
  )

  /** Rows per page the export reads the view in. */
  val ExportPage = 500

  /**
   * One settled round as the day export writes it: stake and win in minor units of the session's
   * currency (the amounts the protocol sent, summed), the outcome, and the transaction ids.
   */
  /**
   * `settlement` is the outcome of the call that settled the round (its last credit or cancel):
   * `acknowledged`, or `pending`, `refused`, `exhausted` or `abandoned` for a round the brand has not
   * been seen to pay. A round counts as settled at the provider when its credit is planned, so finance
   * reads this before comparing it with the brand's books.
   */
  def exportJson(s: Session, r: SettledRound, zone: java.time.ZoneId, settlement: String = ""): GoJson.Value =
    import io.eitheror.casino.contract.money.{Amounts, Currency, MinorUnits}
    val currency = Currency.from(s.currency).fold(e => throw IllegalStateException(e), identity)
    def minor(amounts: Vector[String]): Long =
      amounts.map(a => Amounts.parse(a, currency).fold(e => throw IllegalStateException(e.message), _.value)).sum
    GoJson.obj(
      "brand" -> Str(s.brand), "day" -> Str(java.time.Instant.ofEpochMilli(r.settledAt).atZone(zone).toLocalDate.toString),
      "settledAt" -> instant(r.settledAt), "launchToken" -> Str(s.launchToken), "playerId" -> Str(s.playerId), "gameCode" -> Str(s.gameCode),
      "roundId" -> Str(r.roundId), "currency" -> Str(s.currency), "stake" -> Num(minor(r.stakes).toString), "win" -> Num(minor(r.wins).toString),
      "outcome" -> Str(r.outcome), "free" -> Bool(r.free), "debits" -> GoJson.strArr(r.debits), "credits" -> GoJson.strArr(r.credits),
      "cancels" -> GoJson.strArr(r.cancels), "settlement" -> Str(settlement)
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
