package io.eitheror.casino.gameprovider.api

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.gameprovider.{Json, Metrics}
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.api.Context.*
import io.eitheror.casino.gameprovider.api.callbacks.Answered
import io.eitheror.casino.gameprovider.catalogue.*
import io.eitheror.casino.gameprovider.config.ProviderConfiguration
import io.eitheror.casino.gameprovider.config.ProviderConfiguration.given
import io.eitheror.casino.gameprovider.players.{KnownPlayer, MakeKnown}
import io.eitheror.casino.gameprovider.provider.{BonusRequest, BonusStatus}
import io.eitheror.casino.gameprovider.provider.st8.{Callback, St8Protocol, St8Signature}
import io.eitheror.casino.gameprovider.rounds.*
import io.eitheror.casino.gameprovider.views.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The callbacks, at whichever prefix they arrive (FR-002, research R2): verified on the raw body before
 * anything is read (FR-003), a body that is not a JSON object refused 400 (FR-004), then handled.
 */
object CallbackRoute:
  def serve(ctx: Context, provider: String, callbackName: String, raw: String, signature: Option[String]): Respond[Bytes] =
    Callback.from(callbackName) match
      case None => refuse(404, "unknown_callback", "callback" -> callbackName)
      case Some(callback) =>
        if !St8Signature.verify(raw, signature, ctx.configs.verifyingKeys(provider)) then json(200, St8Protocol.status("auth_failed"))
        else
          Json.parseObject(raw) match
            case None => json(400, St8Protocol.status(St8Protocol.malformedWord(callback)))
            case Some(node) =>
              val Answered(status, body) = ctx.callbacks(provider).handle(callback, node, raw)
              json(status, body)

/**
 * `/v1/casino/brands/...`: the brand-segmented callbacks (public, signed), the brand's catalogue
 * configuration and operator catalogue (an operator holding `config.games`, spec 030 GP1), the free-rounds routes `rewards` calls (service),
 * a game's bonus types (`rewards` alone), and the brand's listed games (`rewards` and `public-api`, spec 016 G8).
 * One endpoint per prefix, since two may not share one; each route's audience is its own ACL (FR-027).
 */
