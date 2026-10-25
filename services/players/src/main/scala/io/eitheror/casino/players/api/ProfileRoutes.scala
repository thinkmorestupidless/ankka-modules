package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Principal, Respond}
import io.eitheror.casino.players.{Json, PlayersRuntime, Secrets}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.Raced
import io.eitheror.casino.players.config.AllowListEntity
import io.eitheror.casino.players.inbox.{MessageAt, MessageEntity, MessageRow, MessagesView, MessageOrigin}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.realm.{RealmRefusal, Tokens}
import io.eitheror.casino.players.reads.{ActivityCategory, ActivityRow, ActivityView, RecentlyPlayedView}

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.{Locale, UUID}

import Answers.*

/**
 * The player's own account (FR-023 to FR-030): the profile, consents, cookie decisions, favourites, the
 * histories, the inbox, the addresses and the password. Bodies keep the original's field names; where
 * the original answered something this service will not (its raw property map, its tokens), the
 * divergence is named in contracts/routes.md.
 */
final class ProfileRoutes(ctx: Ctx):
  import ProfileRoutes.*

  private def components         = ctx.clients.componentClient
  private def views              = ctx.clients.viewClient
  private def player(id: String) = components.forEventSourcedEntity(PlayerEntity.id(id))
  private def stateOf(id: String): PlayerState = player(id).call(PlayerEntity.get).invoke()
  private def now(): Instant     = PlayersRuntime.now()
  private def body(text: String): JsonNode = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())

  /** A route with no player id in its path: the token's player, or `401 missing_token`. */
  private def caller(p: Principal)(f: String => Reply): Reply =
    PlayerCaller.of(p).playerId.fold(refusal(401, "missing_token", "the token names no player"))(f)

  // ── the profile ────────────────────────────────────────────────────────────────────────────────

  def profile(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val s = stateOf(playerId)
      if !s.exists then refusal(404, "player_not_found", "No such player") else ok(ProfileRoutes.profileOf(s))
    }

  /** The original's editable fields; a string sets, `null` or `""` clears a clearable field; anything else is dropped. */
  def editProfile(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val allow = components.forKeyValueEntity(EntityId(ctx.slug)).call(AllowListEntity.get).invoke()
      edit(playerId, edits(body(text), Profile.editable), allow.countries)
    }

  private[api] def edit(playerId: String, asked: Map[String, Option[String]], allowList: Set[String]): Reply =
    player(playerId).call(PlayerEntity.editProfile).invoke(EditProfile(asked, allowList, now())).refusal match
      case Some(r) if r.code == "invalid_country" => refusal(400, r.code, "The country is not one this brand allows")
      case Some(r)                                => refusal(400, r.code, "No editable fields were provided")
      case None                                   => ok(Json.obj("updated" -> true))

  // ── consents and cookies ───────────────────────────────────────────────────────────────────────

  def consent(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId)(_ => ok(consentsOf(stateOf(playerId).consents)))

  def changeConsent(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val b = body(text)
      answer(player(playerId).call(PlayerEntity.changeConsent)
        .invoke(ChangeConsent(b.bool("emailMarketingConsent"), b.bool("smsMarketingConsent"), Actor.Player, Some(playerId), now())))(
        ok(Json.obj("updated" -> true)))
    }

  def cookieConsent(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val b = body(text)
      answer(player(playerId).call(PlayerEntity.recordCookieDecision).invoke(RecordCookieDecision(b.text("deviceId"),
        b.bool("analyticsConsent"), b.bool("marketingConsent"), b.text("version"), b.text("timestamp"), now())))(
        ok(Json.obj("updated" -> true)))
    }

  // ── favourites and the histories ───────────────────────────────────────────────────────────────

  def favourites(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId)(_ => ok(gameIds(stateOf(playerId).favourites.toVector.sorted)))

  def favourite(p: Principal, playerId: String, gameId: String, add: Boolean): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val cmd = if add then PlayerEntity.addFavourite else PlayerEntity.removeFavourite
      player(playerId).call(cmd).invoke(Favourite(gameId, now())): Unit
      ok(gameIds(stateOf(playerId).favourites.toVector.sorted))
    }

  def recentlyPlayed(p: Principal, playerId: String, limit: Option[String]): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val n = limit.flatMap(_.toIntOption).filter(n => n >= 1 && n <= 20).getOrElse(20)
      ok(gameIds(RecentlyPlayedView.ofPlayer(views, playerId, n).map(_.gameId)))
    }

  /**
   * The original's history: `format=json` or, by default, a CSV attachment; newest first. Paged here
   * (`page`, 100 a page) where the original capped at 500, and filtered by `category`.
   */
  def activity(p: Principal, playerId: String, category: Option[String], page: Option[String], format: Option[String]): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val cat  = category.map(_.trim.toLowerCase(Locale.ROOT)).filter(ActivityCategory.all.contains)
      val n    = page.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1)
      val rows = ActivityView.ofPlayer(views, playerId, cat, (n - 1) * PageSize, PageSize)
      if format.exists(_.equalsIgnoreCase("json")) then
        ok(Json.obj("events" -> Json.arr(rows.map(event)*), "from" -> null, "until" -> null, "generatedAt" -> now().toString,
          "page" -> n))
      else
        val day = LocalDate.ofInstant(now(), ZoneOffset.UTC)
        Respond(Bytes("text/csv", csv(rows).getBytes(UTF_8)), 200,
          Vector("Content-Disposition" -> s"""attachment; filename="activity-${ctx.slug}-$day.csv""""))
    }

  // ── the inbox ──────────────────────────────────────────────────────────────────────────────────

  def messages(p: Principal, playerId: String, status: Option[String], page: Option[String], pageSize: Option[String]): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val n    = page.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1)
      val size = pageSize.flatMap(_.toIntOption).filter(s => s >= 1 && s <= 100).getOrElse(20)
      val all  = MessagesView.ofPlayer(views, playerId, 0, 1000)
      val seen = if status.contains("unread") then all.filter(_.unread) else all
      val items = seen.slice((n - 1) * size, n * size).map(r => message(r, components.forEventSourcedEntity(EntityId(r.messageId))
        .call(MessageEntity.get).invoke()))
      ok(Json.obj("items" -> Json.arr(items*), "total" -> seen.size, "unreadCount" -> MessagesView.unreadCount(all), "page" -> n,
        "pageSize" -> size))
    }

  /** Read or delete one of the caller's messages; another's, or none, is `404 message_not_found`. */
  def markMessage(p: Principal, messageId: String, delete: Boolean): Reply =
    caller(p) { me =>
      val entity = components.forEventSourcedEntity(EntityId(messageId))
      val m      = entity.call(MessageEntity.get).invoke()
      if !m.exists || m.playerId != me || m.deletedByPlayerAt.nonEmpty then refusal(404, "message_not_found", "No such message")
      else
        entity.call(if delete then MessageEntity.delete else MessageEntity.read).invoke(MessageAt(now())): Unit
        ok(Json.obj("ok" -> true))
    }

  // ── the addresses ──────────────────────────────────────────────────────────────────────────────

  def emails(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId)(_ => ok(Json.obj("emails" -> Json.arr(stateOf(playerId).emails.map(address)*))))

  /** Adds an address and sends its link (`email_add`); one another player holds is `409 address_already_registered`. */
  def addEmail(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      body(text).text("address").map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty) match
        case None => refusal(400, "missing_fields", "address is required", "fields" -> Json.arr("address"))
        case Some(a) if Lookup.playerOf(components, views, ctx.slug, a).exists(_ != playerId) =>
          refusal(409, "address_already_registered", "The address belongs to another account")
        case Some(a) =>
          answer(player(playerId).call(PlayerEntity.addEmail).invoke(Address(a, now()))) {
            send(playerId, a, Tokens.Purpose.EmailAdd, "/emails/verify", Triggers.EmailAdd)
            json(202, Json.obj("status" -> "verification_email_sent"))
          }
    }

  /** The added address's link followed (public): verified, not primary. */
  def verifyEmail(text: String): Reply =
    body(text).text("token") match
      case None => refusal(400, "missing_fields", "token is required", "fields" -> Json.arr("token"))
      case Some(t) =>
        claims(t, Tokens.Purpose.EmailAdd) { c =>
          player(c.playerId).call(PlayerEntity.verifyAddedEmail).invoke(Address(c.address, now())).refusal match
            case Some(_) => refusal(400, "invalid_token", "The link is not valid")
            case None    => ok(Json.obj("status" -> "verified", "address" -> c.address))
        }

  def resendEmail(p: Principal, encoded: String): Reply =
    caller(p) { me =>
      val a = decode(encoded)
      stateOf(me).address(a) match
        case None                  => refusal(404, "address_not_found", "No such address")
        case Some(e) if e.verified => refusal(400, "already_verified", "The address is already verified")
        case Some(e) =>
          send(me, e.address.reveal, Tokens.Purpose.EmailAdd, "/emails/verify", Triggers.EmailAdd)
          json(202, Json.obj("status" -> "verification_email_sent"))
    }

  /** The realm's sign-in address changed first, then the entity's primary: the realm is the authority (R13). */
  def promoteEmail(p: Principal, encoded: String): Reply =
    caller(p) { me =>
      val a = decode(encoded)
      stateOf(me).address(a) match
        case None                   => refusal(404, "address_not_found", "No such address")
        case Some(e) if !e.verified => refusal(400, "not_verified", "The address is not verified")
        case Some(e) if e.primary   => refusal(400, "already_primary", "The address is already the sign-in address")
        case Some(_) if Lookup.playerOf(components, views, ctx.slug, a).exists(_ != me) =>
          refusal(409, "email_conflict", "The address belongs to another account")
        case Some(e) =>
          val realm = ctx.neighbours.realm
          realm.userOf(me).flatMap(realm.changeAddress(_, e.address.reveal)) match
            case Left(_) => refusal(503, "service_unavailable", "the realm did not answer")
            case Right(_) =>
              answer(player(me).call(PlayerEntity.promoteEmail).invoke(Address(a, now())))(
                ok(Json.obj("status" -> "primary_updated", "newPrimary" -> e.address.reveal)))
    }

  def removeEmail(p: Principal, encoded: String): Reply =
    caller(p)(me => answer(player(me).call(PlayerEntity.removeEmail).invoke(Address(decode(encoded), now())))(
      ok(Json.obj("status" -> "removed"))))

  /** The sign-in address changed through a link to the new one (`email_change`), after the current password. */
  def emailChange(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val b = body(text)
      (b.text("newEmail").map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty), b.text("currentPassword").orElse(b.text("password"))) match
        case (Some(next), Some(password)) =>
          val s = stateOf(playerId)
          if Lookup.playerOf(components, views, ctx.slug, next).exists(_ != playerId) then
            refusal(409, "email_already_registered", "The address belongs to another account")
          else
            passwordHolds(s, password) {
              send(playerId, next, Tokens.Purpose.EmailChange, "/email-change/verify", Triggers.EmailChange)
              json(202, Json.obj("status" -> "verification_email_sent"))
            }
        case _ => refusal(400, "missing_fields", "newEmail and currentPassword are required", "fields" -> Json.arr("newEmail", "currentPassword"))
    }

  def emailChangeVerify(text: String): Reply =
    body(text).text("token") match
      case None => refusal(400, "missing_fields", "token is required", "fields" -> Json.arr("token"))
      case Some(t) =>
        claims(t, Tokens.Purpose.EmailChange) { c =>
          if Lookup.playerOf(components, views, ctx.slug, c.address).exists(_ != c.playerId) then
            refusal(409, "email_already_registered", "The address belongs to another account")
          else
            val realm = ctx.neighbours.realm
            realm.userOf(c.playerId).flatMap(realm.changeAddress(_, c.address)) match
              case Left(_) => refusal(503, "service_unavailable", "the realm did not answer")
              case Right(_) =>
                player(c.playerId).call(PlayerEntity.changeEmail).invoke(Address(c.address, now())): Unit
                ok(Json.obj("status" -> "email_changed"))
        }

  // ── the password ───────────────────────────────────────────────────────────────────────────────

  /** The current password checked with the realm; the new one is set only when the emailed link is followed. */
  def passwordChange(p: Principal, text: String): Reply =
    caller(p) { me =>
      body(text).text("currentPassword") match
        case None => refusal(400, "missing_fields", "currentPassword is required", "fields" -> Json.arr("currentPassword"))
        case Some(password) =>
          val s = stateOf(me)
          passwordHolds(s, password) {
            s.primary.foreach(a => send(me, a.address.reveal, Tokens.Purpose.PasswordChange, "/password-change/verify", Triggers.PasswordChange))
            json(202, Json.obj("status" -> "verification_email_sent"))
          }
    }

  def passwordChangeVerify(text: String): Reply =
    val b = body(text)
    (b.text("token"), b.text("newPassword")) match
      case (Some(t), Some(password)) =>
        claims(t, Tokens.Purpose.PasswordChange) { c =>
          if stateOf(c.playerId).status == Status.Suspended then refusal(409, "account_suspended", "The account is suspended")
          else
            val realm = ctx.neighbours.realm
            realm.userOf(c.playerId).flatMap(realm.setPassword(_, password)) match
              case Left(RealmRefusal.PasswordTooWeak) => refusal(422, "password_too_weak", "The password does not meet the policy")
              case Left(_)                            => refusal(503, "service_unavailable", "the realm did not answer")
              case Right(_)                           => ok(Json.obj("status" -> "password_changed"))
        }
      case _ => refusal(400, "missing_fields", "token and newPassword are required", "fields" -> Json.arr("token", "newPassword"))

  /** Always `200 {status: ok}`, so the route discloses nothing; the realm sends its own reset email. */
  def passwordResetRequest(text: String): Reply =
    body(text).text("email").filter(_.trim.nonEmpty) match
      case None => refusal(400, "missing_fields", "email is required", "fields" -> Json.arr("email"))
      case Some(email) =>
        Lookup.playerOf(components, views, ctx.slug, email).foreach { id =>
          val realm = ctx.neighbours.realm
          realm.userOf(id).flatMap(realm.sendResetEmail) match
            case Left(r) => ProfileRoutes.log.warn("password reset for {}: the realm refused: {}", id, r.code)
            case Right(_) => ()
        }
        ok(Json.obj("status" -> "ok"))

  /** The reset is confirmed on the realm's own page, from the realm's own email; nothing here can confirm one. */
  def passwordResetConfirm(): Reply =
    refusal(400, "invalid_or_expired_token", "The reset is confirmed on the sign-in page the reset email links to")

  // ──────────────────────────────────────────────────────────────────────────────────────────────

  private def passwordHolds(s: PlayerState, password: String)(f: => Reply): Reply =
    s.primary match
      case None => refusal(404, "player_not_found", "No such player")
      case Some(a) =>
        ctx.neighbours.realm.verifyPassword(a.address.reveal, password) match
          case Left(RealmRefusal.InvalidCredentials) => refusal(400, "invalid_password", "The current password is not right")
          case Left(_)                               => refusal(503, "service_unavailable", "the realm did not answer")
          case Right(_)                              => f

  private def claims(token: String, purpose: Tokens.Purpose)(f: Tokens.Claims => Reply): Reply =
    ctx.neighbours.secret(Secrets.EmailToken) match
      case Left(_) => refusal(503, "service_unavailable", "the links are not configured")
      case Right(secret) =>
        Tokens.verify(secret, token, purpose, now()) match
          case Left(Tokens.Refused.Expired)          => refusal(400, "expired_token", "The link has expired")
          case Left(_)                               => refusal(400, "invalid_token", "The link is not valid")
          case Right(c) if c.brand != ctx.slug       => refusal(400, "invalid_token", "The link is not valid")
          case Right(c)                              => f(c)

  /** One link sent through `engagement`; a send not taken is logged, never refused (as registration's, R16). */
  private def send(playerId: String, address: String, purpose: Tokens.Purpose, path: String, trigger: String): Unit =
    val s = ctx.settings
    ctx.neighbours.secret(Secrets.EmailToken) match
      case Left(why) => ProfileRoutes.log.warn("{} link for {}: the link could not be signed: {}", purpose.wire, playerId, why)
      case Right(secret) =>
        val token = Tokens.mint(secret, Tokens.Claims(s.brand, playerId, address, now().plus(s.verificationValidity), purpose))
        val link  = s"${s.playerSiteBase}$path?token=${URLEncoder.encode(token, UTF_8)}"
        ctx.neighbours.engagement.send(UUID.randomUUID().toString, playerId, trigger, address, link, SendBound) match
          case Raced.Answered(_) => ()
          case other             => ProfileRoutes.log.warn("{} link for {}: engagement did not take it: {}", purpose.wire, playerId, other.getClass.getSimpleName)

