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
  postBody("/{playerId}/close") { (id: String, text: String) => OperatorStatusRoutes(ctx).closeMine(principal, id, text) }
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
  post("/{playerId}/erasure") { (id: String) => ErasureRoutes(ctx).fileMine(principal, id) }
  post("/{playerId}/exports") { (id: String) => ErasureRoutes(ctx).startMine(principal, id) }
  get("/{playerId}/exports/{exportId}") { (id: String, exportId: String) => ErasureRoutes(ctx).readMine(principal, id, exportId) }

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
  private val status = OperatorStatusRoutes(ctx)

  get("/players") { () =>
    status.players(query.raw("search"), query.raw("status"), query.raw("vipOnly"), query.raw("page"), query.raw("pageSize"))
  }
  get(s"$P/profile") { (id: String) => account.profile(id) }
  patchBody(s"$P/profile") { (id: String, text: String) => account.editProfile(id, text) }
  patchBody(s"$P/date-of-birth") { (id: String, text: String) => account.dateOfBirth(principal, id, text) }
  get(s"$P/wallet") { (id: String) => status.wallet(id) }
  get(s"$P/activity") { (id: String) => status.activity(id, query.raw("category"), query.raw("page")) }
  get(s"$P/bets") { (id: String) =>
    status.bets(id, query.raw("fromDate"), query.raw("toDate"), query.raw("limit"), query.raw("offset"))
  }
  get(s"$P/sessions/{sessionId}/bets") { (_: String, _: String) => notYet("session bets") }
  get(s"$P/sessions") { (id: String) => status.sessions(id) }
  get(s"$P/crm") { (id: String) => account.crm(id) }
  patchBody(s"$P/crm") { (id: String, text: String) => account.editCrm(principal, id, text) }
  patchBody(s"$P/suspend") { (id: String, text: String) => status.suspend(principal, id, text) }
  patch(s"$P/reinstate") { (id: String) => status.reinstate(principal, id) }
  postBody(s"$P/close") { (id: String, text: String) => status.close(principal, id, text) }
  postBody(s"$P/reopen") { (id: String, text: String) => status.reopen(principal, id, text) }
  putBody(s"$P/vip-tier") { (id: String, text: String) => status.vipTier(principal, id, text) }
  putBody(s"$P/pot-vip") { (id: String, text: String) => status.potVip(principal, id, text) }
  post(s"$P/reset-password") { (id: String) => account.resetPassword(principal, id) }
  post(s"$P/sessions/{sessionId}/force-end") { (playerId: String, sessionId: String) => games.forceEnd(principal, playerId, sessionId) }
  post(s"$P/sessions/force-end-all") { (playerId: String) => games.forceEndAll(principal, playerId) }
  postBody(s"$P/notes") { (id: String, text: String) => status.note(principal, id, text) }
  patch(s"$P/notes/{noteId}/pin") { (id: String, noteId: String) => status.pin(id, noteId, pinned = true) }
  patch(s"$P/notes/{noteId}/unpin") { (id: String, noteId: String) => status.pin(id, noteId, pinned = false) }
  get(s"$P/messages") { (id: String) => status.messages(id, query.raw("page"), query.raw("pageSize")) }
  postBody(s"$P/messages") { (id: String, text: String) => status.message(principal, id, text) }
  get("/merge-tags") { () => status.mergeTags }
  postBody("/messages/preview") { (text: String) => status.preview(text) }
  post(s"$P/clear-suspect-flag") { (id: String) => status.clearSuspectFlag(principal, id) }
  get("/fraud-cases") { () => FraudRoutes(ctx).cases(query.raw("status")) }
  patch("/fraud-cases/{caseId}/review") { (id: String) => FraudRoutes(ctx).review(principal, id) }
  patch("/fraud-cases/{caseId}/dismiss") { (id: String) => FraudRoutes(ctx).dismiss(principal, id) }
  post(s"$P/activity-export") { (id: String) => ErasureRoutes(ctx).startExport(id, principal.subject) }
  get("/exports/{exportId}") { (exportId: String) => ErasureRoutes(ctx).read(None, exportId) }
  post(s"$P/erasure") { (id: String) => ErasureRoutes(ctx).file(id) }
  get(s"$P/erasure") { (id: String) => ErasureRoutes(ctx).state(id) }
  get(s"$P/verification-link") { (playerId: String) => RegistrationRoutes(ctx).verificationLink(playerId) }
  get("/brand-config") { () => status.config("brand-config") }
  putBody("/brand-config") { (text: String) => status.setBrandConfig(text) }
  get("/currency-config") { () => status.config("currency-config") }
  putBody("/currency-config") { (text: String) => status.setCurrencyConfig(text) }
  get("/allow-list") { () => status.config("allow-list") }
  putBody("/allow-list") { (text: String) => status.setAllowList(text) }

/** `/v1/casino/brands/<slug>/modules`: spec 021's operator modules page, through the vendor's agent. */
final class ModulesEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/modules"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  get("/") { () => OperatorStatusRoutes(ctx).modules }
  postBody("/requests") { (text: String) => OperatorStatusRoutes(ctx).moduleRequest(principal, text) }

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
  withAcl(Routes.serviceOf("kyc")) {
    postBody("/{playerId}/suspend") { (id: String, text: String) => OperatorStatusRoutes(ctx).suspendByKyc(id, text) }
  }
  withAcl(Routes.serviceOf("responsible-gambling")) {
    putBody("/{playerId}/exclusion") { (id: String, text: String) => OperatorStatusRoutes(ctx).exclude(id, text) }
    delete("/{playerId}/exclusion") { (id: String) => OperatorStatusRoutes(ctx).liftExclusion(id) }
  }
  withAcl(Routes.serviceOf("engagement")) {
    postBody("/{playerId}/messages") { (id: String, text: String) => OperatorProfileRoutes(ctx).crmMessage(id, text) }
  }
  get("/config/{name}") { (name: String) => OperatorStatusRoutes(ctx).config(name) }
  withAcl(Routes.serviceOf("players")) {
    post("/erasure/players/{playerId}") { (id: String) => ErasureRoutes(ctx).eraseOwn(id) }
    get("/exports/players/{playerId}") { (id: String) => ErasureRoutes(ctx).ownPart(id) }
  }

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