final class BrandsEndpoint(ctx: Context) extends HttpEndpoint("/v1/casino/brands"):
  val acl: Acl = Acl.DenyAll
  private val brand = ctx.brand
  private val adapters = ctx.settings.adapters

  withAcl(Routes.public) {
    adapters.foreach { a =>
      postBody(s"/{brand}/${a.name}/{callback}") { (segment: String, callback: String, body: String) =>
        if segment != brand then refuse(404, Routes.Refusal.WrongBrand, "brand" -> brand)
        else CallbackRoute.serve(ctx, a.name, callback, body, request.header(St8Signature.Header))
      }
    }
  }

  withAcl(ctx.operatorMay("config.games")) {
    get("/{brand}/game-catalog-config") { (segment: String) =>
      ctx.ownBrand(segment)
      ok(CatalogueRoutes.configurationJson(brand, ctx.catalogue.configuration()))
    }

    putBody("/{brand}/game-catalog-config") { (segment: String, body: String) =>
      ctx.ownBrand(segment)
      val node = Json.parseObject(body).getOrElse(badRequest("the body is not a JSON object"))
      val overrides = Option(node.get("gameOverrides")).filter(_.isObject).map { o =>
        val fields = o.fieldNames()
        var m      = Map.empty[String, Boolean]
        while fields.hasNext do { val k = fields.next(); m += k -> o.get(k).asBoolean }
        m
      }.getOrElse(Map.empty)
      val configuration = CatalogueConfiguration(node.strings("disabledDevelopers").toSet, node.strings("disabledCategories").toSet, overrides)
      val games         = ctx.catalogue.games()
      val removed = overrides.keySet.filter { k =>
        games.exists(g => (Game.entityId(g.provider, g.code) == k || g.code == k) && g.removedFromFeed)
      }
      try
        val saved = ctx.components.forKeyValueEntity(EntityId(brand)).call(BrandCatalogueConfiguration.set)
          .invoke(SetCatalogueConfiguration(configuration, removed))
        ok(CatalogueRoutes.configurationJson(brand, saved))
      catch case e: com.thinkmorestupidless.ankka.core.CommandError =>
        refuse(400, "game_removed", "message" -> e.getMessage)
    }

    get("/{brand}/game-catalog") { (segment: String) =>
      ctx.ownBrand(segment)
      val provider = query.optional[String]("provider")
      val config   = ctx.catalogue.configuration()
      ok(Json.obj("games" -> Json.arr(ctx.catalogue.games().filter(g => provider.forall(_ == g.provider)).map(CatalogueReads.operatorJson(_, config))*)))
    }
  }

  withAcl(Routes.service()) {
    adapters.foreach { a =>
      val provider = a.name
      postBody(s"/{brand}/game-provider/$provider/players/{playerId}/known") { (segment: String, playerId: String, body: String) =>
        ctx.ownBrand(segment)
        val currency = Json.parseObject(body).flatMap(_.text("currency")).orElse(query.optional[String]("currency"))
        ctx.components.forKeyValueEntity(EntityId(playerId)).call(KnownPlayer.makeKnown).invoke(MakeKnown(provider, currency, ctx.now())): Unit
        noContent
      }

      postBody(s"/{brand}/game-provider/$provider/bonuses") { (segment: String, body: String) =>
        ctx.ownBrand(segment)
        val node = Json.parseObject(body).getOrElse(badRequest("the body is not a JSON object"))
        ctx.configs.get(provider) match
          case None => ok(Json.obj("status" -> BonusStatus.NotConfigured))
          case Some(configuration) =>
            val playerId = node.text("playerId").getOrElse(badRequest("playerId required"))
            val grantId  = node.text("grantId").getOrElse(badRequest("grantId required"))
            ctx.components.forKeyValueEntity(EntityId(playerId)).call(KnownPlayer.makeKnown)
              .invoke(MakeKnown(provider, node.text("currency"), ctx.now())): Unit
            val request = BonusRequest(grantId, playerId, node.strings("gameCodes"), node.long("count").map(_.toInt).getOrElse(0),
              node.textOr("spinValue", "0"), node.textOr("currency", "EUR"), node.long("durationSeconds").getOrElse(86400L), ctx.now().toString)
            a.createBonus(configuration, request) match
              case Right(status) => ok(Json.obj("status" -> status, "providerBonusId" -> grantId))
              case Left(_)       => ok(Json.obj("status" -> BonusStatus.Failed))
      }

      get(s"/{brand}/game-provider/$provider/bonuses/{grantId}") { (segment: String, grantId: String) =>
        ctx.ownBrand(segment)
        ctx.configs.get(provider) match
          case None                => ok(Json.obj("status" -> BonusStatus.NotConfigured))
          case Some(configuration) => ok(Json.obj("status" -> a.bonus(configuration, grantId).getOrElse(BonusStatus.Processing)))
      }

      postBody(s"/{brand}/game-provider/$provider/bonuses/{grantId}/cancel") { (segment: String, grantId: String, body: String) =>
        ctx.ownBrand(segment)
        val playerId = Json.parseObject(body).flatMap(_.text("playerId")).getOrElse(badRequest("playerId required"))
        ctx.configs.get(provider) match
          case None                => ok(Json.obj("status" -> BonusStatus.NotConfigured))
          case Some(configuration) => ok(Json.obj("status" -> a.cancelBonus(configuration, grantId, playerId).getOrElse(BonusStatus.Failed)))
      }

      get(s"/{brand}/game-provider/$provider/spin-values") { (segment: String) =>
        ctx.ownBrand(segment)
        val codes    = query.all[String]("gameCodes").flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty).toVector
        val currency = query.required[String]("currency")
        ctx.configs.get(provider) match
          case None => ok(Json.obj("status" -> BonusStatus.NotConfigured))
          case Some(configuration) =>
            SpinValues.cached(provider, codes, currency, CatalogueReads.at(ctx.now()))(a.spinValues(configuration, codes, currency)) match
              case Right(values) => ok(Json.obj("values" -> values))
              case Left(_)       => refuse(503, "provider_unavailable")
      }
    }
  }

  // A game's bonus types, for `rewards`' game check alone (spec 011 research R6, G1). `{provider}` is a
  // literal per adapter, as above, so the route keeps to two path parameters.
  withAcl(Routes.serviceOf("rewards")) {
    adapters.foreach { a =>
      val provider = a.name
      get(s"/{brand}/game-provider/$provider/games/{gameCode}") { (segment: String, gameCode: String) =>
        ctx.ownBrand(segment)
        ctx.catalogue.one(gameCode, Some(provider)) match
          case Right(Some(g)) => ok(Json.obj("gameCode" -> g.code, "bonusTypes" -> g.fields.get.bonusTypes))
          case _              => refuse(404, "game_not_found")
      }
    }
  }

  // The brand's available games with their details, for a sibling service (spec 016 G8, research R9):
  // the games the brand lists, as the player catalogue answers each, with `removedFromFeed` and
  // `releaseDate` so the caller applies the original's release gate itself.
  withAcl(Routes.serviceOf("rewards", "public-api")) {
    get("/{brand}/game-provider/games") { (segment: String) =>
      ctx.ownBrand(segment)
      val config = ctx.catalogue.configuration()
      val games  = ctx.catalogue.games().filter(g => Enablement.listed(g, config)).map { g =>
        val row = CatalogueReads.toJson(g)
        Json.set(row, "removedFromFeed", g.removedFromFeed)
        row
      }
      ok(Json.obj("games" -> Json.arr(games*)))
    }
  }

