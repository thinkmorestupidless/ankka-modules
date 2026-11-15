package io.eitheror.casino.rg.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.http.openapi.Description
import io.eitheror.casino.contract.http.Json as JsonBody
import io.eitheror.casino.contract.access.{RoleBags, Routes}
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.profile.*
import io.eitheror.casino.rg.settings.{BrandSettingsEntity, ChangeSettings}
import io.eitheror.casino.rg.usage.{DayAt, SessionDayEntity}

import scala.util.Try

import Answers.*

/*
 * The three endpoints of contracts/routes.md, one prefix each with the brand's slug as a literal (R14).
 */

/** `/v1/casino/rg/<slug>`: the player's own profile; the `{player}` segment must be the token's player. */
final class PlayerEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/rg/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private val profileRoutes = ProfileRoutes(ctx)

  private def mine(playerId: String)(f: => Reply): Reply =
    if !Routes.playerId(principal).contains(playerId) then refusal(403, "player_id_mismatch", "Authenticated player does not match requested player ID")
    else f

  // Spec 031: each route described for the player SDK (contracts/routes.md's player table); the
  // refusal shape is `Answers.refusal`'s, and `mine`'s 403 is every route's.
  override protected def description: Description = Described.refusals

  described(_.summary("The signed-in player's protection: limits, staged changes, sums used, break and reality check")
    .answers[Described.Protection](200).refuses(403, Described.mine).refuses(503, "service_unavailable")
    .query[String]("currency").tag("player")) {
    get("/{player}/limits") { (p: String) => mine(p)(profileRoutes.status(p, query.raw("currency"))) }
  }
  described(_.summary("Set one of the signed-in player's limits; a loosening is staged")
    .body[Described.SetLimit].answers[Described.Protection](200).refuses(403, Described.mine)
    .refuses(400, "invalid_body, unknown_limit_type, value_required, invalid_value, below_minimum_session_minutes, invalid_currency")
    .tag("player")) {
    putBody("/{player}/limits/{type}") { (p: String, t: String, body: JsonBody) => mine(p)(profileRoutes.setLimit(p, t, body.text, Actor.Player)) }
  }
  described(_.summary("Remove one of the signed-in player's limits; the removal is staged")
    .answers[Described.Protection](200).refuses(403, Described.mine).refuses(400, "unknown_limit_type, invalid_currency")
    .query[String]("currency").tag("player")) {
    delete("/{player}/limits/{type}") { (p: String, t: String) => mine(p)(profileRoutes.removeLimit(p, t, query.raw("currency"), Actor.Player)) }
  }
  described(_.summary("Take a break until an instant")
    .body[Described.SetBreak].answers[Described.Protection](200).refuses(403, Described.mine)
    .refuses(400, "invalid_body, until_required, invalid_until, break_too_short, break_too_long").refuses(409, "break_active")
    .tag("player")) {
    putBody("/{player}/cooling-off") { (p: String, body: JsonBody) => mine(p)(profileRoutes.setBreak(p, body.text, Actor.Player)) }
  }
  described(_.summary("Exclude the signed-in player permanently")
    .answers[Described.Excluded](200).refuses(403, Described.mine).refuses(409, "already_excluded").tag("player")) {
    put("/{player}/permanent-exclusion") { (p: String) => mine(p)(profileRoutes.exclude(p, "permanent", Actor.Player)) }
  }
  described(_.summary("Ask to return from an ended self-exclusion; the player is reinstated after the cooling period")
    .answers[Described.ReturnRequested](202).refuses(403, Described.mine)
    .refuses(409, "not_excluded, exclusion_permanent, exclusion_not_ended, return_requested").tag("player")) {
    post("/{player}/return") { (p: String) => mine(p)(profileRoutes.requestReturn(p, Actor.Player)) }
  }
  described(_.summary("The signed-in player's protection history, newest first")
    .answers[Vector[Described.HistoryEntry]](200).refuses(403, Described.mine).refuses(503, "service_unavailable").tag("player")) {
    get("/{player}/history") { (p: String) => mine(p)(profileRoutes.history(p)) }
  }
  described(_.summary("Set the signed-in player's reality-check interval: 15, 30, 45 or 60 minutes")
    .body[Described.SetRealityCheck].answers[Described.Protection](200).refuses(403, Described.mine)
    .refuses(400, "invalid_body, interval_required, invalid_interval").tag("player")) {
    putBody("/{player}/reality-check") { (p: String, body: JsonBody) => mine(p)(profileRoutes.setRealityCheck(p, body.text)) }
  }
  described(_.summary("Turn off the signed-in player's reality checks")
    .answers[Described.Protection](200).refuses(403, Described.mine).tag("player")) {
    delete("/{player}/reality-check") { (p: String) => mine(p)(profileRoutes.removeRealityCheck(p)) }
  }
  described(_.summary("Acknowledge a reality check in one of the player's game sessions of today or yesterday")
    .body[Described.Acknowledge].answers[Described.Acknowledged](200).refuses(403, Described.mine)
    .refuses(400, "invalid_body").refuses(404, "session_not_found").tag("player")) {
    postBody("/{player}/reality-check/acknowledge") { (p: String, body: JsonBody) => mine(p)(profileRoutes.acknowledge(p, body.text)) }
  }

