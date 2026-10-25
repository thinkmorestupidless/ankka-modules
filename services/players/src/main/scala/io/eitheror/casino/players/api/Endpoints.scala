package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.players.clients.Geo

import Answers.notYet

/*
 * The seven endpoints of contracts/routes.md, one prefix each with the brand's slug as a literal (R14).
 * Every route is declared here and answers `501` until its story fills it; the geo lookup is whole.
 */

/** `/v1/casino/players/<slug>`: the player's own routes, and the public ones before a token exists. */
final class PlayerEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/players/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private val registration = RegistrationRoutes(ctx)
  private val sessions     = SessionRoutes(ctx)

  withAcl(Routes.public) {
    postBody("/register") { (text: String) => registration.register(text, request.header) }
    postBody("/login") { (text: String) => sessions.login(text, request.header) }
    postBody("/refresh") { (text: String) => sessions.refresh(text) }
    postBody("/verify-email") { (text: String) => registration.verifyEmail(text) }
    postBody("/resend-verification-email") { (text: String) => registration.resend(text) }
    post("/password-reset/request") { () => notYet("password-reset/request") }
    post("/password-reset/confirm") { () => notYet("password-reset/confirm") }
    post("/password-change/verify") { () => notYet("password-change/verify") }
    post("/emails/verify") { () => notYet("emails/verify") }
    post("/email-change/verify") { () => notYet("email-change/verify") }
  }

  post("/password-change") { () => notYet("password-change") }
  post("/{playerId}/logout") { (playerId: String) => sessions.logout(principal, playerId) }
  get("/{playerId}") { (_: String) => notYet("profile") }
  patch("/{playerId}") { (_: String) => notYet("profile edit") }
  post("/{playerId}/close") { (_: String) => notYet("close") }
  get("/{playerId}/consent") { (_: String) => notYet("consent") }
  patch("/{playerId}/consent") { (_: String) => notYet("consent edit") }
  patch("/{playerId}/cookie-consent") { (_: String) => notYet("cookie-consent") }
  get("/{playerId}/favourites") { (_: String) => notYet("favourites") }
  post("/{playerId}/favourites/{gameId}") { (_: String, _: String) => notYet("favourite add") }
  delete("/{playerId}/favourites/{gameId}") { (_: String, _: String) => notYet("favourite remove") }
  get("/{playerId}/recently-played") { (_: String) => notYet("recently-played") }
  get("/{playerId}/activity-history") { (_: String) => notYet("activity-history") }
  get("/{playerId}/messages") { (_: String) => notYet("messages") }
  post("/messages/{messageId}/read") { (_: String) => notYet("message read") }
  post("/messages/{messageId}/delete") { (_: String) => notYet("message delete") }
  get("/{playerId}/emails") { (_: String) => notYet("emails") }
  post("/{playerId}/emails") { (_: String) => notYet("email add") }
  post("/emails/{address}/resend") { (_: String) => notYet("email resend") }
  post("/emails/{address}/promote") { (_: String) => notYet("email promote") }
  delete("/emails/{address}") { (_: String) => notYet("email remove") }
  post("/{playerId}/email-change") { (_: String) => notYet("email-change") }
  post("/{playerId}/deposits") { (_: String) => notYet("deposits") }
  post("/{playerId}/erasure") { (_: String) => notYet("erasure") }
  post("/{playerId}/exports") { (_: String) => notYet("exports") }
  get("/{playerId}/exports/{exportId}") { (_: String, _: String) => notYet("export read") }

/** `/v1/casino/sessions/<slug>`: the player's sessions. */
final class SessionEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/sessions/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  get("/players/{playerId}") { (_: String) => notYet("sessions by player") }
  get("/{sessionId}") { (_: String) => notYet("session") }
  patch("/{sessionId}/end") { (_: String) => notYet("session end") }