/** `/st8/{callback}`: the wallet URL with its path stripped to the provider's prefix (research R2). */
final class ProviderPathEndpoint(ctx: Context, provider: String) extends HttpEndpoint(s"/$provider"):
  val acl: Acl = Routes.public
  postBody("/{callback}") { (callback: String, body: String) =>
    CallbackRoute.serve(ctx, provider, callback, body, request.header(St8Signature.Header))
  }

/** `/{callback}` at the host's root, ST8 only: ST8 strips the whole path (FR-002). Literal routes, so nothing else is caught. */
final class RootCallbackEndpoint(ctx: Context, provider: String) extends HttpEndpoint("/"):
  val acl: Acl = Routes.public
  Callback.values.foreach { c =>
    postBody(s"/${c.wire}") { (body: String) => CallbackRoute.serve(ctx, provider, c.wire, body, request.header(St8Signature.Header)) }
  }

/** `/v1/casino/sessions/{brand}/launch` (FR-007 to FR-009): the original's path, a player route. */
final class SessionsEndpoint(ctx: Context) extends HttpEndpoint("/v1/casino/sessions"):
  val acl: Acl = Acl.DenyAll
  private val launch = Launch(ctx)

  withAcl(Routes.player(ctx.brand, ctx.settings.authenticate())) {
    postBody("/{brand}/launch") { (segment: String, body: String) =>
      ctx.ownBrand(segment)
      launch(body, Routes.playerId(principal))
    }
  }

/**
 * `/v1/casino/catalog/...` (FR-023): the frontend's catalogue reads, unchanged, plus each game's provider.
 * Public routes (FR-027, amended by spec 027 FR-056): a visitor with no sign-in reads the lobby, as the
 * original's site did; a `?brand=` or path naming another brand is still `404`.
 */
