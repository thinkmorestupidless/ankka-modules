package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.{Audit, AuditEntry}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.comms.{EmailSend, SendEmail}

/** The manager routes' shapes (O1 to O3), the original's. */
object ManagerAnswers:
  final case class ManagerBody(email: Option[String] = None, name: Option[String] = None)
  final case class ManagerAnswer(keycloakUserId: String, email: String, name: String, pending: Boolean)

  given managerBody: JsonValueCodec[ManagerBody]             = ProgrammeCodecs.wire[ManagerBody]
  given managerAnswer: JsonValueCodec[ManagerAnswer]         = ProgrammeCodecs.wire[ManagerAnswer]
  given managerAnswers: JsonValueCodec[Vector[ManagerAnswer]] = ProgrammeCodecs.wire[Vector[ManagerAnswer]]

import ManagerAnswers.{*, given}

/**
 * A brand's programme managers (FR-031): users of the `eitheror` realm holding `affiliate_manager` and
 * `brand_<slug>`, as the original provisions them. A new manager, and a reset, get a token and the
 * brand's email for it (`AffiliateManagerInvite`, `AffiliateManagerPasswordReset`); revoking takes the
 * brand's role, and the manager role with it when no other brand's role is left.
 */
final class Managers(api: Api):
  private val Role = "affiliate_manager"
  private def brandRole(brand: String) = s"brand_$brand"

  private def unavailable(e: RealmError): Respond[Bytes] = refuse(503, "substrate_unavailable", e.toString)

  /** The users holding both roles. */
  private def of(brand: String): Either[RealmError, Vector[RealmUser]] =
    for
      managers <- api.realm.usersWithRole(Role)
      atBrand  <- api.realm.usersWithRole(brandRole(brand))
    yield
      val ids = atBrand.map(_.id).toSet
      managers.filter(u => ids(u.id))

  def list(brand: String): Respond[Bytes] =
    of(brand).fold(unavailable, us => ok(us.map(u => ManagerAnswer(u.id, u.email, u.name, u.pending))))

  def add(brand: String, b: ManagerBody, by: String): Respond[Bytes] =
    Enrolment(api).address(b.email) match
      case None => invalid("email is required")
      case Some(email) =>
        val (first, last) = KeycloakAdmin.names(b.name)
        // A manager's authority is its two roles; it names no account (no `eitheror_scope_id`), as the original.
        api.realm.createUser(email, first, last, Map("eitheror_actor_type" -> "affiliate", "eitheror_scope_level" -> "affiliate")) match
          case Left(RealmError.AlreadyExists(_)) => refuse(409, "user_already_exists")
          case Left(e)                           => unavailable(e)
          case Right(user) =>
            api.realm.assignRole(user, Role).flatMap(_ => api.realm.assignRole(user, brandRole(brand))) match
              case Left(e) => unavailable(e)
              case Right(_) =>
                send(brand, user, email, Token.ManagerInvite, "AffiliateManagerInvite", "affiliates/accept-invitation")
                audit(brand, "MANAGER_ADDED", by, user)
                answer(201, ManagerAnswer(user, email, b.name.filter(_.trim.nonEmpty).getOrElse(email.takeWhile(_ != '@')), pending = true))

  /** O3: `204`; a user who is not a manager of the brand is not found. */
  def reset(brand: String, user: String, by: String): Respond[Bytes] =
    of(brand).fold(unavailable, _.find(_.id == user) match
      case None => refuse(404, "not_found")
      case Some(u) =>
        send(brand, u.id, u.email, Token.ManagerReset, "AffiliateManagerPasswordReset", "affiliates/reset-password")
        audit(brand, "MANAGER_PASSWORD_RESET", by, u.id)
        noContent)

  /** Change 16, as decided 2026-11-02: the manager role stays while the user still holds another brand's role. */
  def revoke(brand: String, user: String, by: String): Respond[Bytes] =
    of(brand).fold(unavailable, _.find(_.id == user) match
      case None => refuse(404, "not_found")
      case Some(_) =>
        val done = for
          _     <- api.realm.removeRole(user, brandRole(brand))
          roles <- api.realm.rolesOf(user)
          _     <- if roles.exists(_.startsWith("brand_")) then Right(()) else api.realm.removeRole(user, Role)
        yield ()
        done.fold(unavailable, { _ =>
          audit(brand, "MANAGER_REVOKED", by, user)
          noContent
        }))

  private def send(brand: String, user: String, email: String, purpose: String, kind: String, page: String): Unit =
    val token = Token.mint()
    api.components.forKeyValueEntity(Token.idOf(token)).call(Token.issue)
      .invoke(IssueToken(user, purpose, Some(brand), api.now().plus(Token.life(purpose)))): Unit
    val link = s"${api.settings.backofficeBaseUrl}/$page?token=$token"
    api.components.forWorkflow(EntityId(s"$purpose-${Token.idOf(token)}")).call(EmailSend.start)
      .invoke(SendEmail(user, brand, kind, Map("invitation_link" -> link, "user_id" -> user), Some(email))): Unit

  private def audit(brand: String, action: String, by: String, user: String): Unit =
    api.components.forKeyValueEntity(EntityId(AuditEntry.newId())).call(AuditEntry.write)
      .invoke(Audit("", Some(brand), action, by, api.now(), details = Map("userId" -> user))): Unit
