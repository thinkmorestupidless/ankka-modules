package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.{OperatorPermissions, Routes}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.keys.RawKeys
import io.eitheror.casino.merchant.operators.{Invitation, InvitationToken, Invitations, IssueInvitation, KeycloakAdmin, Level, OperatorsRuntime, OperatorsView, OperatorRow, OperatorState, Privilege, RealmError, Scope, CreateOperator, ChangeScope, OperatorChange, AskBrandRoles, RejectBrandRoles}
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
    case RealmError.Unreachable(w)   => refuse(503, "KEYCLOAK_UNREACHABLE", w)
    case RealmError.Erring(w)        => refuse(500, "KEYCLOAK_ERROR", w)
    case RealmError.Refused(_, w)    => refuse(500, "KEYCLOAK_ERROR", w)

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

    /**
     * The operators in the caller's scope, newest first, paged (spec 030 H5): `page` from 1, `pageSize`
     * 1 to 200 (25 by default), and `total` however few a page lists. The view orders and pages; only the
     * page's operators are read from their entities, for what the view does not hold (the email, the names).
     */
    get("/") { () =>
      val caller = op
      val page   = request.query.raw("page").flatMap(_.toIntOption).map(n => math.min(1_000_000, math.max(1, n))).getOrElse(1)
      val size   = request.query.raw("pageSize").flatMap(_.toIntOption).filter(n => n >= 1 && n <= 200).getOrElse(25)
      val rows   = OperatorsView.page(ctx.views, caller.scope, (page - 1) * size, size)
      val states = rows.map(r => operator(r.operatorId).call(OperatorEntity.get).invoke()).filter(_.exists)
      ok(Json.obj("operators" -> Json.arr(states.map(summary)*), "total" -> OperatorsView.total(ctx.views, caller.scope),
        "page" -> page, "pageSize" -> size))
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

  /**
   * H6 (spec 030, amended by 032): a brand's `players` gives an operator its roles, by the brand's names
   * without the brand. Admitted to any project's `players` (the brand's grant names it); the handler holds
   * the caller to its own brand. It validates, records the giving as the brand's next and answers `202`
   * with it pending; `BrandRolesApplier` brings the realm to it. Every refusal is an audit record (FR-014).
   * H6r answers the brand's last giving, how far it got, and the realm roles held for the brand now.
   */
  withAcl(BrandRoles.acl) {
    putBody("/{id}/brand-roles/{brand}") { (id: String, brand: String, text: String) =>
      guarded {
        if !KeycloakAdmin.validUserId(id) then
          refuse(400, "INVALID_OPERATOR_ID", "an operator is named by the realm's id: letters, digits, - and _, at most 64")
        else body(text).fold(identity, b => askBrandRoles(id, brand, b.strings("roles").toSet))
      }
    }
    get("/{id}/brand-roles/{brand}") { (id: String, brand: String) =>
      guarded {
        if !KeycloakAdmin.validUserId(id) then
          refuse(400, "INVALID_OPERATOR_ID", "an operator is named by the realm's id: letters, digits, - and _, at most 64")
        else callerProject.filter(_ != brand) match
          case Some(project) => refuse(403, "FORBIDDEN", s"$project reads the roles of its own brand only")
          case None          => ok(BrandRoles.answer(brand, operator(id).call(OperatorEntity.get).invoke().givings.get(brand), held(id, brand)))
      }
    }
  }

  /** The project a calling `players` belongs to; none for a local run. */
  private def callerProject: Option[String] = caller match
    case com.thinkmorestupidless.ankka.http.Caller.Service(project, _) => Some(project)
    case _                                                             => None

  /** The realm roles named for `brand` the operator holds now, without the brand; none when the realm cannot be read. */
  private def held(id: String, brand: String): Option[Set[String]] =
    realm.realmRoles(id) match
      case Right(roles) => Some(io.eitheror.casino.contract.access.BrandRoles.heldIn(brand, roles))
      case Left(e) =>
        log.warn("the realm roles of {} could not be read for {}: {}", id, brand, e)
        None

  private def askBrandRoles(id: String, brand: String, asked: Set[String]): Respond[Bytes] =
    val refusal = callerProject.filter(_ != brand) match
      case Some(project) => Some((project, refuse(403, "FORBIDDEN", s"$project sets the roles of its own brand only"), "FORBIDDEN"))
      case None => BrandRoles.refused(brand, asked).map { (code, role) =>
          val message = if code == BrandRoles.NamedForBrand then s"$role names a brand: a giving names roles without the brand"
                        else s"$role is not a role a brand gives"
          (brand, refuse(400, code, message, "role" -> role), code)
        }
    refusal match
      case Some((by, answer, code)) =>
        // the entity records it when the operator exists (FR-014); a refusal naming no operator is only logged
        val s = operator(id).call(OperatorEntity.rejectBrandRoles).invoke(RejectBrandRoles(brand, by, asked, code, ctx.now()))
        if !s.exists then log.info("a giving by {} for no operator {} was refused: {}", by, id, code)
        answer
      case None =>
        val giving = operator(id).call(OperatorEntity.askBrandRoles).invoke(AskBrandRoles(brand, asked - s"brand_$brand", ctx.now()))
        Answers.json(202, BrandRoles.answer(brand, Some(giving), None))

object BrandRoles:
  /** Any project's `players`, or a local run. */
  val acl: Acl = Acl.AllowIf(r => r.caller match
    case Caller.Service(_, "players") | Caller.Local => true
    case _                                           => false)

  /** The refusal for a role a giving names with a brand (`brand_admin@goldvibe`): a giving names roles without it. */
  val NamedForBrand = "role_named_for_brand"

  /**
   * The first role `brand` may not give, and why: one named for a brand; not a role's name; or reserved
   * (every realm role but the default roles, `platform_admin`, a payments role, another brand's
   * `brand_<slug>`), but its own `brand_<slug>`. `api_keys` and `markets` are a brand's to give (FR-012).
   */
  def refused(brand: String, asked: Set[String]): Option[(String, String)] =
    asked.toVector.sorted.collectFirst {
      case r if r.contains(io.eitheror.casino.contract.access.BrandRoles.Separator) => (NamedForBrand, r)
      case r if r != s"brand_$brand" && !io.eitheror.casino.contract.access.BrandRoles.Given(r) && (!OperatorPermissions.validRoleName(r) || OperatorPermissions.reserved(r)) =>
        ("RESERVED_ROLE", r)
    }

  /** A giving as H6 and H6r answer it: `{roles, version, state, reason?, at}`, and `held` when it was read. */
  def answer(brand: String, giving: Option[io.eitheror.casino.merchant.operators.Giving], held: Option[Set[String]]): com.fasterxml.jackson.databind.node.ObjectNode =
    import io.eitheror.casino.merchant.operators.GivingState
    val o = Json.obj("brand" -> brand)
    giving.foreach { g =>
      o.set("roles", Json.arr(g.roles.toVector.sorted*)): Unit
      o.put("version", g.version)
      o.put("state", g.state match
        case GivingState.Pending   => "pending"
        case GivingState.InForce   => "in_force"
        case GivingState.Failed(_) => "failed")
      g.state match
        case GivingState.Failed(reason) => o.put("reason", reason): Unit
        case _                          => ()
      o.put("at", g.at.toString)
    }
    held.foreach(h => o.set("held", Json.arr(h.toVector.sorted*)): Unit)
    o
