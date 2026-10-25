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
  private val profile      = ProfileRoutes(ctx)

  withAcl(Routes.public) {
    postBody("/register") { (text: String) => registration.register(text, request.header) }
    postBody("/login") { (text: String) => sessions.login(text, request.header) }
    postBody("/refresh") { (text: String) => sessions.refresh(text) }
    postBody("/verify-email") { (text: String) => registration.verifyEmail(text) }
    postBody("/resend-verification-email") { (text: String) => registration.resend(text) }
    postBody("/password-reset/request") { (text: String) => profile.passwordResetRequest(text) }
    post("/password-reset/confirm") { () => profile.passwordResetConfirm() }
    postBody("/password-change/verify") { (text: String) => profile.passwordChangeVerify(text) }
    postBody("/emails/verify") { (text: String) => profile.verifyEmail(text) }
    postBody("/email-change/verify") { (text: String) => profile.emailChangeVerify(text) }
  }

  postBody("/password-change") { (text: String) => profile.passwordChange(principal, text) }
  post("/{playerId}/logout") { (playerId: String) => sessions.logout(principal, playerId) }
  get("/{playerId}") { (id: String) => profile.profile(principal, id) }
  patchBody("/{playerId}") { (id: String, text: String) => profile.editProfile(principal, id, text) }
  post("/{playerId}/close") { (_: String) => notYet("close") }
  get("/{playerId}/consent") { (id: String) => profile.consent(principal, id) }
  patchBody("/{playerId}/consent") { (id: String, text: String) => profile.changeConsent(principal, id, text) }
  patchBody("/{playerId}/cookie-consent") { (id: String, text: String) => profile.cookieConsent(principal, id, text) }
  get("/{playerId}/favourites") { (id: String) => profile.favourites(principal, id) }
  post("/{playerId}/favourites/{gameId}") { (id: String, game: String) => profile.favourite(principal, id, game, add = true) }
  delete("/{playerId}/favourites/{gameId}") { (id: String, game: String) => profile.favourite(principal, id, game, add = false) }
  get("/{playerId}/recently-played") { (id: String) => profile.recentlyPlayed(principal, id, query.raw("limit")) }
  get("/{playerId}/activity-history") { (id: String) =>
    profile.activity(principal, id, query.raw("category"), query.raw("page"), query.raw("format"))
  }
  get("/{playerId}/messages") { (id: String) =>
    profile.messages(principal, id, query.raw("status"), query.raw("page"), query.raw("pageSize"))
  }
  post("/messages/{messageId}/read") { (m: String) => profile.markMessage(principal, m, delete = false) }
  post("/messages/{messageId}/delete") { (m: String) => profile.markMessage(principal, m, delete = true) }
  get("/{playerId}/emails") { (id: String) => profile.emails(principal, id) }
  postBody("/{playerId}/emails") { (id: String, text: String) => profile.addEmail(principal, id, text) }
  post("/emails/{address}/resend") { (a: String) => profile.resendEmail(principal, a) }
  post("/emails/{address}/promote") { (a: String) => profile.promoteEmail(principal, a) }
  delete("/emails/{address}") { (a: String) => profile.removeEmail(principal, a) }
  postBody("/{playerId}/email-change") { (id: String, text: String) => profile.emailChange(principal, id, text) }
  post("/{playerId}/deposits") { (_: String) => notYet("deposits") }
  post("/{playerId}/erasure") { (_: String) => notYet("erasure") }
  post("/{playerId}/exports") { (_: String) => notYet("exports") }
  get("/{playerId}/exports/{exportId}") { (_: String, _: String) => notYet("export read") }

/** `/v1/casino/sessions/<slug>`: the player's sessions. */
final class SessionEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/sessions/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private val games = GameSessionRoutes(ctx)

  get("/players/{playerId}") { (playerId: String) => games.mine(principal, playerId) }
  get("/{sessionId}") { (sessionId: String) => games.one(principal, sessionId) }
  patch("/{sessionId}/end") { (sessionId: String) => games.endMine(principal, sessionId) }

/** `/v1/casino/backoffice/<slug>`: the brand's operators. */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private val P     = "/players/{playerId}"
  private val account = OperatorProfileRoutes(ctx)
  private val games = GameSessionRoutes(ctx)

  get("/players") { () => notYet("players") }
  get(s"$P/profile") { (id: String) => account.profile(id) }
  patchBody(s"$P/profile") { (id: String, text: String) => account.editProfile(id, text) }
  patchBody(s"$P/date-of-birth") { (id: String, text: String) => account.dateOfBirth(principal, id, text) }
  get(s"$P/wallet") { (_: String) => notYet("wallet summary") }
  get(s"$P/activity") { (_: String) => notYet("activity") }
  get(s"$P/bets") { (_: String) => notYet("bets") }
  get(s"$P/sessions/{sessionId}/bets") { (_: String, _: String) => notYet("session bets") }
  get(s"$P/sessions") { (_: String) => notYet("operator sessions") }
  get(s"$P/crm") { (id: String) => account.crm(id) }
  patchBody(s"$P/crm") { (id: String, text: String) => account.editCrm(principal, id, text) }
  patch(s"$P/suspend") { (_: String) => notYet("suspend") }
  patch(s"$P/reinstate") { (_: String) => notYet("reinstate") }
  post(s"$P/close") { (_: String) => notYet("operator close") }
  post(s"$P/reopen") { (_: String) => notYet("reopen") }
  put(s"$P/vip-tier") { (_: String) => notYet("vip-tier") }
  put(s"$P/pot-vip") { (_: String) => notYet("pot-vip") }
  post(s"$P/reset-password") { (id: String) => account.resetPassword(principal, id) }
  post(s"$P/sessions/{sessionId}/force-end") { (playerId: String, sessionId: String) => games.forceEnd(principal, playerId, sessionId) }
  post(s"$P/sessions/force-end-all") { (playerId: String) => games.forceEndAll(principal, playerId) }
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

  private val games = GameSessionRoutes(ctx)

  withAcl(Routes.serviceOf("game-provider", "support-agent")) {
    get("/{playerId}/status") { (playerId: String) => games.status(playerId) }
  }
  withAcl(Routes.serviceOf("game-provider")) {
    postBody("/{playerId}/game-sessions") { (playerId: String, text: String) => games.open(playerId, text) }
  }
  withAcl(Routes.serviceOf("support-agent")) {
    get("/{playerId}/game-sessions") { (playerId: String) => games.history(playerId, query.raw("offset")) }
    postBody("/game-sessions/{sessionId}/end") { (sessionId: String, text: String) => games.endByAgent(sessionId, text) }
  }
  post("/{playerId}/suspend") { (_: String) => notYet("kyc suspend") }
  put("/{playerId}/exclusion") { (_: String) => notYet("exclusion") }
  delete("/{playerId}/exclusion") { (_: String) => notYet("exclusion lift") }
  withAcl(Routes.serviceOf("engagement")) {
    postBody("/{playerId}/messages") { (id: String, text: String) => OperatorProfileRoutes(ctx).crmMessage(id, text) }
  }
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