/** `/v1/casino/backoffice/<slug>`: the brand's operators. */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private val P = "/players/{playerId}"

  get("/players") { () => notYet("players") }
  get(s"$P/profile") { (_: String) => notYet("operator profile") }
  patch(s"$P/profile") { (_: String) => notYet("operator profile edit") }
  patch(s"$P/date-of-birth") { (_: String) => notYet("date-of-birth") }
  get(s"$P/wallet") { (_: String) => notYet("wallet summary") }
  get(s"$P/activity") { (_: String) => notYet("activity") }
  get(s"$P/bets") { (_: String) => notYet("bets") }
  get(s"$P/sessions/{sessionId}/bets") { (_: String, _: String) => notYet("session bets") }
  get(s"$P/sessions") { (_: String) => notYet("operator sessions") }
  get(s"$P/crm") { (_: String) => notYet("crm") }
  patch(s"$P/crm") { (_: String) => notYet("crm edit") }
  patch(s"$P/suspend") { (_: String) => notYet("suspend") }
  patch(s"$P/reinstate") { (_: String) => notYet("reinstate") }
  post(s"$P/close") { (_: String) => notYet("operator close") }
  post(s"$P/reopen") { (_: String) => notYet("reopen") }
  put(s"$P/vip-tier") { (_: String) => notYet("vip-tier") }
  put(s"$P/pot-vip") { (_: String) => notYet("pot-vip") }
  post(s"$P/reset-password") { (_: String) => notYet("reset-password") }
  post(s"$P/sessions/{sessionId}/force-end") { (_: String, _: String) => notYet("force-end") }
  post(s"$P/sessions/force-end-all") { (_: String) => notYet("force-end-all") }
  post(s"$P/notes") { (_: String) => notYet("note") }
  patch(s"$P/notes/{noteId}/pin") { (_: String, _: String) => notYet("note pin") }
  patch(s"$P/notes/{noteId}/unpin") { (_: String, _: String) => notYet("note unpin") }
  get(s"$P/messages") { (_: String) => notYet("operator messages") }
  post(s"$P/messages") { (_: String) => notYet("operator message") }
  get("/merge-tags") { () => notYet("merge-tags") }
  post("/messages/preview") { () => notYet("message preview") }
  post(s"$P/clear-suspect-flag") { (_: String) => notYet("clear-suspect-flag") }
  get("/fraud-cases") { () => notYet("fraud-cases") }
  patch("/fraud-cases/{caseId}/review") { (_: String) => notYet("fraud review") }
  patch("/fraud-cases/{caseId}/dismiss") { (_: String) => notYet("fraud dismiss") }
  post(s"$P/activity-export") { (_: String) => notYet("activity-export") }
  get("/exports/{exportId}") { (_: String) => notYet("operator export read") }
  post(s"$P/erasure") { (_: String) => notYet("operator erasure") }
  get(s"$P/erasure") { (_: String) => notYet("erasure state") }
  get(s"$P/verification-link") { (playerId: String) => RegistrationRoutes(ctx).verificationLink(playerId) }
  get("/brand-config") { () => notYet("brand-config") }
  put("/brand-config") { () => notYet("brand-config set") }
  get("/currency-config") { () => notYet("currency-config") }
  put("/currency-config") { () => notYet("currency-config set") }
  get("/allow-list") { () => notYet("allow-list") }
  put("/allow-list") { () => notYet("allow-list set") }

/** `/v1/casino/brands/<slug>/modules`: spec 021's operator modules page, through the vendor's agent. */
final class ModulesEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/modules"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  get("/") { () => notYet("modules") }
  post("/requests") { () => notYet("modules request") }

/** `/v1/casino/brands/<slug>/players`: the project's services, by certificate. */
final class ServiceEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/players"):
  val acl: Acl = Routes.service()

  get("/{playerId}/status") { (_: String) => notYet("status") }
  post("/{playerId}/game-sessions") { (_: String) => notYet("game session open") }
  get("/{playerId}/game-sessions") { (_: String) => notYet("game sessions") }
  post("/game-sessions/{sessionId}/end") { (_: String) => notYet("game session end") }
  post("/{playerId}/suspend") { (_: String) => notYet("kyc suspend") }
  put("/{playerId}/exclusion") { (_: String) => notYet("exclusion") }
  delete("/{playerId}/exclusion") { (_: String) => notYet("exclusion lift") }
  post("/{playerId}/messages") { (_: String) => notYet("crm message") }
  get("/config/{name}") { (_: String) => notYet("config") }
  post("/erasure/players/{playerId}") { (_: String) => notYet("own erasure") }
  get("/exports/players/{playerId}") { (_: String) => notYet("own export part") }

/** `/internal/realm/<slug>`: the realm's listener and authenticators, each route checking its secret header. */
final class RealmEndpoint(ctx: Ctx) extends HttpEndpoint(s"/internal/realm/${ctx.slug}"):
  val acl: Acl = Routes.public

  private val realm = RealmRoutes(ctx)

  postBody("/webhook") { (text: String) => realm.webhook(text, request.header) }
  postBody("/may-sign-in") { (text: String) => realm.maySignIn(text, request.header) }
  postBody("/gamstop-refusal") { (text: String) => realm.gamstopRefusal(text, request.header) }

/** `/v1/casino/geo`: the public lookup (FR-006), `null`s when it fails. */
final class GeoEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/casino/geo"):
  val acl: Acl = Routes.public

  get("/lookup") { () =>
    val r      = request
    val answer = ctx.neighbours.geo.lookup(Geo.callerAddress(r.header))
    val body   = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
    body.put("country", answer.country.orNull)
    body.put("dialCode", answer.dialCode.orNull)
    Answers.ok(body)
  }