object ProfileRoutes:
  private[api] val log = org.slf4j.LoggerFactory.getLogger(classOf[ProfileRoutes])

  val PageSize  = 100
  val SendBound: java.time.Duration = java.time.Duration.ofSeconds(2)

  /** The `engagement` triggers of the links this service sends (spec 014). */
  object Triggers:
    val EmailAdd       = "EmailAddressVerification"
    val EmailChange    = "EmailChangeVerification"
    val PasswordChange = "PasswordChangeVerification"

  /** How a command's refusal is answered: the original's statuses. */
  val Statuses: Map[String, Int] = Map(
    "address_not_found" -> 404, "player_not_found" -> 404, "address_already_in_list" -> 409, "kyc_locked" -> 409,
    "already_closed" -> 409, "not_suspended" -> 409, "not_closed" -> 409, "hold_lapsed" -> 409, "not_excluded" -> 409,
    "account_closed" -> 422)

  def answer(d: Done)(f: => Answers.Reply): Answers.Reply =
    d.refusal match
      case Some(r) => Answers.refusal(Statuses.getOrElse(r.code, 400), r.code, r.code.replace('_', ' '), r.details.toSeq*)
      case None    => f

  /** A body's fields as an edit: a string sets; `null` or `""` empties (the entity keeps that to clearable fields). */
  def edits(b: JsonNode, fields: Set[String]): Map[String, Option[String]] =
    fields.toVector.flatMap { f =>
      Option(b.get(f)).collect {
        case n if n.isNull     => f -> None
        case n if n.isTextual  => f -> Option(n.asText).filter(_.trim.nonEmpty)
      }
    }.toMap

  def decode(encoded: String): String = URLDecoder.decode(encoded, UTF_8).trim.toLowerCase(Locale.ROOT)

  def gameIds(ids: Vector[String]): ObjectNode = Json.obj("gameIds" -> Json.arr(ids*))

  /** The player's own view of themselves: every profile field they hold, never a token or a lookup value. */
  def profileOf(s: PlayerState): ObjectNode =
    val o = Json.obj("playerId" -> s.playerId, "status" -> s.status, "email" -> s.primary.map(_.address.reveal).orNull,
      "emailVerified" -> s.primary.exists(_.verified), "currency" -> s.currency,
      "registeredAt" -> s.registeredAt.map(_.toString).orNull, "vipTier" -> s.vipTier.orNull,
      "profileComplete" -> Profile.required.forall(s.profile.has),
      "missingProfileFields" -> Json.arr(Profile.required.filterNot(s.profile.has)*))
    s.erasure.foreach(e => o.put("erasureHoldUntil", e.holdUntil.toString))
    Profile.all.foreach(f => s.profile.get(f).foreach(v => o.put(f, v): Unit))
    o

  def consentsOf(c: Consents): ObjectNode =
    Json.obj("emailMarketingConsent" -> c.email.granted, "smsMarketingConsent" -> c.sms.granted,
      "emailConsentAt" -> c.email.at.map(_.toString).orNull, "smsConsentAt" -> c.sms.at.map(_.toString).orNull,
      "consentSource" -> c.email.source.orElse(c.sms.source).orNull)

  def address(e: EmailAddress): ObjectNode =
    Json.obj("address" -> e.address.reveal, "verified" -> e.verified, "primary" -> e.primary, "addedAt" -> e.addedAt.toString)

  def event(r: ActivityRow): ObjectNode =
    Json.obj("eventId" -> r.factId, "category" -> ActivityCategory.wire(r.category), "eventType" -> r.eventType,
      "timestamp" -> Instant.ofEpochMilli(r.at).toString, "actor" -> r.actor,
      "amountMinorUnits" -> r.amountMinorUnits.map(Long.box).orNull, "currency" -> r.currency.orNull,
      "description" -> r.description, "expandable" -> true)

  def csv(rows: Vector[ActivityRow]): String =
    def cell(s: String) = if s.exists(c => c == ',' || c == '"' || c == '\n') then "\"" + s.replace("\"", "\"\"") + "\"" else s
    val head = "timestamp,category,eventType,actor,amountMinorUnits,currency,description"
    (head +: rows.map(r => Vector(Instant.ofEpochMilli(r.at).toString, ActivityCategory.wire(r.category), r.eventType, r.actor,
      r.amountMinorUnits.fold("")(_.toString), r.currency.getOrElse(""), r.description).map(cell).mkString(","))).mkString("\n") + "\n"

  def message(r: MessageRow, m: io.eitheror.casino.players.inbox.MessageState): ObjectNode =
    Json.obj("notificationId" -> r.messageId, "subject" -> m.subject.reveal, "body" -> m.body.reveal,
      "state" -> (if r.readAt.nonEmpty then "read" else "unread"), "category" -> r.category,
      "createdAt" -> Instant.ofEpochMilli(r.createdAt).toString, "readAt" -> r.readAt.map(Instant.ofEpochMilli(_).toString).orNull,
      "originName" -> (if r.origin == MessageOrigin.Crm then "CRM" else "Operator"))
