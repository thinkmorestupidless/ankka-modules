package io.eitheror.casino.merchant.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.it.Reply
import io.eitheror.casino.merchant.operators.Operator

/** features/payments/operators.feature (US6: FR-027, FR-028), against the fake realm. */
class OperatorsFeatures extends MerchantFeatures("operators.feature"):

  private var as: Seq[(String, String)] = Nil
  private var id    = ""
  private var email = ""
  private var made: Reply = null

  private def create(e: String, role: String, level: String, scope: String): Reply =
    kit.request("POST", "/v1/operators", Some(Json.render(Json.obj("email" -> e, "role" -> role, "scopeLevel" -> level, "scopeId" -> scope))), as)

  private def fresh(e: String) = e.replace("@", s"+${java.util.UUID.randomUUID().toString.take(6)}@")

  Given("an operator at the installation level") { () =>
    ready = kit.subBrand()
    as = kit.asOperator()
  }
  When("they create an operator {string} with role {string} at the level of a company") { (e: String, role: String) =>
    email = fresh(e)
    made = create(email, role, "company", ready.companyId)
    id = made.str("operatorId")
  }
  Then("the operator exists pending with that role, that level and its ancestors") { () =>
    assertEquals(made.status, 201, made.text)
    val s = kit.components.forEventSourcedEntity(EntityId(id)).call(Operator.get).invoke()
    assertEquals((s.pending, s.role, s.scopeLevel, s.scopeId, s.ancestors), (true, "resource_admin", "company", ready.companyId, Vector(ready.holdingId)))
  }
  Then("an operator invitation is sent") { () => assertEquals(kit.invitations.to(email).size, 1) }

  Given("a pending operator") { () =>
    ready = kit.subBrand()
    as = kit.asOperator()
    email = fresh("pending@acme.example")
    id = create(email, "resource_viewer", "sub_brand", ready.subBrandId).str("operatorId")
  }
  When("they accept the operator invitation and set a password") { () =>
    val token = kit.invitations.to(email).head.token
    made = kit.request("POST", "/v1/operators/accept-invitation", Some(s"""{"token":"$token","password":"Pa55word!"}"""), kit.asBrowser)
  }
  Then("they are active") { () =>
    assertEquals(made.status, 200, made.text)
    assert(!kit.components.forEventSourcedEntity(EntityId(id)).call(Operator.get).invoke().pending)
  }
  Then("their sign-in carries their level") { () =>
    assertEquals((kit.keycloak.attribute(id, "eitheror_scope_level"), kit.keycloak.attribute(id, "eitheror_scope_id")),
      (Vector("sub_brand"), Vector(ready.subBrandId)))
  }

  Given("an operator at the level of one company") { () =>
    ready = kit.subBrand()
    as = kit.asOperator(level = "company", scopeId = ready.companyId, roles = "resource_admin")
  }
  When("they create an operator at the level of a holding") { () => made = create(fresh("up@acme.example"), "resource_viewer", "holding", ready.holdingId) }
  When("they create an operator with a role of more privilege than their own") { () =>
    made = create(fresh("priv@acme.example"), "resource_owner", "sub_brand", ready.subBrandId)
  }
  Then("they are refused") { () =>
    assertEquals(made.status, 403, made.text)
    assert(Set("SCOPE_ESCALATION", "PRIVILEGE_ESCALATION")(made.str("error")))
  }

  Given("an operator disabled at the sign-in") { () =>
    ready = kit.subBrand()
    as = kit.asOperator()
    id = create(fresh("off@acme.example"), "resource_viewer", "company", ready.companyId).str("operatorId")
    kit.keycloak.disable(id)
  }
  When("the sign-in is mirrored") { () => kit.syncOperators() }
  Then("the operator reads as disabled") { () =>
    kit.eventually("disabled")(Option(kit.components.forEventSourcedEntity(EntityId(id)).call(Operator.get).invoke()).filter(!_.enabled))
    import scala.jdk.CollectionConverters.*
    kit.eventually("listed as disabled")(Option(kit.request("GET", "/v1/operators?pageSize=200", None, kit.asOperator()))
      .flatMap(_.json.get("operators").elements.asScala.find(_.textOr("operatorId", "") == id)).filter(_.bool("enabled").contains(false)))
  }

  // ── spec 030 H6: a brand's players sets the roles that brand gives ────────────────────────────────

  private def brandRoles(brand: String, roles: String*): Reply =
    kit.request("PUT", s"/v1/operators/$id/brand-roles/$brand", Some(Json.render(Json.obj("roles" -> Json.arr(roles*)))),
      kit.asService(brand, "players"))

  private def held: Set[String] = kit.keycloak.user(id).map(_.roles.toSet).getOrElse(Set.empty)

  Given("an operator invited by the shared payments") { () =>
    ready = kit.subBrand()
    as = kit.asOperator()
    id = create(fresh("desk@acme.example"), "resource_viewer", "company", ready.companyId).str("operatorId")
  }
  When("the brand {string} asks for that operator to hold the roles {string} and {string}") { (brand: String, a: String, b: String) =>
    made = brandRoles(brand, a, b)
  }
  When("the brand {string} asks for that operator to hold the role {string}") { (brand: String, role: String) =>
    made = brandRoles(brand, role)
  }
  Then("the operator holds those roles and the role naming the brand {string}") { (brand: String) =>
    assertEquals(made.status, 200, made.text)
    assert(Set("brand_support", "vip_host", s"brand_$brand").subsetOf(held), held.toString)
    assertEquals(kit.components.forEventSourcedEntity(EntityId(id)).call(Operator.get).invoke().brandRoles(brand),
      Set("brand_support", "vip_host", s"brand_$brand"))
  }
  Then("a role that did not exist in the realm is created there") { () =>
    assert(kit.keycloak.roles("vip_host"))
    // another brand's players cannot set goldvibe's roles
    val r = kit.request("PUT", s"/v1/operators/$id/brand-roles/goldvibe", Some("""{"roles":[]}"""), kit.asService("spinvibe", "players"))
    assertEquals(r.status, 403, r.text)
    // nor can any other service
    assertEquals(kit.request("PUT", s"/v1/operators/$id/brand-roles/goldvibe", Some("""{"roles":[]}"""), kit.asService("goldvibe", "wallet")).status, 403)
  }
  Then("the request is refused and the operator's roles are unchanged") { () =>
    assertEquals(made.status, 400, made.text)
    assertEquals(made.str("error"), "RESERVED_ROLE")
    assertEquals(held, Set("resource_viewer"))
    for role <- Vector("resource_admin", "brand_spinvibe", "audit_compliance", "affiliate_manager") do
      assertEquals(brandRoles("goldvibe", role).status, 400, role)
    assertEquals(held, Set("resource_viewer"))
  }

  Given("an operator holding the role naming the brand {string}") { (brand: String) =>
    ready = kit.subBrand()
    as = kit.asOperator()
    id = create(fresh("both@acme.example"), "resource_viewer", "company", ready.companyId).str("operatorId")
    assertEquals(brandRoles(brand, "brand_support").status, 200)
    assert(held(s"brand_$brand"))
  }
  Then("the operator still holds the role naming the brand {string}") { (brand: String) =>
    assertEquals(made.status, 200, made.text)
    assert(held(s"brand_$brand") && held("brand_goldvibe"), held.toString)
    // goldvibe no longer giving brand_support leaves it: spinvibe's request gave it too
    assertEquals(brandRoles("goldvibe").status, 200)
    assert(held("brand_support") && held(s"brand_$brand"), held.toString)
    // and a role only goldvibe gave goes when goldvibe stops asking for it
    assertEquals(brandRoles("goldvibe", "vip_host").status, 200)
    assert(held("vip_host"))
    assertEquals(brandRoles("goldvibe").status, 200)
    assert(!held("vip_host") && held("brand_support"), held.toString)
  }
