package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.operators.Invitation

import scala.jdk.CollectionConverters.*

/** FR-027, FR-028 through `/v1/operators` against the fake realm (the original's operator route cases). */
class OperatorsSuite extends SharedKitSuite:

  private def create(as: Seq[(String, String)], email: String, role: String, level: String, scope: String): Reply =
    kit.request("POST", "/v1/operators", Some(Json.render(Json.obj("email" -> email, "role" -> role, "scopeLevel" -> level, "scopeId" -> scope))), as)

  private def email = s"op-${java.util.UUID.randomUUID().toString.take(8)}@acme.example"

  test("an operator is created in the realm with role, level and ancestors, pending its email's verification, and sent an invitation") {
    val r = kit.subBrand()
    val e = email
    val c = create(kit.asOperator(), e, "resource_admin", "company", r.companyId)
    assertEquals(c.status, 201, c.text)
    val id = c.str("operatorId")
    val u  = kit.keycloak.user(id).get
    assertEquals((u.roles.toSet, kit.keycloak.attribute(id, "eitheror_scope_level"), kit.keycloak.attribute(id, "eitheror_scope_ancestors")),
      (Set("resource_admin"), Vector("company"), Vector(r.holdingId)))
    assertEquals((u.emailVerified, u.requiredActions), (false, Vector("VERIFY_EMAIL")))
    val invite = kit.invitations.to(e).head
    assertEquals((invite.kind, invite.link), (Invitation.Kind.Invite, s"https://payments.example/accept-invitation?token=${invite.token}"))
    val listed = kit.eventually("listed")(Option(kit.request("GET", "/v1/operators", None, kit.asOperator())).filter(_.text.contains(id)))
    val row = listed.json.get("operators").elements.asScala.find(_.textOr("operatorId", "") == id).get
    assertEquals((row.bool("pending"), row.textOr("email", "")), (Some(true), e))
  }

  test("accepting the invitation sets the password once; a second accept with the same token is refused") {
    val r  = kit.subBrand()
    val e  = email
    val id = create(kit.asOperator(), e, "resource_viewer", "sub_brand", r.subBrandId).str("operatorId")
    val token = kit.invitations.to(e).head.token
    val accept = kit.request("POST", "/v1/operators/accept-invitation", Some(s"""{"token":"$token","password":"S3cret!pw"}"""), kit.asBrowser)
    assertEquals((accept.status, accept.str("status")), (200, "accepted"))
    val u = kit.keycloak.user(id).get
    assertEquals((u.password, u.emailVerified, u.requiredActions), (Some("S3cret!pw"), true, Vector.empty))
    assertEquals(kit.request("POST", "/v1/operators/accept-invitation", Some(s"""{"token":"$token","password":"x"}"""), kit.asBrowser).str("error"),
      "invalid_or_expired_token")
  }

  test("a company operator cannot create above their level or with more privilege; within both they can") {
    val r  = kit.subBrand()
    val as = kit.asOperator(level = "company", scopeId = r.companyId, roles = "resource_admin")
    assertEquals(create(as, email, "resource_viewer", "holding", r.holdingId).str("error"), "SCOPE_ESCALATION")
    assertEquals(create(as, email, "resource_owner", "sub_brand", r.subBrandId).str("error"), "PRIVILEGE_ESCALATION")
    assertEquals(create(as, email, "resource_admin", "sub_brand", r.subBrandId).str("error"), "PRIVILEGE_ESCALATION", "equal is not above")
    assertEquals(create(as, email, "resource_viewer", "sub_brand", r.subBrandId).status, 201)
    assertEquals(create(as, email, "wizard", "sub_brand", r.subBrandId).str("error"), "UNKNOWN_ROLE")
  }

  test("an operator already in the realm is refused; a re-invitation, a reset and a rescope are made; removal deletes the user") {
    val r  = kit.subBrand()
    val e  = email
    val id = create(kit.asOperator(), e, "resource_viewer", "company", r.companyId).str("operatorId")
    assertEquals(create(kit.asOperator(), e, "resource_viewer", "company", r.companyId).str("error"), "USER_ALREADY_EXISTS")
    assertEquals(kit.request("POST", s"/v1/operators/$id/resend-invitation", None, kit.asOperator()).status, 200)
    assertEquals(kit.request("POST", s"/v1/operators/$id/reset-password", None, kit.asOperator()).status, 200)
    assertEquals(kit.invitations.to(e).map(_.kind), Vector(Invitation.Kind.Invite, Invitation.Kind.Resend, Invitation.Kind.PasswordReset))
    val moved = kit.request("PATCH", s"/v1/operators/$id/scope", Some(s"""{"scopeLevel":"sub_brand","scopeId":"${r.subBrandId}"}"""), kit.asOperator())
    assertEquals((moved.status, moved.str("scopeLevel")), (200, "sub_brand"))
    assertEquals(kit.keycloak.attribute(id, "eitheror_scope_ancestors"), Vector(r.holdingId, r.companyId))
    val other = kit.subBrand()
    val holdingOp = kit.asOperator(level = "holding", scopeId = other.holdingId, roles = "resource_owner")
    assertEquals(kit.request("DELETE", s"/v1/operators/$id", None, holdingOp).status, 403, "outside the caller's level")
    assertEquals(kit.request("DELETE", s"/v1/operators/$id", None, kit.asOperator()).status, 204)
    assertEquals(kit.keycloak.user(id), None)
  }