/**
 * `/v1/casino/backoffice/<slug>/rg`: the brand's operators (FR-004, FR-016, FR-019, FR-025, FR-026, FR-029),
 * each route requiring one permission of the brand's roles as `bags` last read them (spec 030 amendment G1,
 * `Routes.operatorMay`): `protection.read` for the reads, `protection.change` for limits, breaks,
 * self-exclusion, the return and cases, `config.brand` for changing the brand's protection settings.
 */
final class OperatorEndpoint(ctx: Ctx, bags: RoleBags) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}/rg"):
  private def may(permission: String): Acl = Routes.operatorMay(ctx.slug, permission, bags, ctx.authenticate())

  // every route names its own permission; this is never reached
  val acl: Acl = Acl.DenyAll

  private val profileRoutes = ProfileRoutes(ctx)
  private def operator = Actor.operator(principal.subject)
  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  private def settingsBody(s: io.eitheror.casino.rg.settings.BrandSettings) =
    Json.obj("minSessionMinutes" -> s.minSessionMinutes, "maxBreakDays" -> s.maxBreakDays)

  withAcl(may("protection.read")) {
    get("/settings") { () => ok(settingsBody(ctx.brandSettings)) }
  }
  withAcl(may("config.brand")) {
    putBody("/settings") { (text: String) =>
      body(text) { b =>
        def int(f: String): Either[String, Option[Int]] =
          if b.text(f).isEmpty then Right(None) else b.long(f).filter(_.isValidInt).map(v => Some(v.toInt)).toRight(f)
        (for m <- int("minSessionMinutes"); d <- int("maxBreakDays") yield ChangeSettings(m, d, principal.subject, ctx.now())) match
          case Left(field) => refusal(400, "invalid_setting", s"$field is not a whole number", "field" -> field)
          case Right(c) =>
            val a = ctx.clients.componentClient.forEventSourcedEntity(EntityId(ctx.slug)).call(BrandSettingsEntity.change).invoke(c)
            a.refusal.fold(ok(settingsBody(a.settings)))(code => refusal(400, code, s"${a.field.getOrElse("")} is out of range", "field" -> a.field))
      }
    }
  }

  withAcl(may("protection.read")) {
    get("/{player}") { (p: String) =>
      Try(ctx.state(p)).fold(_ => refusal(503, "service_unavailable", "The profile cannot be read"),
        s => ok(Render.full(ctx, s.copy(playerId = p), query.raw("currency"))))
    }
  }

  private def caseReply(status: Int)(a: ProfileAnswer): Reply =
    a.refusal.fold(a.caseId.flatMap(a.state.caseOf).fold(refusal(404, "case_not_found", "No such case"))(c => json(status, Render.rgCase(c))))(refused)

  withAcl(may("protection.change")) {
    putBody("/{player}/limits/{type}") { (p: String, t: String, text: String) => profileRoutes.setLimit(p, t, text, operator) }
    delete("/{player}/limits/{type}") { (p: String, t: String) => profileRoutes.removeLimit(p, t, query.raw("currency"), operator) }
    putBody("/{player}/cooling-off") { (p: String, text: String) => profileRoutes.setBreak(p, text, operator) }
    postBody("/{player}/cooling-off/lift") { (p: String, text: String) => profileRoutes.liftBreak(p, text, principal.subject) }
    postBody("/{player}/self-exclude") { (p: String, text: String) =>
      body(text)(b => profileRoutes.exclude(p, b.textOr("period", ""), operator))
    }
    post("/{player}/return") { (p: String) => profileRoutes.requestReturn(p, operator) }

    postBody("/{player}/cases") { (p: String, text: String) =>
      body(text)(b => caseReply(201)(ctx.profile(p).call(ProfileEntity.openCase)
        .invoke(OpenCase(p, b.textOr("caseType", ""), b.textOr("note", ""), principal.subject, ctx.now()))))
    }
    postBody("/{player}/cases/{caseId}/notes") { (p: String, c: String, text: String) =>
      body(text)(b => caseReply(200)(ctx.profile(p).call(ProfileEntity.noteCase).invoke(NoteCase(c, b.textOr("note", ""), principal.subject, ctx.now()))))
    }
    postBody("/{player}/cases/{caseId}/close") { (p: String, c: String, text: String) =>
      body(text)(b => caseReply(200)(ctx.profile(p).call(ProfileEntity.closeCase).invoke(CloseCase(c, b.textOr("reason", ""), principal.subject, ctx.now()))))
    }
  }

