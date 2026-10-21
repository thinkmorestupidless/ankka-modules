package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.keys.RawKeys
import io.eitheror.casino.merchant.operators.{Invitation, InvitationToken, Invitations, IssueInvitation, KeycloakAdmin, Level, OperatorsRuntime, OperatorsView, OperatorRow, OperatorState, Privilege, RealmError, Scope, CreateOperator, ChangeScope, OperatorChange}
import io.eitheror.casino.merchant.operators.Operator as OperatorEntity

/**
 * `/v1/operators` (FR-027, FR-028; the original's `OperatorRoutes`): operators of the payments
 * backoffice provisioned into the `eitheror` realm with a role and a level, invited, re-invited, sent
 * a password reset, moved, removed; `accept-invitation` is public, admitted by the invitation's token.
 */
final class OperatorsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/operators"):
  val acl: Acl = Acl.DenyAll
  private def op = io.eitheror.casino.merchant.api.Operator.of(principal)
  private def realm       = OperatorsRuntime.keycloak(ctx.live, ctx.clients.secrets)
  private def invitations = OperatorsRuntime.invitations(ctx.live, ctx.clients.secrets)
  private def operator(id: String) = ctx.components.forEventSourcedEntity(EntityId(id))
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[OperatorsEndpoint])

  private def realmFailure(e: RealmError): Respond[Bytes] = e match
    case RealmError.AlreadyExists(_) => refuse(409, "USER_ALREADY_EXISTS")
    case RealmError.NotFound(_)      => refuse(404, "OPERATOR_NOT_FOUND")
    case RealmError.NotConfigured(w) => refuse(503, "KEYCLOAK_NOT_CONFIGURED", w)
    case RealmError.Failed(w)        => refuse(500, "KEYCLOAK_ERROR", w)

  /** The entity's parents an operator placed at it records (the original's `resolveAncestors`). */
  private def ancestors(level: Level, id: String): Either[Respond[Bytes], Vector[String]] = level match
    case Level.Platform | Level.Holding => Right(Vector.empty)
    case Level.Company =>
      val c = ctx.company(id)
      if c.exists then Right(c.parentId.toVector) else Left(refuse(400, "COMPANY_NOT_FOUND"))
    case Level.SubBrand =>
      val s = ctx.subBrand(id)
      if s.exists then Right(Vector(s.holdingId, s.companyId).filter(_.nonEmpty)) else Left(refuse(400, "SUB_BRAND_NOT_FOUND"))

  private def invite(kind: Invitation.Kind, operatorId: String, email: String): Unit =
    val token = RawKeys.mint().stripPrefix("mk_")
    ctx.components.forKeyValueEntity(EntityId(RawKeys.digest(token))).call(InvitationToken.issue)
      .invoke(IssueInvitation(operatorId, Personal(email), ctx.now())): Unit
    invitations.send(Invitation(kind, operatorId, email, token, Invitations.link(ctx.live.operatorConsoleUrl, kind, token)))
      .left.foreach(why => log.warn(s"$kind for $operatorId was not sent: $why"))

  private def summary(s: OperatorState): com.fasterxml.jackson.databind.node.ObjectNode =
    val email = s.email.map(_.reveal).getOrElse("")
    Json.obj("operatorId" -> s.id, "email" -> email, "username" -> email, "enabled" -> s.enabled, "roles" -> Vector(s.role),
      "scopeId" -> s.scopeId, "scopeLevel" -> s.scopeLevel, "scopeAncestors" -> s.ancestors, "pending" -> s.pending,
      "firstName" -> s.firstName.map(_.reveal), "lastName" -> s.lastName.map(_.reveal))

  withAcl(Routes.public) {
    /** The invited operator sets a password with the invitation's token (no token of the realm yet). */
    postBody("/accept-invitation") { (text: String) =>
      guarded {
        body(text).fold(identity, b =>
          (b.text("token"), b.text("password")) match
            case (Some(token), Some(password)) =>
              ctx.components.forKeyValueEntity(EntityId(RawKeys.digest(token))).call(InvitationToken.consume).invoke(ctx.now()) match
                case None => refuse(400, "invalid_or_expired_token")
                case Some(id) =>
                  realm.resetPassword(id, password).fold(realmFailure, _ =>
                    realm.setEmailVerified(id).left.foreach(e => log.warn(s"email verified not set for $id: $e"))
                    operator(id).call(OperatorEntity.accept).invoke(ctx.now()): Unit
                    ok(Json.obj("status" -> "accepted")))
            case _ => refuse(400, "token and password are required"))
      }
    }
  }

  withAcl(Routes.paymentsOperator(ctx.authenticate())) {
    postBody("/") { (text: String) =>
      guarded {
        val caller = op
        body(text).fold(identity, b =>
          val role  = b.textOr("role", "")
          val email = b.textOr("email", "")
          Level.from(b.textOr("scopeLevel", "")) match
            case _ if !Routes.PaymentsRoles.contains(role)          => refuse(400, "UNKNOWN_ROLE")
            case _ if !Privilege.canAssignRole(caller.roles, role)  => refuse(403, "PRIVILEGE_ESCALATION")
            case None                                               => refuse(400, "UNKNOWN_SCOPE_LEVEL")
            case _ if email.isEmpty                                 => refuse(400, "INVALID_REQUEST", "email is required")
            case Some(level) =>
              val scopeId = b.textOr("scopeId", "")
              ancestors(level, scopeId).fold(identity, anc =>
                if !Privilege.canAssignScope(caller.scope, Scope(level, scopeId), anc.toSet) then refuse(403, "SCOPE_ESCALATION")
                else
                  val first = b.text("firstName"); val last = b.text("lastName")
                  val made = for
                    id <- realm.createUser(email, first, last)
                    _  <- realm.assignRole(id, role)
                    _  <- realm.setScope(id, scopeId, level.claim, anc)
                  yield id
                  made.fold(realmFailure, id =>
                    operator(id).call(OperatorEntity.create).invoke(CreateOperator(Personal(email), role, level.claim, scopeId, anc,
                      first.map(Personal(_)), last.map(Personal(_)), caller.actor, ctx.now())): Unit
                    invite(Invitation.Kind.Invite, id, email)
                    created(Json.obj("operatorId" -> id, "email" -> email, "role" -> role, "scopeId" -> scopeId, "scopeLevel" -> level.claim)))))
      }
    }

    get("/") { () =>
      val caller = op
      val rows   = ctx.views.forView(OperatorsView).all(10_000).filter(r => OperatorsView.inScope(caller.scope, r))
      ok(Json.obj("operators" -> Json.arr(rows.map(r => summary(operator(r.operatorId).call(OperatorEntity.get).invoke()))*)))
    }

    /** The caller covers the operator: the platform any, otherwise one at or under the caller's entity. */
    def covered(id: String)(f: OperatorState => Respond[Bytes]): Respond[Bytes] = guarded {
      val s = operator(id).call(OperatorEntity.get).invoke()
      if !s.exists then refuse(404, "OPERATOR_NOT_FOUND")
      else if !OperatorsView.inScope(op.scope, OperatorRow(s.id, s.role, s.scopeLevel, s.scopeId, s.ancestors, s.pending, s.enabled)) then scopeViolation
      else f(s)
    }

    post("/{id}/resend-invitation") { (id: String) =>
      covered(id) { s =>
        invite(Invitation.Kind.Resend, id, s.email.map(_.reveal).getOrElse(""))
        operator(id).call(OperatorEntity.invited).invoke(OperatorChange(op.actor, ctx.now())): Unit
        ok(Json.obj("status" -> "sent"))
      }
    }

    post("/{id}/reset-password") { (id: String) =>
      covered(id) { s =>
        invite(Invitation.Kind.PasswordReset, id, s.email.map(_.reveal).getOrElse(""))
        ok(Json.obj("status" -> "sent"))
      }
    }

    def rescope(id: String, text: String): Respond[Bytes] =
      covered(id) { _ =>
        body(text).fold(identity, b =>
          Level.from(b.textOr("scopeLevel", "")) match
            case None => refuse(400, "UNKNOWN_SCOPE_LEVEL")
            case Some(level) =>
              val scopeId = b.textOr("scopeId", "")
              ancestors(level, scopeId).fold(identity, anc =>
                if !Privilege.canAssignScope(op.scope, Scope(level, scopeId), anc.toSet) then refuse(403, "SCOPE_ESCALATION")
                else
                  realm.setScope(id, scopeId, level.claim, anc).fold(realmFailure, _ =>
                    operator(id).call(OperatorEntity.changeScope).invoke(ChangeScope(level.claim, scopeId, anc, op.actor, ctx.now())): Unit
                    ok(Json.obj("operatorId" -> id, "scopeId" -> scopeId, "scopeLevel" -> level.claim)))))
      }
    patchBody("/{id}/scope") { (id: String, text: String) => rescope(id, text) }
    putBody("/{id}/scope") { (id: String, text: String) => rescope(id, text) }

    delete("/{id}") { (id: String) =>
      covered(id) { _ =>
        realm.deleteUser(id) match
          case Left(e) if !e.isInstanceOf[RealmError.NotFound] => realmFailure(e)
          case _ =>
            operator(id).call(OperatorEntity.remove).invoke(OperatorChange(op.actor, ctx.now())): Unit
            noContent
      }
    }
  }
