package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.players.PlayersRuntime
import io.eitheror.casino.players.clients.Geo

import Answers.{notYet, refusal}

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
  postBody("/{playerId}/deposits") { (id: String, text: String) => DepositRoutes(ctx).deposit(principal, id, text) }
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

/**
 * `/v1/casino/backoffice/<slug>`: the brand's operators. Every route requires one permission (spec 030
 * amendment P1, contracts/roles.md), decided from the brand's roles `players` keeps.
 */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}"):
  // every route names its own permission; this admits only the routes none matches, so that an operator of
  // the brand asking for a path no route serves is answered 404, and anyone else is refused as before
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private def may(permission: String) = Routes.operatorMay(ctx.slug, permission, PlayersRuntime.bags, ctx.authenticate())

  private val P     = "/players/{playerId}"
  private val account = OperatorProfileRoutes(ctx)
  private val games = GameSessionRoutes(ctx)
  private val status = OperatorStatusRoutes(ctx)

  withAcl(may("player.read")) {
  get("/players") { () =>
    val q = query
    status.players(OperatorStatusRoutes.PlayersQuery(q.raw("search"), q.raw("status"), q.raw("vipOnly"), q.raw("page"), q.raw("pageSize"),
      q.raw("country"), q.raw("currency"), q.raw("potVip"), q.raw("online"), q.raw("identity"), q.raw("birthdayWithinDays"), q.raw("facets")))
  }
  }
  withAcl(may("player.read")) {
  get(s"$P/profile") { (id: String) => account.profile(id) }
  }
  withAcl(may("profile.change")) {
  patchBody(s"$P/profile") { (id: String, text: String) => account.editProfile(principal, id, text) }
  }
  withAcl(may("profile.change")) {
  patchBody(s"$P/date-of-birth") { (id: String, text: String) => account.dateOfBirth(principal, id, text) }
  }
  withAcl(may("player.read")) {
  get(s"$P/wallet") { (id: String) => status.wallet(id) }
  }
  withAcl(may("player.read")) {
  get(s"$P/activity") { (id: String) => status.activity(id, query.raw("category"), query.raw("page")) }
  }
  withAcl(may("player.read")) {
  get(s"$P/bets") { (id: String) =>
    status.bets(id, query.raw("fromDate"), query.raw("toDate"), query.raw("limit"), query.raw("offset"))
  }
  }
  withAcl(may("player.read")) {
  get(s"$P/sessions/{sessionId}/bets") { (_: String, _: String) => notYet("session bets") }
  }
  withAcl(may("player.read")) {
  get(s"$P/sessions") { (id: String) => status.sessions(id) }
  }
  withAcl(may("player.read")) {
  get(s"$P/crm") { (id: String) => account.crm(id) }
  }
  withAcl(may("profile.change")) {
  patchBody(s"$P/crm") { (id: String, text: String) => account.editCrm(principal, id, text) }
  }
  withAcl(may("standing.change")) {
  patchBody(s"$P/suspend") { (id: String, text: String) => status.suspend(principal, id, text) }
  }
  withAcl(may("standing.change")) {
  patch(s"$P/reinstate") { (id: String) => status.reinstate(principal, id) }
  }
  withAcl(may("standing.change")) {
  postBody(s"$P/close") { (id: String, text: String) => status.close(principal, id, text) }
  }
  withAcl(may("standing.change")) {
  postBody(s"$P/reopen") { (id: String, text: String) => status.reopen(principal, id, text) }
  }
  withAcl(may("tier.change")) {
  putBody(s"$P/vip-tier") { (id: String, text: String) => status.vipTier(principal, id, text) }
  }
  withAcl(may("tier.change")) {
  putBody(s"$P/pot-vip") { (id: String, text: String) => status.potVip(principal, id, text) }
  }
  withAcl(may("profile.change")) {
  post(s"$P/reset-password") { (id: String) => account.resetPassword(principal, id) }
  }
  withAcl(may("session.end")) {
  post(s"$P/sessions/{sessionId}/force-end") { (playerId: String, sessionId: String) => games.forceEnd(principal, playerId, sessionId) }
  }
  withAcl(may("session.end")) {
  post(s"$P/sessions/force-end-all") { (playerId: String) => games.forceEndAll(principal, playerId) }
  }
  withAcl(may("note.write")) {
  postBody(s"$P/notes") { (id: String, text: String) => status.note(principal, id, text) }
  }
  withAcl(may("note.write")) {
  patch(s"$P/notes/{noteId}/pin") { (id: String, noteId: String) => status.pin(id, noteId, pinned = true) }
  }
  withAcl(may("note.write")) {
  patch(s"$P/notes/{noteId}/unpin") { (id: String, noteId: String) => status.pin(id, noteId, pinned = false) }
  }
  withAcl(may("player.read")) {
  get(s"$P/messages") { (id: String) => status.messages(id, query.raw("page"), query.raw("pageSize")) }
  }
  withAcl(may("message.send")) {
  postBody(s"$P/messages") { (id: String, text: String) => status.message(principal, id, text) }
  }
  withAcl(may("message.send")) {
  get("/merge-tags") { () => status.mergeTags }
  }
  withAcl(may("message.send")) {
  postBody("/messages/preview") { (text: String) => status.preview(text) }
  }
  withAcl(may("flag.clear")) {
  post(s"$P/clear-suspect-flag") { (id: String) => status.clearSuspectFlag(principal, id) }
  }
  // spec 011 FR-036, amended by spec 030: flag.clear
  withAcl(may("flag.clear")) {
  post(s"$P/clear-no-deposit-block") { (id: String) => status.clearNoDepositBlock(principal, id) }
  }
  withAcl(may("fraud.review")) {
  get("/fraud-cases") { () => FraudRoutes(ctx).cases(query.raw("status"), query.raw("page"), query.raw("pageSize")) }
  }
  withAcl(may("fraud.review")) {
  patch("/fraud-cases/{caseId}/review") { (id: String) => FraudRoutes(ctx).review(principal, id) }
  }
  withAcl(may("fraud.review")) {
  patchBody("/fraud-cases/{caseId}/dismiss") { (id: String, text: String) => FraudRoutes(ctx).dismiss(principal, id, text) }
  }
  withAcl(may("export.player")) {
  post(s"$P/activity-export") { (id: String) => ErasureRoutes(ctx).startExport(id, principal.subject) }
  }
  withAcl(may("export.player")) {
  get("/exports/{exportId}") { (exportId: String) => ErasureRoutes(ctx).read(None, exportId) }
  }
  withAcl(may("erasure.request")) {
  post(s"$P/erasure") { (id: String) => ErasureRoutes(ctx).file(id) }
  }
  withAcl(may("erasure.request")) {
  get(s"$P/erasure") { (id: String) => ErasureRoutes(ctx).state(id) }
  }
  withAcl(may("profile.change")) {
  get(s"$P/verification-link") { (playerId: String) => RegistrationRoutes(ctx).verificationLink(playerId) }
  }
  withAcl(may("config.brand")) {
  get("/brand-config") { () => status.config("brand-config") }
  }
  withAcl(may("config.brand")) {
  putBody("/brand-config") { (text: String) => status.setBrandConfig(text) }
  }
  withAcl(may("config.brand")) {
  get("/currency-config") { () => status.config("currency-config") }
  }
  withAcl(may("config.brand")) {
  putBody("/currency-config") { (text: String) => status.setCurrencyConfig(text) }
  }
  withAcl(may("config.brand")) {
  get("/allow-list") { () => status.config("allow-list") }
  }
  withAcl(may("config.brand")) {
  putBody("/allow-list") { (text: String) => status.setAllowList(text) }
  }

  // spec 030 P12, P13: the brand's roles, each route requiring its permission
  private val roles = RolesRoutes(ctx)
  withAcl(may("roles.read")) {
    get("/roles") { () => roles.list }
  }
  withAcl(may("roles.change")) {
    putBody("/roles/{name}") { (name: String, text: String) => roles.set(principal, name, text) }
    delete("/roles/{name}") { (name: String) => roles.delete(principal, name) }
    post("/roles/{name}/reset") { (name: String) => roles.reset(principal, name) }
  }
  withAcl(may("roles.give")) {
    putBody("/role-assignments/{operator}") { (operator: String, text: String) => roles.give(principal, operator, text) }
  }

