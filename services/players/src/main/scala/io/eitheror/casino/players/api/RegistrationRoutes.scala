package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.{Background, Json, PlayersRuntime, Secrets}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.{Bound, Geo, Raced}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.realm.Tokens
import io.eitheror.casino.players.registration.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future

import Answers.*

/**
 * The public registration routes (FR-001 to FR-006; contracts/routes.md) and the operator's
 * verification link (R16). `register` claims the address's `RegistrationWorkflow`, makes the realm
 * call when it holds the attempt (the password reaches no state), and answers from the workflow's
 * outcome within `registrationBound`, else `503 service_unavailable`.
 */
final class RegistrationRoutes(ctx: Ctx):
  private def now(): Instant = PlayersRuntime.now()
  private def components    = ctx.clients.componentClient
  private def flow(id: EntityId) = components.forWorkflow(id)

  // ── POST /register ─────────────────────────────────────────────────────────────────────────────

  def register(text: String, header: String => Option[String]): Reply =
    val body = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())
    val email    = body.text("email")
    val password = Option(body.get("password")).filter(_.isTextual).map(_.asText).filter(_.nonEmpty)
    val currency = body.text("currency")
    if email.isEmpty || password.isEmpty || currency.isEmpty then
      refusal(400, "missing_fields", "email, password, and currency are required",
        "fields" -> Json.arr("email", "password", "currency"))
    else
      val started = System.nanoTime()
      val at      = now()
      val request = RegistrationRoutes.request(body, email.get, currency.get, at, header, ctx.neighbours.geo)
      val id      = RegistrationWorkflow.idOf(ctx.slug, email.get)
      val claim   = flow(id).call(RegistrationWorkflow.start).invoke(Begin(UUID.randomUUID().toString, at))
      claim.refusal match
        case Some(code) => RegistrationRoutes.answer(Outcome(refusal = Some(code)))
        case None =>
          if claim.claimed then realm(id, claim.flow, email.get, password.get, request)
          await(id, started)

  /** Holds the request, asks the realm, and tells the workflow what it answered; a user it did not take is removed. */
  private def realm(id: EntityId, f: RegistrationFlow, email: String, password: String, request: RegistrationRequest): Unit =
    components.forKeyValueEntity(EntityId(id.toString)).call(PendingRegistration.put).invoke(request): Unit
    val realm  = ctx.neighbours.realm
    val bound  = ctx.settings.registrationBound.multipliedBy(3).dividedBy(4)
    val answer = Bound.within(bound)(Future(realm.createUser(email, password, f.playerId))(using Background.ec)) match
      case Raced.Answered(Right(user))  => RealmAnswer(f.attempt, userId = Some(user))
      case Raced.Answered(Left(refusal)) => RealmAnswer(f.attempt, refusal = Some(refusal.code))
      case Raced.Unanswered(late) =>
        // Heard after the bound: a user made then is removed, as the workflow is undoing this attempt.
        late.foreach(_.foreach(u => realm.deleteUser(u): Unit))(using Background.ec)
        RealmAnswer(f.attempt, refusal = Some("realm_unanswered"))
      case Raced.Failed(_) => RealmAnswer(f.attempt, refusal = Some("realm_failed"))
    val taken = flow(id).call(RegistrationWorkflow.realmAnswered).invoke(answer)
    if !taken.taken then answer.userId.foreach(u => realm.deleteUser(u): Unit)

  /** The outcome, polled until it is held or the bound from the request's arrival passes. */
  private def await(id: EntityId, started: Long): Reply =
    val deadline = started + ctx.settings.registrationBound.toNanos
    def poll(): Reply =
      val f = flow(id).call(RegistrationWorkflow.status).invoke()
      f.outcome match
        case Some(o) => RegistrationRoutes.answer(o)
        case None if System.nanoTime() < deadline =>
          Thread.sleep(20)
          poll()
        case None => refusal(503, "service_unavailable", "the registration did not complete in time")
    poll()

  // ── POST /verify-email ─────────────────────────────────────────────────────────────────────────

  def verifyEmail(text: String): Reply =
    val token = Json.parse(text).flatMap(_.text("token"))
    token match
      case None => refusal(400, "missing_token", "token is required")
      case Some(t) =>
        ctx.neighbours.secret(Secrets.EmailToken) match
          case Left(_) => refusal(503, "service_unavailable", "verification is not configured")
          case Right(secret) =>
            Tokens.verify(secret, t, Tokens.Purpose.EmailVerify, now()) match
              case Left(_) => refusal(400, "invalid_or_expired_token", "the link is not valid or has expired")
              case Right(claims) if claims.brand != ctx.slug => refusal(400, "invalid_or_expired_token", "the link is not valid or has expired")
              case Right(claims) =>
                val realm = ctx.neighbours.realm
                realm.userOf(claims.playerId).flatMap(realm.setVerified) match
                  case Left(_) => refusal(503, "service_unavailable", "the realm did not answer")
                  case Right(_) =>
                    components.forEventSourcedEntity(PlayerEntity.id(claims.playerId)).call(PlayerEntity.verifyEmail).invoke(At(now())): Unit
                    ok(Json.obj("status" -> "verified"))

  // ── POST /resend-verification-email ───────────────────────────────────────────────────────────

  /** 200 whether or not the address is a player's, so the route discloses nothing (the original's). */
  def resend(text: String): Reply =
    Json.parse(text).flatMap(_.text("email")) match
      case None => refusal(400, "missing_fields", "email is required", "fields" -> Json.arr("email"))
      case Some(email) =>
        val f   = flow(RegistrationWorkflow.idOf(ctx.slug, email)).call(RegistrationWorkflow.status).invoke()
        val at  = now()
        val sent = ok(Json.obj("result" -> "sent"))
        f.outcome.flatMap(_.playerId) match
          case None => sent
          case Some(playerId) =>
            val done = components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.resendVerification)
              .invoke(Resend(at, ctx.settings.verificationResendInterval))
            done.refusal match
              case Some(Refusal("rate_limited", d)) =>
                refusal(429, "rate_limited", "a verification email was sent recently",
                  "retry_after_seconds" -> d.get("retry_after_seconds").flatMap(_.toLongOption).getOrElse(300L))
              case Some(_) => sent
              case None =>
                RegistrationWorkflow.sendVerification(ctx.neighbours, playerId, email, s"resend:$playerId:${at.toEpochMilli}", at): Unit
                sent

  // ── GET /backoffice/<slug>/players/{playerId}/verification-link ───────────────────────────────

  /** The current link, on a brand that takes no real money only (R16). */
  def verificationLink(playerId: String): Reply =
    if ctx.settings.realMoney then refusal(404, "not_found", "not served on a real-money brand")
    else
      val p = components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
      p.primary.filter(_ => p.exists) match
        case None => refusal(404, "player_not_found", "no such player")
        case Some(primary) =>
          RegistrationWorkflow.link(ctx.neighbours, playerId, primary.address.reveal, now()) match
            case Left(_)     => refusal(503, "service_unavailable", "verification is not configured")
            case Right(link) => ok(Json.obj("playerId" -> playerId, "status" -> p.status, "link" -> link))