final class CatalogEndpoint(ctx: Context) extends HttpEndpoint("/v1/casino/catalog"):
  val acl: Acl = Acl.DenyAll

  private def brandParameter(): Unit =
    query.optional[String]("brand").foreach(b => if b != ctx.brand then throw HttpProblem.notFound(s"${Routes.Refusal.WrongBrand}: this is brand ${ctx.brand}"))

  withAcl(Routes.public) {
    get("/games") { () =>
      brandParameter()
      query.optional[String]("ids") match
        case Some(ids) => ok(Json.obj("games" -> Json.arr(ctx.catalogue.batch(ids.split(",").toVector).map(CatalogueReads.toJson)*)))
        case None =>
          val q = CatalogueQuery(query.optional[String]("category"), query.optional[String]("themes"), query.optional[String]("features"),
            query.optional[String]("bonusTypes"), query.optional[String]("tag"), query.optional[String]("developer"),
            query.optional[String]("search"), query.optional[String]("country"),
            query.raw("limit").flatMap(_.toIntOption), query.raw("offset").flatMap(_.toIntOption))
          val (page, paging) = ctx.catalogue.browse(q, CatalogueReads.at(ctx.now()))
          val body           = Json.obj("games" -> Json.arr(page.map(CatalogueReads.toJson)*))
          paging.foreach { (total, next) => Json.set(body, "total", total); Json.set(body, "nextOffset", next) }
          ok(body)
    }

    get("/games/{gameCode}") { (gameCode: String) =>
      brandParameter()
      ctx.catalogue.one(gameCode, query.optional[String]("provider")) match
        case Left(ambiguous) => refuse(400, "ambiguous_game_code", "gameId" -> gameCode, "providers" -> ambiguous.providers)
        case Right(None)     => refuse(404, "game_not_found")
        case Right(Some(g))  => ok(CatalogueReads.toJson(g))
    }

    get("/developers") { () =>
      brandParameter()
      ok(Json.obj("developers" -> Json.arr(ctx.catalogue.developers(query.optional[String]("country")).map((id, name) =>
        Json.obj("developerId" -> id, "developerName" -> name))*)))
    }

    get("/{brand}/games/popular") { (segment: String) =>
      ctx.ownBrand(segment)
      val limit = query.raw("limit").flatMap(_.toIntOption).filter(n => n >= 1 && n <= 100).getOrElse(20)
      ok(Json.obj("gameIds" -> ctx.catalogue.popular(limit).map(_.code)))
    }
  }

/**
 * `/v1/casino/backoffice/{brand}/game-provider/...` (FR-020, FR-021, FR-026, FR-029): an operator's
 * provider configuration, catalogue refresh, unacknowledged callbacks, rounds and metrics. Each route
 * requires one permission (spec 030 amendment GP1, `Routes.operatorMay` over the brand's roles):
 * `config.games` for the providers and the catalogue refresh, `callback.resolve` for the unacknowledged
 * calls and the metrics (whose gauge is their count), `player.read` for the rounds.
 */
