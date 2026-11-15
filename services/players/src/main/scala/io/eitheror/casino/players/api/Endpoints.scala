package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.players.PlayersRuntime
import io.eitheror.casino.players.clients.Geo

import Answers.{notYet, refusal}
import com.thinkmorestupidless.ankka.http.openapi.Description
import io.eitheror.casino.contract.http.Json as JsonBody

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

  override protected def description: Description = Described.refusals

  // Spec 031: every route described for the player SDK; the public ones before a token exists.
  withAcl(Routes.public) {
    described(_.summary("Register a player: the account opened in the realm, its verification email sent").body[Described.Register].answers[Described.Registered](201).refuses(400, "missing_fields").refuses(404, "brand_not_found").refuses(409, "email_already_exists").refuses(422, "password_too_weak").refuses(503, "service_unavailable").tag("player")) {
    postBody("/register") { (b: JsonBody) => registration.register(b.text, request.header) }
  }
    described(_.summary("Sign in: the tokens, and the player's currency and country").body[Described.Login].answers[Described.LoggedIn](200).refuses(400, "missing_fields").refuses(401, "invalid_credentials, email_not_verified, missing_token").refuses(403, "account_restricted, cooling_off").refuses(503, "service_unavailable").tag("player")) {
    postBody("/login") { (b: JsonBody) => sessions.login(b.text, request.header) }
  }
    described(_.summary("Refresh the sign-in's tokens").body[Described.Refresh].answers[Described.Refreshed](200).refuses(400, "missing_refresh_token").refuses(401, "refresh_expired").refuses(503, "service_unavailable").tag("player")) {
    postBody("/refresh") { (b: JsonBody) => sessions.refresh(b.text) }
  }
    described(_.summary("Verify the registration's address, from the link the email holds").body[Described.Token].answers[Described.Status](200).refuses(400, "missing_token, invalid_or_expired_token").refuses(503, "service_unavailable").tag("player")) {
    postBody("/verify-email") { (b: JsonBody) => registration.verifyEmail(b.text) }
  }
    described(_.summary("Send the registration's verification email again").body[Described.Email].answers[Described.Result](200).refuses(400, "missing_fields").refuses(429, "rate_limited").tag("player")) {
    postBody("/resend-verification-email") { (b: JsonBody) => registration.resend(b.text) }
  }
    described(_.summary("Ask for a password reset email").body[Described.Email].answers[Described.Status](200).refuses(400, "missing_fields").tag("player")) {
    postBody("/password-reset/request") { (b: JsonBody) => profile.passwordResetRequest(b.text) }
  }
    described(_.summary("Confirm a password reset: always refused, the reset is confirmed on the realm's page").answers[Described.Refusal](400).tag("player")) {
    post("/password-reset/confirm") { () => profile.passwordResetConfirm() }
  }
    described(_.summary("Set the new password, from the link the password change sent").body[Described.PasswordChangeVerify].answers[Described.Status](200).refuses(400, "missing_fields, invalid_token, expired_token").refuses(409, "account_suspended").refuses(422, "password_too_weak").refuses(503, "service_unavailable").tag("player")) {
    postBody("/password-change/verify") { (b: JsonBody) => profile.passwordChangeVerify(b.text) }
  }
    described(_.summary("Verify an added address, from the link the email holds").body[Described.Token].answers[Described.AddressVerified](200).refuses(400, "missing_fields, invalid_token, expired_token").refuses(503, "service_unavailable").tag("player")) {
    postBody("/emails/verify") { (b: JsonBody) => profile.verifyEmail(b.text) }
  }
    described(_.summary("Change the sign-in address, from the link sent to the new one").body[Described.Token].answers[Described.Status](200).refuses(400, "missing_fields, invalid_token, expired_token").refuses(409, "email_already_registered").refuses(503, "service_unavailable").tag("player")) {
    postBody("/email-change/verify") { (b: JsonBody) => profile.emailChangeVerify(b.text) }
  }
  }

  described(_.summary("Change the password: a link to set the new one, once the current one holds").body[Described.PasswordChange].answers[Described.Status](202).refuses(401, Described.noToken).refuses(400, "missing_fields, invalid_password").refuses(404, "player_not_found").refuses(503, "service_unavailable").tag("player")) {
    postBody("/password-change") { (b: JsonBody) => profile.passwordChange(principal, b.text) }
  }
  described(_.summary("Sign out: the session the token belongs to ended").answers[Described.Status](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    post("/{playerId}/logout") { (playerId: String) => sessions.logout(principal, playerId) }
  }
  described(_.summary("The signed-in player's profile").answers[Described.Profile](200).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(404, "player_not_found").tag("player")) {
    get("/{playerId}") { (id: String) => profile.profile(principal, id) }
  }
  described(_.summary("Edit the player's profile").body[Described.ProfileEdit].answers[Described.Updated](200).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(400, "invalid_country, no_editable_fields").tag("player")) {
    patchBody("/{playerId}") { (id: String, b: JsonBody) => profile.editProfile(principal, id, b.text) }
  }
  described(_.summary("Close the player's own account, once the password holds").body[Described.Close].answers[Described.Status](200).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(400, "invalid_password").refuses(404, "player_not_found").refuses(409, "already_closed").refuses(422, "account_closed").refuses(503, "service_unavailable").tag("player")) {
    postBody("/{playerId}/close") { (id: String, b: JsonBody) => OperatorStatusRoutes(ctx).closeMine(principal, id, b.text) }
  }
  described(_.summary("The player's marketing consents").answers[Described.Consents](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    get("/{playerId}/consent") { (id: String) => profile.consent(principal, id) }
  }
  described(_.summary("Change the player's marketing consents").body[Described.ConsentChange].answers[Described.Updated](200).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(400, "a refusal of the record").refuses(404, "player_not_found, address_not_found").refuses(409, "address_already_in_list, already_closed, kyc_locked").refuses(422, "account_closed").tag("player")) {
    patchBody("/{playerId}/consent") { (id: String, b: JsonBody) => profile.changeConsent(principal, id, b.text) }
  }
  described(_.summary("Record a device's cookie decision").body[Described.CookieConsent].answers[Described.Updated](200).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(400, "a refusal of the record").refuses(404, "player_not_found, address_not_found").refuses(409, "address_already_in_list, already_closed, kyc_locked").refuses(422, "account_closed").tag("player")) {
    patchBody("/{playerId}/cookie-consent") { (id: String, b: JsonBody) => profile.cookieConsent(principal, id, b.text) }
  }
  described(_.summary("The player's favourite games").answers[Described.GameIds](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    get("/{playerId}/favourites") { (id: String) => profile.favourites(principal, id) }
  }
  described(_.summary("Add a favourite game: the favourites after").answers[Described.GameIds](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    post("/{playerId}/favourites/{gameId}") { (id: String, game: String) => profile.favourite(principal, id, game, add = true) }
  }
  described(_.summary("Remove a favourite game: the favourites after").answers[Described.GameIds](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    delete("/{playerId}/favourites/{gameId}") { (id: String, game: String) => profile.favourite(principal, id, game, add = false) }
  }
  described(_.summary("The games the player played last, newest first").answers[Described.GameIds](200).refuses(401, Described.noToken).refuses(403, Described.mine).query[Int]("limit").tag("player")) {
    get("/{playerId}/recently-played") { (id: String) => profile.recentlyPlayed(principal, id, query.raw("limit")) }
  }
  // The history is a CSV attachment unless `format=json`: one route answering two content types, so the document gives
  // the route's bytes as a string, and the JSON shape only in the summary's words.
  described(_.summary("The player's activity, newest first: a CSV attachment, or with format=json the events").answers(200, Described.ActivitySchema).refuses(401, Described.noToken).refuses(403, Described.mine).query[String]("category").query[Int]("page").query[String]("format").tag("player")) {
    get("/{playerId}/activity-history") { (id: String) =>
      profile.activity(principal, id, query.raw("category"), query.raw("page"), query.raw("format"))
    }
  }
  described(_.summary("The player's inbox, a page of it").answers[Described.Messages](200).refuses(401, Described.noToken).refuses(403, Described.mine).query[String]("status").query[Int]("page").query[Int]("pageSize").tag("player")) {
    get("/{playerId}/messages") { (id: String) =>
      profile.messages(principal, id, query.raw("status"), query.raw("page"), query.raw("pageSize"))
    }
  }
  described(_.summary("Mark one of the player's messages read").answers[Described.Ok](200).refuses(401, Described.noToken).refuses(404, "message_not_found").tag("player")) {
    post("/messages/{messageId}/read") { (m: String) => profile.markMessage(principal, m, delete = false) }
  }
  described(_.summary("Delete one of the player's messages").answers[Described.Ok](200).refuses(401, Described.noToken).refuses(404, "message_not_found").tag("player")) {
    post("/messages/{messageId}/delete") { (m: String) => profile.markMessage(principal, m, delete = true) }
  }
  described(_.summary("The player's addresses").answers[Described.Addresses](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    get("/{playerId}/emails") { (id: String) => profile.emails(principal, id) }
  }
  described(_.summary("Add an address: a link to verify it sent").body[Described.AddAddress].answers[Described.Status](202).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(400, "missing_fields, a refusal of the record").refuses(404, "player_not_found").refuses(409, "address_already_registered, address_already_in_list").refuses(422, "account_closed").tag("player")) {
    postBody("/{playerId}/emails") { (id: String, b: JsonBody) => profile.addEmail(principal, id, b.text) }
  }
  described(_.summary("Send an address's verification link again").answers[Described.Status](202).refuses(401, Described.noToken).refuses(400, "already_verified").refuses(404, "address_not_found").tag("player")) {
    post("/emails/{address}/resend") { (a: String) => profile.resendEmail(principal, a) }
  }
  described(_.summary("Make a verified address the sign-in address").answers[Described.Promoted](200).refuses(401, Described.noToken).refuses(400, "not_verified, already_primary").refuses(404, "address_not_found").refuses(409, "email_conflict").refuses(503, "service_unavailable").tag("player")) {
    post("/emails/{address}/promote") { (a: String) => profile.promoteEmail(principal, a) }
  }
  described(_.summary("Remove an address").answers[Described.Status](200).refuses(401, Described.noToken).refuses(400, "a refusal of the record").refuses(404, "player_not_found, address_not_found").refuses(409, "address_already_in_list, already_closed, kyc_locked").refuses(422, "account_closed").tag("player")) {
    delete("/emails/{address}") { (a: String) => profile.removeEmail(principal, a) }
  }
  described(_.summary("Change the sign-in address: a link sent to the new one, once the password holds").body[Described.EmailChange].answers[Described.Status](202).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(400, "missing_fields, invalid_password").refuses(404, "player_not_found").refuses(409, "email_already_registered").refuses(503, "service_unavailable").tag("player")) {
    postBody("/{playerId}/email-change") { (id: String, b: JsonBody) => profile.emailChange(principal, id, b.text) }
  }
  described(_.summary("Start a deposit: the checks of kyc, the wallet and responsible gambling, then ingest's cashier").body[Described.Deposit].answers[Described.Cashier](200).refuses(400, "invalid_amount, invalid_currency, invalid_request_id, ingest's code").refuses(401, Described.noToken).refuses(403, "player_id_mismatch, account_restricted, kyc's code, responsible gambling's code").refuses(404, "player_not_found").refuses(409, "REQUEST_ID_REUSED, ingest's code").refuses(422, "ingest's code").refuses(429, "ingest's code").refuses(503, "service_unavailable").tag("player")) {
    postBody("/{playerId}/deposits") { (id: String, b: JsonBody) => DepositRoutes(ctx).deposit(principal, id, b.text) }
  }
  described(_.summary("File the player's own erasure, once the account is closed").answers[Described.Erasure](202).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(404, "player_not_found").refuses(409, "account_not_closed").tag("player")) {
    post("/{playerId}/erasure") { (id: String) => ErasureRoutes(ctx).fileMine(principal, id) }
  }
  described(_.summary("Start an export of the player's own data").answers[Described.ExportStarted](202).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(404, "player_not_found").tag("player")) {
    post("/{playerId}/exports") { (id: String) => ErasureRoutes(ctx).startMine(principal, id) }
  }
  described(_.summary("The player's export, once it is assembled").answers(200, Described.ExportSchema).refuses(401, Described.noToken).refuses(403, Described.mine).refuses(404, "export_not_found").refuses(409, "export_not_ready").refuses(410, "export_expired").tag("player")) {
    get("/{playerId}/exports/{exportId}") { (id: String, exportId: String) => ErasureRoutes(ctx).readMine(principal, id, exportId) }
  }

/** `/v1/casino/sessions/<slug>`: the player's sessions. */
final class SessionEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/sessions/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private val games = GameSessionRoutes(ctx)

  override protected def description: Description = Described.refusals

  described(_.summary("The player's game sessions").answers[Described.Sessions](200).refuses(401, Described.noToken).refuses(403, Described.mine).tag("player")) {
    get("/players/{playerId}") { (playerId: String) => games.mine(principal, playerId) }
  }
  described(_.summary("One of the player's game sessions").answers[Described.Session](200).refuses(404, "session_not_found").tag("player")) {
    get("/{sessionId}") { (sessionId: String) => games.one(principal, sessionId) }
  }
  described(_.summary("End one of the player's game sessions").answers[Described.Ended](200).refuses(403, Described.mine).refuses(404, "session_not_found").tag("player")) {
    patch("/{sessionId}/end") { (sessionId: String) => games.endMine(principal, sessionId) }
  }

/**
 * `/v1/casino/backoffice/<slug>`: the brand's operators. Every route requires one permission (spec 030
 * amendment P1, contracts/roles.md), decided from the brand's roles `players` keeps.
 */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}"):
  // every route names its own permission; this is never reached
  val acl: Acl = Acl.DenyAll

  private def may(permission: String) = Routes.operatorMay(ctx.slug, permission, PlayersRuntime.bags, ctx.authenticate())

  private val P     = "/players/{playerId}"
  private val account = OperatorProfileRoutes(ctx)
  private val games = GameSessionRoutes(ctx)
  private val status = OperatorStatusRoutes(ctx)

  withAcl(may("player.read")) {
  get("/players") { () =>
    status.players(query.raw("search"), query.raw("status"), query.raw("vipOnly"), query.raw("page"), query.raw("pageSize"))
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
  get("/fraud-cases") { () => FraudRoutes(ctx).cases(query.raw("status")) }
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

  override protected def description: Description = Described.refusals

  // Spec 031: described for the player SDK; public, so a visitor reads it too.
  described(_.summary("The caller's country and its dialling code, from the caller's address; nulls when it is not known")
    .answers[Described.Country](200).tag("player")) {
    get("/lookup") { () =>
      val r      = request
      val answer = ctx.neighbours.geo.lookup(Geo.callerAddress(r.header))
      val body   = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
      body.put("country", answer.country.orNull)
      body.put("dialCode", answer.dialCode.orNull)
      Answers.ok(body)
    }
  }