object RegistrationRoutes:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[RegistrationRoutes])

  /** The original's tag grammar (FR-003), `nan` and `inf` excepted as it does. */
  private val TagPattern        = "^[A-Za-z0-9_.:-]{1,128}$".r
  private val NonFiniteSpelling = "(?i)^[+-]?(nan|inf|infinity)$".r

  def validTag(tag: String): Boolean = TagPattern.matches(tag) && !NonFiniteSpelling.matches(tag)

  /** A field at the top of the body, else under `trackingContext` (the original's `registrationBtag`). */
  private def tracked(body: JsonNode, field: String): Option[String] =
    body.text(field).orElse(Option(body.get("trackingContext")).filter(_.isObject).flatMap(_.text(field)))

  /** The request the workflow holds: the profile, the consents with their time and source, what registration presented. */
  def request(body: JsonNode, email: String, currency: String, at: Instant, header: String => Option[String], geo: Geo): RegistrationRequest =
    val profile = Profile(Vector("displayName", "firstName", "lastName", "country", "language", "dateOfBirth")
      .flatMap(f => body.text(f).map(f -> Personal(_))).toMap)
    def consent(field: String) = body.bool(field).fold(Consent())(g => Consent(g, Option.when(g)(at), Some("registration")))
    val tag = tracked(body, "btag").filter { t =>
      val valid = validTag(t)
      if !valid then log.warn(s"dropping a tag that is not well formed on registration (length ${t.length})")
      valid
    }
    val network = Geo.callerAddress(header)
    val country = network.flatMap(a => geo.lookup(Some(a)).country)
    RegistrationRequest(email.trim, currency.trim.toUpperCase, profile,
      Consents(consent("emailMarketingConsent"), consent("smsMarketingConsent")),
      Registration(tag, tracked(body, "clickId"), body.text("bonusCode"), network.map(Personal(_)), country,
        body.text("deviceFingerprint").map(Personal(_))),
      at)

  def answer(o: Outcome): Reply = (o.playerId, o.refusal) match
    case (Some(p), _) => json(201, Json.obj("playerId" -> p, "status" -> o.status.getOrElse(Status.Pending)))
    case (_, Some(RealmRefusalCodes.EmailExists)) => refusal(409, "email_already_exists", "the address is already registered")
    case (_, Some("password_too_weak"))           => refusal(422, "password_too_weak", "the password does not meet the brand's policy")
    case (_, Some("brand_not_found"))             => refusal(404, "brand_not_found", "the brand has no realm")
    case _                                         => refusal(503, "service_unavailable", "the registration could not be completed")