final class BackofficeEndpoint(ctx: Context) extends HttpEndpoint("/v1/casino/backoffice"):
  val acl: Acl = Acl.DenyAll
  private val brand = ctx.brand

  private def knownProvider(provider: String): Unit =
    if ctx.settings.adapter(provider).isEmpty then
      throw HttpProblem.notFound(s"provider_unknown: $provider is not among the brand's casino-providers (${ctx.settings.adapters.map(_.name).mkString(", ")})")

  withAcl(ctx.operatorMay("config.games")) {
    get("/{brand}/game-provider/providers") { (segment: String) =>
      ctx.ownBrand(segment)
      ok(Json.obj("providers" -> Json.arr(ctx.configs.providers.flatMap(p => ctx.configs.get(p).map(c => Json.parse(writeToString(c.view(p))).get))*)))
    }

    get("/{brand}/game-provider/providers/{provider}") { (segment: String, provider: String) =>
      ctx.ownBrand(segment)
      ctx.configs.get(provider) match
        case None    => refuse(404, "provider_not_configured", "provider" -> provider)
        case Some(c) => ok(Json.parse(writeToString(c.view(provider))).get)
    }

    putBody("/{brand}/game-provider/providers/{provider}") { (segment: String, provider: String, body: String) =>
      ctx.ownBrand(segment)
      knownProvider(provider)
      val node = Json.parseObject(body).getOrElse(badRequest("the body is not a JSON object"))
      val configuration = ProviderConfiguration(
        apiUrl = node.textOr("apiUrl", ""), site = node.textOr("site", ""),
        operatorPrivateKey = node.text("operatorPrivateKey").flatMap(k => St8Signature.base64Der(k).toOption).getOrElse(""),
        providerPublicKey = node.text("providerPublicKey").flatMap(k => St8Signature.base64Der(k).toOption),
        providerKeyDocumentUrl = node.text("providerKeyDocumentUrl"), jurisdiction = node.textOr("jurisdiction", ""),
        lobbyUrl = node.textOr("lobbyUrl", ""), depositUrl = node.textOr("depositUrl", ""), walletUrl = node.text("walletUrl")
      )
      configuration.problem(ctx.settings.realMoney) match
        case Some(problem) if problem.startsWith("real_money_brand") => refuse(400, "real_money_brand", "message" -> problem)
        case Some(problem)                                          => refuse(400, "invalid_configuration", "message" -> problem)
        case None =>
          ctx.configs.put(provider, configuration)
          RefreshRuntime.scheduleNightly(provider)
          noContent
    }

    post("/{brand}/game-provider/providers/{provider}/catalogue/refresh") { (segment: String, provider: String) =>
      ctx.ownBrand(segment)
      knownProvider(provider)
      if ctx.configs.get(provider).isEmpty then refuse(404, "provider_not_configured", "provider" -> provider)
      else
        ctx.components.forKeyValueEntity(EntityId(provider)).call(RefreshStatus.set)
          .invoke(RefreshState(provider, "running", Some(ctx.now()), None, None, None, None, 0)): Unit
        RefreshRuntime.refreshNow(provider)
        json(202, Json.obj("status" -> "running"))
    }

    get("/{brand}/game-provider/providers/{provider}/catalogue/refresh") { (segment: String, provider: String) =>
      ctx.ownBrand(segment)
      val s = ctx.components.forKeyValueEntity(EntityId(provider)).call(RefreshStatus.get).invoke()
      ok(Json.parse(writeToString(s)(using Codecs.make[RefreshState])).get)
    }
  }

  withAcl(ctx.operatorMay("callback.resolve")) {
    get("/{brand}/game-provider/unacknowledged") { (segment: String) =>
      ctx.ownBrand(segment)
      Paging.from(query.raw("page"), query.raw("pageSize")) match
        case Left(parameter) => refuse(400, "INVALID_REQUEST", "field" -> parameter, "message" -> s"$parameter is not a valid value")
        case Right(paging) =>
          // Oldest first (a review queue, spec 030 FR-009). An entry is one of a round row's nested entries, which
          // a view query cannot page, so the page is cut in memory from the open entries of at most 10,000 rounds.
          val open = Unacknowledgements.open(ctx)
          val entries = paging.fold(open)(_.of(open)).map { (row, e) =>
            Json.obj("transactionId" -> e.transactionId, "callback" -> e.callback, "kind" -> e.kind, "amount" -> e.amount, "currency" -> row.currency,
              "provider" -> row.provider, "roundId" -> row.roundId, "playerId" -> row.playerId, "error" -> e.error, "recordedAt" -> e.recordedAt.toString)
          }
          ok(Json.obj("entries" -> Json.arr(entries*), "total" -> open.size.toLong, "page" -> paging.map(_.page),
            "pageSize" -> paging.map(_.pageSize)))
    }

    post("/{brand}/game-provider/unacknowledged/{transactionId}/replay") { (segment: String, transactionId: String) =>
      ctx.ownBrand(segment)
      Unacknowledgements.find(ctx, transactionId) match
        case None => refuse(404, "not_unacknowledged", "transactionId" -> transactionId)
        case Some((row, _)) =>
          // The replay resumes the transaction's own workflow under the same id (FR-020, research R18).
          val workflow = ctx.components.forWorkflow(BetWorkflow.id(row.provider, transactionId))
          workflow.call(BetWorkflow.retry).invoke(Cleared(Cleared.OperatorReplay, None, Some(principal.subject), ctx.now())): Unit
          callbacks.Callbacks.await(ctx.components, row.provider, transactionId) match
            case Some(a) =>
              val currency = a.currency.getOrElse("EUR")
              json(200, St8Protocol.money(a.status,
                a.balance.map(b => io.eitheror.casino.gameprovider.money.Amounts.render(b, io.eitheror.casino.contract.money.Currency.unsafe(currency))), a.currency))
            case None =>
              refuse(409, "still_unacknowledged", "message" -> workflow.call(BetWorkflow.status).invoke().error.getOrElse("error"))
    }

    postBody("/{brand}/game-provider/unacknowledged/{transactionId}/resolve") { (segment: String, transactionId: String, body: String) =>
      ctx.ownBrand(segment)
      val reason = Json.parseObject(body).flatMap(_.text("reason")).getOrElse(badRequest("reason required"))
      Unacknowledgements.find(ctx, transactionId) match
        case None => refuse(404, "not_unacknowledged", "transactionId" -> transactionId)
        case Some((row, _)) =>
          val how     = Cleared(Cleared.OperatorResolve, Some(reason), Some(principal.subject), ctx.now())
          val cleared = ctx.components.forEventSourcedEntity(EntityId(Round.entityId(row.provider, row.roundId))).call(Round.clear)
            .invoke(ClearUnacknowledged(transactionId, how))
          // The transaction's workflow ends too, so a late provider retry is not resumed.
          ctx.components.forWorkflow(BetWorkflow.id(row.provider, transactionId)).call(BetWorkflow.resolve).invoke(how): Unit
          if cleared then noContent else refuse(404, "not_unacknowledged", "transactionId" -> transactionId)
    }

    get("/{brand}/game-provider/metrics") { (segment: String) =>
      ctx.ownBrand(segment)
      Respond(Bytes("text/plain; version=0.0.4", Metrics.render(brand, Unacknowledgements.openCount(ctx)).getBytes(UTF_8)), 200)
    }
  }

  withAcl(ctx.operatorMay("player.read")) {
    // A player's or a game session's rounds, newest first, with their `total` (spec 030 GP2). `page` and
    // `pageSize` page them as every operator list is paged (spec 030 L); without either, `offset` and
    // `limit` (100 by default, at most 1,000) as before 030.
    get("/{brand}/game-provider/rounds") { (segment: String) =>
      ctx.ownBrand(segment)
      val (view, condition) = (query.optional[String]("playerId"), query.optional[String]("sessionId")) match
        case (Some(p), _) => (ctx.views.forView(RoundsByPlayer), jsonText("playerId") ++ sql" = $p")
        case (_, Some(s)) => (ctx.views.forView(RoundsBySession), jsonText("sessionId") ++ sql" = $s")
        case _            => badRequest("playerId or sessionId required")
      Paging.from(query.raw("page"), query.raw("pageSize")) match
        case Left(parameter) => refuse(400, "INVALID_REQUEST", "field" -> parameter, "message" -> s"$parameter is not a valid value")
        case Right(paging) =>
          val (skip, take) = paging.fold((query.optional[Int]("offset").getOrElse(0).max(0).toLong,
            query.optional[Int]("limit").getOrElse(100).max(1).min(1000)))(p => ((p.page.toLong - 1) * p.pageSize, p.pageSize))
          val rows = if skip + take > Int.MaxValue then Vector.empty
                     else view.ordered(condition, RoundReads.Newest, (skip + take).toInt).drop(skip.toInt)
          ok(Json.obj("rounds" -> Json.arr(rows.map(r => Json.parse(writeToString(r)(using Codecs.make[RoundRow])).get)*),
            "total" -> view.count(condition), "page" -> paging.map(_.page), "pageSize" -> paging.map(_.pageSize)))
    }

    // A round by its id at whichever of the brand's providers holds it (spec 030 GP2). These literal
    // segments are matched before a `rounds/<adapter>/{roundId}` (ankka sorts a literal before a
    // parameter); an adapter named `by-round` or `by-transaction` would declare the same route twice,
    // which ankka refuses at start.
    get("/{brand}/game-provider/rounds/by-round/{roundId}") { (segment: String, roundId: String) =>
      ctx.ownBrand(segment)
      RoundReads.byRound(ctx, roundId).fold(refuse(404, "round_not_found", "roundId" -> roundId))(s => ok(RoundReads.json(s)))
    }

    // The round holding a transaction (a bet, a win, a cancel, a buyin or a payout): the transaction's own
    // workflow names its round, as a cancel finds the transaction it references (spec 030 GP2).
    get("/{brand}/game-provider/rounds/by-transaction/{transactionId}") { (segment: String, transactionId: String) =>
      ctx.ownBrand(segment)
      RoundReads.byTransaction(ctx, transactionId)
        .fold(refuse(404, "round_not_found", "transactionId" -> transactionId))(s => ok(RoundReads.json(s)))
    }

    ctx.settings.adapters.foreach { a =>
      get(s"/{brand}/game-provider/rounds/${a.name}/{roundId}") { (segment: String, roundId: String) =>
        ctx.ownBrand(segment)
        RoundReads.at(ctx, Round.entityId(a.name, roundId)).fold(refuse(404, "round_not_found"))(s => ok(RoundReads.json(s)))
      }
    }
  }

