package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.{Background, Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.{Bound, Geo, LogIn, Raced}
import io.eitheror.casino.players.config.BrandConfigEntity
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.realm.{RealmRefusal, RealmTokens}
import io.eitheror.casino.players.sessions.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.*

import Answers.*

/** The player a token names, and the realm session it belongs to (R4): its claims, as the verifier read them. */
final case class PlayerCaller(playerId: Option[String], realmSessionId: Option[String])

object PlayerCaller:
  def of(p: Principal): PlayerCaller =
    PlayerCaller(p.claims.get("eitheror_twin_id").filter(_.nonEmpty), p.claims.get("session_state").orElse(p.claims.get("sid")).filter(_.nonEmpty))

  /**
   * Every player route with a player id in its path (T042): a token naming no player is `401
   * missing_token`, one naming another player `403 player_id_mismatch`.
   */
  def matching(p: Principal, pathPlayerId: String)(f: PlayerCaller => Reply): Reply =
    val c = of(p)
    c.playerId match
      case None                           => refusal(401, "missing_token", "the token names no player")
      case Some(id) if id != pathPlayerId => refusal(403, "player_id_mismatch", "Authenticated player does not match requested player ID")
      case Some(_)                        => f(c)

/**
 * Sign-in, refresh and sign-out (FR-010 to FR-015; research R4, R6). Sign-in asks the realm, then the
 * player, then `may-log-in` under its bound, failing closed, and only then opens the session.
 */
final class SessionRoutes(ctx: Ctx):
  private def now(): Instant = PlayersRuntime.now()
  private def components    = ctx.clients.componentClient
  private def player(id: String) = components.forEventSourcedEntity(PlayerEntity.id(id))
  private def realmBound    = ctx.settings.registrationBound

  private def realmCall[A](call: => Either[RealmRefusal, A]): Either[RealmRefusal, A] =
    Bound.within(realmBound)(Future(call)(using Background.ec)) match
      case Raced.Answered(a)   => a
      case Raced.Unanswered(_) => Left(RealmRefusal.Failed("the realm did not answer"))
      case Raced.Failed(e)     => Left(RealmRefusal.Failed(e.getClass.getSimpleName))

  // ── POST /login ────────────────────────────────────────────────────────────────────────────────

  def login(text: String, header: String => Option[String]): Reply =
    val body     = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())
    val email    = body.text("email")
    val password = Option(body.get("password")).filter(_.isTextual).map(_.asText).filter(_.nonEmpty)
    if email.isEmpty || password.isEmpty then
      refusal(400, "missing_fields", "email and password are required", "fields" -> Json.arr("email", "password"))
    else
      val realm = ctx.neighbours.realm
      realmCall(realm.directGrant(email.get, password.get)) match
        case Left(RealmRefusal.InvalidCredentials) => refusal(401, "invalid_credentials", "the address or the password is wrong")
        case Left(RealmRefusal.EmailNotVerified)   => refusal(401, "email_not_verified", "the address is not verified")
        case Left(RealmRefusal.Disabled)           => refusal(403, "account_restricted", "the account is restricted", "reason" -> Status.Closed)
        case Left(RealmRefusal.MissingToken)       => refusal(401, "missing_token", "the realm's token names no player or session")
        case Left(_)                               => refusal(503, "service_unavailable", "the realm did not answer")
        case Right(tokens) if tokens.playerId.isEmpty || tokens.realmSessionId.isEmpty =>
          if tokens.realmSessionId.nonEmpty then realmCall(realm.revokeSession(tokens.realmSessionId)): Unit
          refusal(401, "missing_token", "the realm's token names no player or session")
        case Right(tokens) => signedIn(tokens, body, header)

  private def signedIn(tokens: RealmTokens, body: com.fasterxml.jackson.databind.JsonNode, header: String => Option[String]): Reply =
    val at    = now()
    val id    = tokens.playerId
    val realm = ctx.neighbours.realm
    def revoke(): Unit = realmCall(realm.revokeSession(tokens.realmSessionId)): Unit
    val answer = player(id).call(PlayerEntity.signIn).invoke(SignIn(at))
    answer.refusal match
      case Some(r) =>
        revoke()
        r.code match
          case "invalid_credentials" => refusal(401, "invalid_credentials", "the address or the password is wrong")
          case "email_not_verified"  => refusal(401, "email_not_verified", "the address is not verified")
          case code                  => refusal(403, code, "the account is restricted", r.details.toSeq*)
      case None =>
        val refused: Option[Reply] = ctx.neighbours.rg.mayLogIn(id, ctx.settings.mayLogInBound) match
          case Raced.Answered(LogIn.Allowed)  => None
          case Raced.Answered(LogIn.Excluded) => Some(refusal(403, "account_restricted", "the account is restricted", "reason" -> Status.SelfExcluded))
          case Raced.Answered(LogIn.CoolingOff(until)) =>
            Some(refusal(403, "cooling_off", "the player is cooling off", "until" -> until.toString))
          case _ =>
            SessionRoutes.log.warn(s"sign-in of $id: responsible-gambling did not answer may-log-in; refused")
            Some(refusal(403, "account_restricted", "the account is restricted", "reason" -> "rg_unavailable"))
        refused match
          case Some(r) =>
            player(id).call(PlayerEntity.signInRefused).invoke(RefuseSignIn("may_log_in", at)): Unit
            revoke()
            r
          case None => open(tokens, answer, body, header, at)

  private def open(tokens: RealmTokens, answer: SignInAnswer, body: com.fasterxml.jackson.databind.JsonNode,
      header: String => Option[String], at: Instant): Reply =
    val id        = tokens.playerId
    val sessionId = UUID.randomUUID().toString
    components.forEventSourcedEntity(EntityId(sessionId)).call(PlayerSessionEntity.open)
      .invoke(OpenPlayerSession(id, tokens.realmSessionId, Geo.callerAddress(header).getOrElse(""), header("user-agent").getOrElse(""),
        body.textOr("deviceFingerprint", ""), at)): Unit
    val others = player(id).call(PlayerEntity.sessionOpened).invoke(SessionId(sessionId, at)).others
    val single = components.forKeyValueEntity(EntityId(ctx.slug)).call(BrandConfigEntity.get).invoke().singleSession
    if single then
      val bound = ctx.settings.sessionEndBound.toMillis.millis
      others.toVector.sorted.foreach { other =>
        val realmSession = components.forEventSourcedEntity(EntityId(other)).call(PlayerSessionEntity.get).invoke().realmSessionId
        SessionEndWorkflow.end(components, other, EndReason.Superseded, Some(bound)): Unit
        if realmSession.nonEmpty then realmCall(ctx.neighbours.realm.revokeSession(realmSession)): Unit
      }
    val reply = Json.obj("token" -> tokens.accessToken, "refreshToken" -> tokens.refreshToken, "brandId" -> ctx.slug, "playerId" -> id,
      "pendingBonusCode" -> answer.pendingBonusCode.getOrElse(""))
    reply.put("currency", Some(answer.currency).filter(_.nonEmpty).orNull)
    reply.put("country", answer.country.orNull)
    ok(reply)

  // ── POST /refresh ──────────────────────────────────────────────────────────────────────────────

  def refresh(text: String): Reply =
    Json.parse(text).flatMap(_.text("refreshToken")) match
      case None => refusal(400, "missing_refresh_token", "refreshToken is required")
      case Some(t) =>
        realmCall(ctx.neighbours.realm.refresh(t)) match
          case Right(tokens) =>
            ok(Json.obj("accessToken" -> tokens.accessToken, "refreshToken" -> tokens.refreshToken, "expiresIn" -> tokens.expiresIn))
          case Left(RealmRefusal.InvalidToken | RealmRefusal.MissingToken | RealmRefusal.InvalidCredentials) =>
            refusal(401, "refresh_expired", "the refresh token is no longer valid")
          case Left(_) => refusal(503, "service_unavailable", "the realm did not answer")

  // ── POST /{playerId}/logout ────────────────────────────────────────────────────────────────────

  /** Ends the session the token belongs to, awaited under `SESSION_END_BOUND` (SC-003), then revokes the realm's. */
  def logout(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId) { c =>
      c.realmSessionId.foreach { realmSession =>
        SessionsView.ofRealm(ctx.clients.viewClient, realmSession).filter(_.open).foreach { row =>
          SessionEndWorkflow.end(components, row.sessionId, EndReason.Logout, Some(ctx.settings.sessionEndBound.toMillis.millis)): Unit
        }
        realmCall(ctx.neighbours.realm.revokeSession(realmSession)): Unit
      }
      ok(Json.obj("status" -> "logged_out"))
    }

object SessionRoutes:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[SessionRoutes])
