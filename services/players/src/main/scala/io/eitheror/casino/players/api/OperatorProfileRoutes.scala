package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.inbox.{CreateMessage, MessageEntity, MessageOrigin, NoteEntity, NotesView}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.reads.WalletSummaryView
import io.eitheror.casino.players.sessions.{SessionRow, SessionsView}

import java.time.Instant

import Answers.*

/**
 * The operator's view of a player's account and `engagement`'s inbox write (FR-025, FR-030, FR-033).
 * Every change goes through the entity, so the operator's edit honours the player's locks, and the
 * audit record comes from the event the entity persists.
 */
final class OperatorProfileRoutes(ctx: Ctx):
  import ProfileRoutes.{answer, consentsOf, edits}
  import OperatorProfileRoutes.OperatorFields

  private def components         = ctx.clients.componentClient
  private def views              = ctx.clients.viewClient
  private def player(id: String) = components.forEventSourcedEntity(PlayerEntity.id(id))
  private def stateOf(id: String): PlayerState = player(id).call(PlayerEntity.get).invoke()
  private def now(): Instant     = PlayersRuntime.now()
  private def body(text: String): JsonNode = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())

  private def known(playerId: String)(f: PlayerState => Reply): Reply =
    val s = stateOf(playerId)
    if !s.exists then refusal(404, "player_not_found", "No such player") else f(s)

  /**
   * The original's `{player, wallet, activeSessions, pinnedNotes}`. Its `rg` and `lifetimeStats` are
   * spec 010's and spec 015's to answer, and are not here.
   */
  def profile(playerId: String): Reply =
    known(playerId) { s =>
      val wallet = WalletSummaryView.ofPlayer(views, playerId).headOption.map(w =>
        Json.obj("realBalance" -> w.realBalanceAfter, "bonusBalance" -> w.restrictedBalanceAfter, "currency" -> w.currency)).orNull
      val active = SessionsView.ofPlayer(views, playerId, SessionRow.Player, 0, 100).filter(_.open).map(r =>
        Json.obj("sessionId" -> r.sessionId, "startedAt" -> Instant.ofEpochMilli(r.startedAt).toString))
      val pinned = NotesView.ofPlayer(views, playerId).filter(_.pinned).map { n =>
        val note = components.forKeyValueEntity(EntityId(n.noteId)).call(NoteEntity.get).invoke()
        Json.obj("noteId" -> n.noteId, "text" -> note.text.reveal, "author" -> n.author, "createdAt" -> Instant.ofEpochMilli(n.createdAt).toString)
      }
      ok(Json.obj("player" -> playerOf(s), "wallet" -> wallet, "activeSessions" -> Json.arr(active*),
        "pinnedNotes" -> Json.arr(pinned*)))
    }

  /** The player's edit, made by an operator: the same locks and the allow-list left to the player's route. */
  def editProfile(playerId: String, text: String): Reply =
    known(playerId)(_ => ProfileRoutes(ctx).edit(playerId, edits(body(text), OperatorFields), Set.empty))

  def dateOfBirth(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val b = body(text)
      val value = b.textOr("dateOfBirth", "")
      answer(player(playerId).call(PlayerEntity.correctDateOfBirth).invoke(CorrectDateOfBirth(value, b.textOr("reason", ""), p.subject, now())))(
        ok(Json.obj("dateOfBirth" -> value.trim)))
    }

  /**
   * The CRM view: consents, the suppression flag and the cookie decisions. The original's lifecycle
   * state and campaign history are `engagement`'s (spec 014) and are not here.
   */
  def crm(playerId: String): Reply =
    known(playerId) { s =>
      val o = consentsOf(s.consents)
      o.put("marketingConsent", s.consents.email.granted)
      o.put("marketingExcluded", false)
      o.put("marketingExcludedReason", "")
      o.put("messageSuppressed", s.consents.messagesSuppressed)
      o.set[JsonNode]("cookieConsents", Json.arr(s.cookieDecisions.toVector.sortBy(_._1).map((device, d) =>
        Json.obj("deviceId" -> device, "analyticsConsent" -> d.analytics, "marketingConsent" -> d.marketing, "version" -> d.version,
          "timestamp" -> d.timestamp))*))
      ok(o)
    }

  def editCrm(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val b          = body(text)
      val email      = b.bool("marketingConsent").orElse(b.bool("emailMarketingConsent"))
      val sms        = b.bool("smsMarketingConsent")
      val suppressed = b.bool("messageSuppressed")
      if email.isEmpty && sms.isEmpty && suppressed.isEmpty then refusal(400, "no_fields_provided", "No editable fields were provided")
      else
        if email.nonEmpty || sms.nonEmpty then
          player(playerId).call(PlayerEntity.changeConsent).invoke(ChangeConsent(email, sms, Actor.Operator, Some(p.subject), now())): Unit
        suppressed.foreach(v => player(playerId).call(PlayerEntity.setMessagesSuppressed).invoke(SetMessagesSuppressed(v, p.subject, now())): Unit)
        ok(Json.obj("updated" -> true))
    }

  /** The realm's own reset email, then the event the audit record is made from. */
  def resetPassword(p: Principal, playerId: String): Reply =
    known(playerId) { _ =>
      val realm = ctx.neighbours.realm
      realm.userOf(playerId).flatMap(realm.sendResetEmail) match
        case Left(io.eitheror.casino.players.realm.RealmRefusal.UserNotFound) => refusal(404, "player_not_found", "No such player")
        case Left(_) => refusal(503, "service_unavailable", "the realm did not answer")
        case Right(_) =>
          player(playerId).call(PlayerEntity.resetLinkSent).invoke(SendResetLink(p.subject, now())): Unit
          ok(Json.obj("status" -> "reset_link_sent"))
    }

  /**
   * `engagement`'s inbox write under its delivery id (spec 014): a repeat answers the same message. A
   * closed player, or one whose messages are suppressed or who has not consented to marketing for a
   * marketing message, is refused.
   */
  def crmMessage(playerId: String, text: String): Reply =
    val b = body(text)
    (b.text("deliveryId").filter(_.nonEmpty), b.text("category").filter(Set("service", "marketing"))) match
      case (None, _) => refusal(400, "missing_fields", "deliveryId is required", "fields" -> Json.arr("deliveryId"))
      case (_, None) => refusal(422, "category_required", "category must be service or marketing")
      case (Some(delivery), Some(category)) =>
        known(playerId) { s =>
          if Status.ended(s.status) then refusal(422, "account_closed", "The account is closed")
          else if s.consents.messagesSuppressed || (category == "marketing" && !s.consents.email.granted) then
            refusal(422, "marketing_suppressed", "Messages to this player are suppressed")
          else
            val m = components.forEventSourcedEntity(EntityId(delivery)).call(MessageEntity.write)
              .invoke(CreateMessage(playerId, category, MessageOrigin.Crm, b.textOr("subject", ""), b.textOr("body", ""), Some("engagement"), now()))
              .state
            json(201, Json.obj("notificationId" -> delivery, "state" -> (if m.readAt.nonEmpty then "read" else "unread"),
              "createdAt" -> m.createdAt.map(_.toString).orNull, "subject" -> m.subject.reveal, "body" -> m.body.reveal))
        }

  private def playerOf(s: PlayerState): ObjectNode =
    val o = ProfileRoutes.profileOf(s)
    o.put("lastLoginAt", s.lastSignInAt.map(_.toString).orNull)
    o.put("registrationDate", s.registeredAt.map(_.toString).orNull)
    o.put("kycStatus", if s.kycVerified then "verified" else "unverified")
    o.put("potVip", s.potVip)
    s.fraud.foreach { f =>
      o.put("suspectedDuplicate", true)
      o.set[JsonNode]("linkedPlayerIds", Json.arr(f.linkedPlayerIds.toVector.sorted*))
      o.set[JsonNode]("duplicateSignals", Json.arr(f.signals.toVector.sorted*))
      o.put("duplicateDetectedAt", f.flaggedAt.toString)
    }
    o

object OperatorProfileRoutes:
  /** What an operator may edit (the original's backoffice list); `language` and `currency` are not the entity's to edit. */
  val OperatorFields: Set[String] = Set("firstName", "lastName", "country", "addressLine1", "city", "stateProv", "postalCode", "phone")