/** The operator's reads of one round (FR-021, spec 030 GP2), from the round's entity. */
object RoundReads:
  /** Newest opened first, the row's key breaking a tie. */
  val Newest: com.thinkmorestupidless.ankka.runtime.SqlFragment =
    com.thinkmorestupidless.ankka.runtime.SqlFragment.raw("(payload::jsonb->>'openedAt')::timestamptz DESC NULLS LAST, row_key")

  def json(state: RoundState) = Json.parse(writeToString(state)(using Codecs.make[RoundState])).get

  /** The round under its entity id, none before it opened. */
  def at(ctx: Context, entityId: String): Option[RoundState] =
    Some(ctx.components.forEventSourcedEntity(EntityId(entityId)).call(Round.get).invoke()).filterNot(_.isNew)

  /** The round with this id at the first of the brand's providers (in `casino-providers` order) that holds one. */
  def byRound(ctx: Context, roundId: String): Option[RoundState] =
    ctx.settings.adapters.iterator.flatMap(a => at(ctx, Round.entityId(a.name, roundId))).nextOption()

  /** The round that recorded this transaction, at the first of the brand's providers whose workflow for it names one. */
  def byTransaction(ctx: Context, transactionId: String): Option[RoundState] =
    ctx.settings.adapters.iterator.flatMap { a =>
      ctx.components.forWorkflow(BetWorkflow.id(a.name, transactionId)).call(BetWorkflow.status).invoke().job
        .flatMap(job => at(ctx, job.roundEntity)).filter(_.transaction(transactionId).isDefined)
    }.nextOption()

