package io.eitheror.casino.players.port

import io.eitheror.casino.contract.port.{DescriptionCheck, Recording}
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute
import io.eitheror.casino.players.it.Kit
import io.eitheror.casino.players.port.WireTranslation.Context

import java.nio.file.Paths

/**
 * The kept description against the original (spec 031 FR-013). Every recordings directory holds
 * players' calls (registration, sign-in, verification); each recorded step is read through
 * [[WireTranslation]] onto the route the rebuild serves, with its recorded status. The original
 * answered fewer fields than the rebuild (the parity test narrows the rebuild's answers to the
 * recorded ones), so here a step's status is checked and its body is not: the bodies are checked where
 * `ParitySuite` replays the same steps through the held kit (`Held`).
 *
 * The table is the description's operations, the ones with fewest path parameters first, so a literal
 * segment (`/register`, `/emails/verify`) is matched before a parameter that would take it.
 */
class DescriptionSuite extends munit.FunSuite:

  private val root = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize

  private val routes = Vector(
    OriginalRoute("GET", "/v1/casino/geo/lookup".r, None, "get_v1_casino_geo_lookup"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/email-change/verify".r, None, "post_v1_casino_players_the_brand_email_change_verify"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/emails/verify".r, None, "post_v1_casino_players_the_brand_emails_verify"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/login".r, None, "post_v1_casino_players_the_brand_login"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/password-change".r, None, "post_v1_casino_players_the_brand_password_change"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/password-change/verify".r, None, "post_v1_casino_players_the_brand_password_change_verify"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/password-reset/confirm".r, None, "post_v1_casino_players_the_brand_password_reset_confirm"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/password-reset/request".r, None, "post_v1_casino_players_the_brand_password_reset_request"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/refresh".r, None, "post_v1_casino_players_the_brand_refresh"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/register".r, None, "post_v1_casino_players_the_brand_register"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/resend-verification-email".r, None, "post_v1_casino_players_the_brand_resend_verification_email"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/verify-email".r, None, "post_v1_casino_players_the_brand_verify_email"),
    OriginalRoute("DELETE", "/v1/casino/players/[^/]+/emails/[^/]+".r, None, "delete_v1_casino_players_the_brand_emails_address"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+".r, None, "get_v1_casino_players_the_brand_playerId"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/activity-history".r, None, "get_v1_casino_players_the_brand_playerId_activity_history"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/consent".r, None, "get_v1_casino_players_the_brand_playerId_consent"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/emails".r, None, "get_v1_casino_players_the_brand_playerId_emails"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/favourites".r, None, "get_v1_casino_players_the_brand_playerId_favourites"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/messages".r, None, "get_v1_casino_players_the_brand_playerId_messages"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/recently-played".r, None, "get_v1_casino_players_the_brand_playerId_recently_played"),
    OriginalRoute("GET", "/v1/casino/sessions/[^/]+/[^/]+".r, None, "get_v1_casino_sessions_the_brand_sessionId"),
    OriginalRoute("GET", "/v1/casino/sessions/[^/]+/players/[^/]+".r, None, "get_v1_casino_sessions_the_brand_players_playerId"),
    OriginalRoute("PATCH", "/v1/casino/players/[^/]+/[^/]+".r, None, "patch_v1_casino_players_the_brand_playerId"),
    OriginalRoute("PATCH", "/v1/casino/players/[^/]+/[^/]+/consent".r, None, "patch_v1_casino_players_the_brand_playerId_consent"),
    OriginalRoute("PATCH", "/v1/casino/players/[^/]+/[^/]+/cookie-consent".r, None, "patch_v1_casino_players_the_brand_playerId_cookie_consent"),
    OriginalRoute("PATCH", "/v1/casino/sessions/[^/]+/[^/]+/end".r, None, "patch_v1_casino_sessions_the_brand_sessionId_end"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/close".r, None, "post_v1_casino_players_the_brand_playerId_close"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/deposits".r, None, "post_v1_casino_players_the_brand_playerId_deposits"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/email-change".r, None, "post_v1_casino_players_the_brand_playerId_email_change"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/emails".r, None, "post_v1_casino_players_the_brand_playerId_emails"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/erasure".r, None, "post_v1_casino_players_the_brand_playerId_erasure"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/exports".r, None, "post_v1_casino_players_the_brand_playerId_exports"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/logout".r, None, "post_v1_casino_players_the_brand_playerId_logout"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/emails/[^/]+/promote".r, None, "post_v1_casino_players_the_brand_emails_address_promote"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/emails/[^/]+/resend".r, None, "post_v1_casino_players_the_brand_emails_address_resend"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/messages/[^/]+/delete".r, None, "post_v1_casino_players_the_brand_messages_messageId_delete"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/messages/[^/]+/read".r, None, "post_v1_casino_players_the_brand_messages_messageId_read"),
    OriginalRoute("DELETE", "/v1/casino/players/[^/]+/[^/]+/favourites/[^/]+".r, None, "delete_v1_casino_players_the_brand_playerId_favourites_gameId"),
    OriginalRoute("GET", "/v1/casino/players/[^/]+/[^/]+/exports/[^/]+".r, None, "get_v1_casino_players_the_brand_playerId_exports_exportId"),
    OriginalRoute("POST", "/v1/casino/players/[^/]+/[^/]+/favourites/[^/]+".r, None, "post_v1_casino_players_the_brand_playerId_favourites_gameId")
  )

  /** The step on the rebuild's route, with the original's status and no body. */
  private def read(recording: Recording, step: Recording.Step): Option[Recording.Step] =
    val path = WireTranslation.path(step.request.method, step.request.path, Context("spinvibe", Some("recorded")))
    Some(step.copy(request = step.request.copy(path = path), response = step.response.copy(body = None)))

  private lazy val result = DescriptionCheck.run(Kit.description, DescriptionCheck.recordings(root), routes, read)

  test("every recorded call to a player route answers a status the description names") {
    assert(result.differences.isEmpty, result.differences.map(d => s"${d.recording} ${d.step} ${d.operationId}: ${d.what}").mkString("\n"))
  }

  test("the routes no recording calls are tests/port/unrecorded-player-routes/players.txt") {
    assertEquals(result.unrecorded, DescriptionCheck.unrecordedList(root, "players"))
  }

  test("the original's player calls are found") {
    assert(result.matched >= 200, s"matched ${result.matched}")
  }