/**
 * `/v1/casino/brands/<slug>/rg`: the checks the brand's services ask (FR-007 to FR-011), the GAMSTOP
 * report (FR-022), and erasure and export (FR-032). Every check is one profile read and the request's
 * figures, no call out; an unreadable request is `400`, which every caller takes as a refusal.
 */
final class ServiceEndpoint(ctx: Ctx, bags: RoleBags) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/rg"):
  val acl: Acl = Routes.service()

  // spec 030 G4 (F): the views' freshness, for an operator who may read protection, or for the project's services
  withAcl(Acl.anyOf(Routes.operatorMay(ctx.slug, "protection.read", bags, ctx.authenticate()), Routes.service())) {
    get("/status") { () =>
      io.eitheror.casino.rg.freshness.Freshness.answer(io.eitheror.casino.rg.freshness.Freshness.of(ctx.clients.componentClient))
    }
  }

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  private def check(a: CheckAnswer): Reply =
    ok(Json.obj("decision" -> a.decision, "endsAt" -> a.endsAt.map(_.toString), "period" -> a.period, "limit" -> a.limit,
      "used" -> a.used, "remaining" -> a.remaining))

  private def figures(b: JsonNode, names: (String, String, String)): Option[Sums] =
    for x <- b.long(names._1); y <- b.long(names._2); z <- b.long(names._3) yield Sums(x, y, z)

  withAcl(Routes.serviceOf("wallet", "players")) {
    postBody("/may-deposit") { (text: String) =>
      body(text) { b =>
        (b.text("playerId"), b.text("currency"), b.long("amount"), figures(b, ("last24h", "last7d", "last30d"))) match
          case (Some(p), Some(c), Some(amount), Some(sums)) => check(Checks.mayDeposit(ctx.state(p), ctx.now(), c, amount, sums))
          case _ => refusal(400, "invalid_body", "playerId, currency, amount, last24h, last7d and last30d are required")
      }
    }
  }
  // exchange asks it with an order's cost (spec 023 FR-002).
  withAcl(Routes.serviceOf("game-provider", "wallet", "exchange")) {
    postBody("/may-bet") { (text: String) =>
      body(text) { b =>
        (b.text("playerId"), b.text("currency"), b.long("amount")) match
          case (Some(p), Some(c), Some(amount)) =>
            check(Checks.mayBet(ctx.state(p), ctx.now(), c, amount, figures(b, ("lossLast24h", "lossLast7d", "lossLast30d"))))
          case _ => refusal(400, "invalid_body", "playerId, currency and amount are required")
      }
    }
  }
  withAcl(Routes.serviceOf("game-provider")) {
    get("/{player}/may-play") { (p: String) =>
      val now  = ctx.now()
      val used = ctx.clients.componentClient.forKeyValueEntity(EntityId(SessionDayEntity.id(p, now))).call(SessionDayEntity.get)
        .invoke(DayAt(now)).minutesUsed
      val a = Checks.mayPlay(ctx.state(p), now, used)
      json(if a.allowed then 200 else 403, Json.obj("allowed" -> a.allowed, "reason" -> a.reason,
        "coolingOffUntil" -> a.coolingOffUntil.map(_.toString), "limitMinutes" -> a.limitMinutes, "usedMinutes" -> a.usedMinutes,
        "sessionLimitMinutes" -> a.sessionLimitMinutes, "minutesUsed" -> a.minutesUsed))
    }
  }
  withAcl(Routes.serviceOf("players")) {
    postBody("/may-log-in") { (text: String) =>
      body(text)(b => b.text("playerId").fold(refusal(400, "invalid_body", "playerId is required"))(p =>
        check(Checks.mayLogIn(ctx.state(p), ctx.now()))))
    }
    postBody("/gamstop-refusal") { (text: String) =>
      body(text) { b =>
        (b.text("playerId"), b.text("result")) match
          case (Some(p), Some(r)) if Set("excluded", "api_error", "timeout")(r) =>
            ctx.profile(p).call(ProfileEntity.recordThirdParty).invoke(RecordThirdParty(p, r, ctx.now())): Unit
            noContent
          case _ => refusal(400, "invalid_body", "playerId and a result of excluded, api_error or timeout are required")
      }
    }
    post("/erasure/players/{player}") { (p: String) =>
      val at = ctx.now()
      ctx.profile(p).call(ProfileEntity.erase).invoke(Erase(at)): Unit
      ok(Json.obj("service" -> "responsible-gambling", "playerId" -> p, "erasedAt" -> at.toString,
        "erased" -> Json.arr("caseNotes", "closeNotes", "breakReasons"), "kept" -> Json.arr("limits", "cases", "history")))
    }
    get("/exports/players/{player}") { (p: String) =>
      val s = ctx.state(p).copy(playerId = p)
      ok(Json.obj("service" -> "responsible-gambling", "playerId" -> p, "records" -> Render.full(ctx, s, None)))
    }
  }