/** The open unacknowledged callbacks, from the view (FR-020). */
object Unacknowledgements:
  def rows(ctx: Context): Vector[UnacknowledgedRow] =
    ctx.views.forView(Unacknowledged).where(jsonNumber("openCount") ++ sql" > ${0}", 10_000)

  def open(ctx: Context): Vector[(UnacknowledgedRow, UnacknowledgedEntry)] =
    rows(ctx).flatMap(r => r.entries.filter(_.open).map(r -> _)).sortBy(_._2.recordedAt)

  def find(ctx: Context, transactionId: String): Option[(UnacknowledgedRow, UnacknowledgedEntry)] =
    open(ctx).find(_._2.transactionId == transactionId)

  def openCount(ctx: Context): Long = rows(ctx).map(_.openCount.toLong).sum

/** The operator's catalogue configuration answer, the original's shape. */
object CatalogueRoutes:
  def configurationJson(brand: String, c: CatalogueConfiguration) =
    val overrides = Json.obj()
    c.gameOverrides.toVector.sortBy(_._1).foreach((k, v) => Json.set(overrides, k, v))
    Json.obj("brandId" -> brand, "disabledDevelopers" -> c.disabledDevelopers.toVector.sorted,
      "disabledCategories" -> c.disabledCategories.toVector.sorted, "gameOverrides" -> overrides)

/** Spin values per provider, games and currency, kept for the day (FR-025, the feature's "cached for the day"). */
object SpinValues:
  private val cache = ConcurrentHashMap[(String, Vector[String], String, LocalDate), Vector[String]]()

  def cached(provider: String, codes: Vector[String], currency: String, day: LocalDate)(fetch: => Either[String, Vector[String]]): Either[String, Vector[String]] =
    val key = (provider, codes.sorted, currency, day)
    Option(cache.get(key)) match
      case Some(values) => Right(values)
      case None =>
        val fetched = fetch
        fetched.foreach(values => cache.put(key, values))
        fetched

  def clear(): Unit = cache.clear()
