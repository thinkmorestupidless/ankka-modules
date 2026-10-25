package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.players.{Json, PlayersRuntime, Secrets}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.{LogIn, Raced}
import io.eitheror.casino.players.player.{PlayerEntity, PlayersView, Status}
import io.eitheror.casino.players.realm.Webhook
import io.eitheror.casino.players.registration.RegistrationWorkflow
import io.eitheror.casino.players.sessions.{EndReason, SessionEndWorkflow, SessionsView}

import java.util.Locale
import scala.util.Try

import Answers.*

/**
 * The realm's listener and authenticators (FR-008, FR-009, FR-013; contracts/routes.md's
 * `RealmEndpoint`), each verifying its shared secret before anything else.
 */
final class RealmRoutes(ctx: Ctx):
  private def components = ctx.clients.componentClient

  // ── POST /webhook ──────────────────────────────────────────────────────────────────────────────

  /** Always `200` once the secret holds: the listener does not retry, so an event this service cannot act on is dropped, logged. */
  def webhook(text: String, header: String => Option[String]): Reply =
    ctx.neighbours.secret(Secrets.WebhookSecret).toOption match
      case None => refusal(503, "webhook_secret_not_configured", "the webhook secret is not configured")
      case Some(secret) if !Webhook.verify(header("x-webhook-secret"), Some(secret)) => refusal(401, "unauthorized", "the webhook secret does not match")
      case Some(_) =>
        Webhook.parse(text).filter(_.realmId == ctx.settings.realm).foreach { e =>
          e.kind match
            case "LOGIN" => ()
            case kind =>
              val reason = if kind == "SESSION_EXPIRED" then EndReason.Expired else EndReason.Logout
              Try(SessionsView.ofRealm(ctx.clients.viewClient, e.sessionId)).toOption.flatten.filter(_.open).foreach { row =>
                SessionEndWorkflow.end(components, row.sessionId, reason, None): Unit
              }
        }
        ok(Json.obj("ok" -> true))

  // ── POST /may-sign-in ──────────────────────────────────────────────────────────────────────────

  /**
   * The realm's authenticator (R4, SC-004): the player's status, then `may-log-in` under
   * `REALM_ANSWER_BOUND`; no answer is a refusal, and a player this service does not know is refused.
   */
  def maySignIn(text: String, header: String => Option[String]): Reply =
    secured(header) {
      Json.parse(text).flatMap(_.text("email")) match
        case None => refusal(400, "missing_fields", "email is required", "fields" -> Json.arr("email"))
        case Some(email) =>
          playerOf(email) match
            case None => ok(Json.obj("allowed" -> false, "reason" -> "player_not_found"))
            case Some(id) =>
              val p = components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
              if p.status == Status.Pending then ok(Json.obj("allowed" -> true))
              else if p.status != Status.Active then ok(Json.obj("allowed" -> false, "reason" -> p.status))
              else
                ctx.neighbours.rg.mayLogIn(id, ctx.settings.realmAnswerBound) match
                  case Raced.Answered(LogIn.Allowed)  => ok(Json.obj("allowed" -> true))
                  case Raced.Answered(LogIn.Excluded) => ok(Json.obj("allowed" -> false, "reason" -> Status.SelfExcluded))
                  case Raced.Answered(LogIn.CoolingOff(until)) =>
                    ok(Json.obj("allowed" -> false, "reason" -> "cooling_off", "until" -> until.toString))
                  case _ => ok(Json.obj("allowed" -> false, "reason" -> "rg_unavailable"))
    }

  /** The player holding an address: its registration's outcome, else the players view's primary address. */
  private def playerOf(email: String): Option[String] =
    val flow = components.forWorkflow(RegistrationWorkflow.idOf(ctx.slug, email)).call(RegistrationWorkflow.status).invoke()
    flow.outcome.flatMap(_.playerId).orElse {
      val lower = email.trim.toLowerCase(Locale.ROOT)
      PlayersView.find(ctx.clients.viewClient, lower, 5).find(_.primaryEmail.reveal.equalsIgnoreCase(lower)).map(_.playerId)
    }

  // ── POST /gamstop-refusal ──────────────────────────────────────────────────────────────────────

  /** The GAMSTOP authenticator's refusal, passed to responsible gambling (spec 010 FR-022), best effort. */
  def gamstopRefusal(text: String, header: String => Option[String]): Reply =
    secured(header) {
      val body = Json.parse(text).getOrElse(Json.obj())
      (body.text("email"), body.text("result")) match
        case (Some(email), Some(result)) =>
          ctx.neighbours.rg.gamstopRefusal(email, result, ctx.settings.sessionEndBound) match
            case Raced.Answered(_) => ()
            case _                 => RealmRoutes.log.warn("gamstop-refusal: responsible-gambling did not take it")
          ok(Json.obj("ok" -> true))
        case _ => refusal(400, "missing_fields", "email and result are required", "fields" -> Json.arr("email", "result"))
    }

  private def secured(header: String => Option[String])(f: => Reply): Reply =
    if Webhook.verify(header("x-realm-secret"), ctx.neighbours.secret(Secrets.RealmSecret).toOption) then f
    else refusal(401, "unauthorized", "the realm secret does not match")

object RealmRoutes:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[RealmRoutes])