/** `/v1/casino/brands/<slug>/modules`: spec 021's operator modules page, through the vendor's agent. */
final class ModulesEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/modules"):
  val acl: Acl = Acl.DenyAll

  private def may(permission: String) = Routes.operatorMay(ctx.slug, permission, PlayersRuntime.bags, ctx.authenticate())

  withAcl(may("modules.read")) {
    get("/") { () => OperatorStatusRoutes(ctx).modules }
  }
  withAcl(may("modules.request")) {
    postBody("/requests") { (text: String) => OperatorStatusRoutes(ctx).moduleRequest(principal, text) }
  }

/** `/v1/casino/brands/<slug>/players`: the project's services, by certificate. */
final class ServiceEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/players"):
  val acl: Acl = Routes.service()

  private val games = GameSessionRoutes(ctx)

  withAcl(Routes.serviceOf("game-provider", "support-agent", "exchange")) {
    get("/{playerId}/status") { (playerId: String) => games.status(playerId) }
  }
  withAcl(Routes.serviceOf("game-provider")) {
    postBody("/{playerId}/game-sessions") { (playerId: String, text: String) => games.open(playerId, text) }
  }
  withAcl(Routes.serviceOf("support-agent")) {
    get("/{playerId}/game-sessions") { (playerId: String) => games.history(playerId, query.raw("offset")) }
    postBody("/game-sessions/{sessionId}/end") { (sessionId: String, text: String) => games.endByAgent(sessionId, text) }
    get("/{playerId}/agent-profile") { (playerId: String) => games.agentProfile(playerId, query.raw("fields")) }
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
  // spec 030: every service of the brand reads the brand's roles here, every 10 seconds (contract.access.RoleBags)
  get("/roles") { () => RolesRoutes(ctx).list }
  // spec 030 P14: the brand's enabled modules, for the backoffice's frame whatever the operator's roles
  get("/modules") { () => OperatorStatusRoutes(ctx).modules }
  // spec 030 P2 (F): the freshness of the views behind the operator lists, for an operator who may read the
  // players, or for the project's services (the backoffice's server)
  withAcl(Acl.anyOf(Routes.operatorMay(ctx.slug, "player.read", PlayersRuntime.bags, ctx.authenticate()), Routes.service())) {
    get("/status") { () => io.eitheror.casino.players.freshness.Freshness.answer(
      io.eitheror.casino.players.freshness.Freshness.of(ctx.clients.componentClient)) }
  }
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
