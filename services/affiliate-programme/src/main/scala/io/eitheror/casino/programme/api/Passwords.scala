package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.core.CommandError
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.comms.{EmailSend, Notice, Notices, SendEmail, TokenLink}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

/** The password routes' shapes (P5, P6, O16, O17), the original's. */
object PasswordAnswers:
  final case class ForgotBody(email: Option[String] = None)
  final case class AcceptBody(token: Option[String] = None, password: Option[String] = None, mode: Option[String] = None,
      brandId: Option[String] = None)
  final case class Accepted(status: String)

  given forgotBody: JsonValueCodec[ForgotBody] = ProgrammeCodecs.wire[ForgotBody]
  given acceptBody: JsonValueCodec[AcceptBody] = ProgrammeCodecs.wire[AcceptBody]
  given accepted: JsonValueCodec[Accepted]     = ProgrammeCodecs.wire[Accepted]

  /** As the original's: an `@` between two parts, a dot after it, no spaces. */
  val EmailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

import PasswordAnswers.{*, given}

/**
 * Setting a password (research R11): the forgotten-password request, the invitation and reset an
 * operator resends, and the one route every token is claimed at. An affiliate's token names the account
 * and its realm user is the account's; a manager's names the realm user (O2, O3).
 */
final class Passwords(api: Api):
  /**
   * P5: always `{ok: true}`, so the caller learns nothing of who is registered; a second request for the
   * same address within five minutes sends nothing, as the original.
   */
  def forgot(brand: String, b: ForgotBody): Respond[Bytes] =
    val email = b.email.map(_.trim).getOrElse("")
    if email.isEmpty then refuse(400, "email_required")
    else if !EmailPattern.matches(email) then refuse(400, "email_invalid")
    else
      if Passwords.firstWithin(email.toLowerCase, api.now()) then
        try
          Enrolment(api).lookup(email).toOption.flatMap(_._2).foreach { aff =>
            val account = api.account(aff)
            if account.closed.isEmpty && account.erased.isEmpty then
              send(aff, brand, Token.Reset, "AffiliatePasswordReset", "reset-password")
              tell(aff, brand, "PASSWORD_RESET_REQUESTED", "A password reset was requested for your account.")
          }
        catch case NonFatal(_) => () // the answer is the same whatever happened
      ok(Answers.Ok(true))

  /** P6: the token claimed once; given back when the password could not be set, so it can be tried again. */
  def accept(b: AcceptBody): Respond[Bytes] =
    (b.token.filter(_.nonEmpty), b.password.filter(_.nonEmpty)) match
      case (Some(token), Some(password)) =>
        val entity = api.components.forKeyValueEntity(Token.idOf(token))
        val claimed =
          try Right(entity.call(Token.claim).invoke(api.now()))
          catch case e: CommandError => Left(e)
        claimed match
          case Left(_) => refuse(400, Token.Invalid)
          case Right(t) =>
            def release(r: Respond[Bytes]): Respond[Bytes] = { entity.call(Token.release).invoke(): Unit; r }
            val manager = t.purpose == Token.ManagerInvite || t.purpose == Token.ManagerReset
            val user    = if manager then Some(t.user) else api.account(t.user).keycloakUser
            user match
              case None => release(refuse(503, "substrate_unavailable", "the account has no realm user yet"))
              case Some(u) =>
                api.realm.resetPassword(u, password) match
                  case Left(RealmError.NotFound(_)) => refuse(404, "not_found")
                  case Left(e: RealmError.NotConfigured) => release(refuse(503, "substrate_unavailable", e.toString))
                  case Left(e)                      => release(refuse(500, "internal_error", e.toString))
                  case Right(_) =>
                    // The original's security notice, after a reset the affiliate asked for only.
                    if !manager && b.mode.contains("reset") then
                      b.brandId.orElse(t.brand).foreach { brand =>
                        api.components.forWorkflow(EntityId(s"changed-${Token.idOf(token)}")).call(EmailSend.start)
                          .invoke(SendEmail(t.user, brand, "AffiliatePasswordChanged", Map.empty)): Unit
                      }
                    ok(Accepted("accepted"))
      case _ => refuse(400, "token and password are required")

  /** O16: a fresh invitation for an active member; the old token stays good until it expires, as the original. */
  def resendInvitation(brand: String, aff: String, by: String): Respond[Bytes] =
    if !api.membership(aff, brand).active then refuse(409, "affiliate_not_active")
    else withUser(aff) { _ =>
      Networks(api).signIn(aff, brand)
      Networks(api).audit(aff, Some(brand), "INVITATION_RESENT", by)
      Respond(Bytes("application/json", Array.emptyByteArray), 204)
    }

  /** O17: a reset link to the portal, with no guard on the membership's status: an affiliate who cannot sign in needs one most. */
  def reset(brand: String, aff: String, by: String): Respond[Bytes] =
    withUser(aff) { _ =>
      send(aff, brand, Token.Reset, "AffiliatePasswordReset", "reset-password")
      tell(aff, brand, "PASSWORD_RESET_REQUESTED", "A password reset was requested for your account.")
      Networks(api).audit(aff, Some(brand), "PASSWORD_RESET_SENT", by)
      Respond(Bytes("application/json", Array.emptyByteArray), 204)
    }

  private def withUser(aff: String)(f: String => Respond[Bytes]): Respond[Bytes] =
    val a = api.account(aff)
    if !a.exists then refuse(404, "not_found")
    else a.keycloakUser.fold(refuse(409, "no_keycloak_account"))(f)

  private def send(aff: String, brand: String, purpose: String, kind: String, page: String): Unit =
    val link = TokenLink(purpose, aff, brand, api.settings.portalBaseUrl, page, Seq("invitation_link", "link"))
    api.components.forWorkflow(EmailSend.newId(purpose)).call(EmailSend.start)
      .invoke(SendEmail(aff, brand, kind, Map("user_id" -> aff), link = Some(link))): Unit

  private def tell(aff: String, brand: String, kind: String, body: String): Unit =
    val at = api.now()
    Notices.tell(api.components, java.util.UUID.randomUUID().toString, Notice(aff, brand, kind, body, None), at)

object Passwords:
  private val Window = java.time.Duration.ofMinutes(5)
  private val recent = ConcurrentHashMap[String, Instant]()

  /** True for the first request for an address in five minutes; held in the process, as the original's was. */
  def firstWithin(key: String, now: Instant): Boolean =
    recent.entrySet().removeIf(e => !now.isBefore(e.getValue.plus(Window)))
    recent.putIfAbsent(key, now) == null
