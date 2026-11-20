package io.eitheror.casino.merchant.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.it.Reply
import io.eitheror.casino.contract.access.BrandRoles
import io.eitheror.casino.merchant.operators.Operator

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

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

  // ── spec 030 H6, amended by 032: a brand gives its roles; the applier puts the giving in force ─────

  private def brandRoles(brand: String, roles: String*): Reply =
    kit.request("PUT", s"/v1/operators/$id/brand-roles/$brand", Some(Json.render(Json.obj("roles" -> Json.arr(roles*)))),
      kit.asService(brand, "players"))

  private def held: Set[String] = kit.keycloak.user(id).map(_.roles.toSet).getOrElse(Set.empty)
  private def named(brand: String, roles: String*): Set[String] = roles.map(BrandRoles.nameIn(brand, _)).toSet
  /** What H6r answers the brand. */
  private def giving(brand: String): Reply = kit.request("GET", s"/v1/operators/$id/brand-roles/$brand", None, kit.asService(brand, "players"))
  private def state(brand: String): String = giving(brand).str("state")
  /** The giving H6 just answered, in the state named: in force, or failed. */
  private def ends(brand: String, as: String, within: FiniteDuration = 30.seconds): Reply =
    kit.eventually(s"$brand's giving $as", within)(Option(giving(brand)).filter(_.str("state") == as))
  private def accepted(r: Reply): Reply =
    assertEquals(r.status, 202, r.text); assertEquals(r.str("state"), "pending"); r
  private def invited(): Unit =
    ready = kit.subBrand()
    as = kit.asOperator()
    id = create(fresh("desk@acme.example"), "resource_viewer", "company", ready.companyId).str("operatorId")

  Given("an operator invited by the shared payments") { () => invited() }
  When("the brand {string} asks for that operator to hold the roles {string} and {string}") { (brand: String, a: String, b: String) =>
    made = brandRoles(brand, a, b)
  }
  When("the brand {string} asks for that operator to hold the role {string}") { (brand: String, role: String) =>
    made = brandRoles(brand, role)
  }
  Then("the operator holds those roles and the role naming the brand {string}") { (brand: String) =>
    accepted(made)
    ends(brand, "in_force")
    assert((named(brand, "brand_support", "vip_host") + s"brand_$brand").subsetOf(held), held.toString)
    assertEquals(giving(brand).json.get("held").elements.asScala.map(_.asText).toSet, Set("brand_support", "vip_host"))
  }
  Then("a role that did not exist in the realm is created there") { () =>
    assert(kit.keycloak.roles("vip_host@goldvibe"))
    // another brand's players cannot set goldvibe's roles, nor read them
    val r = kit.request("PUT", s"/v1/operators/$id/brand-roles/goldvibe", Some("""{"roles":[]}"""), kit.asService("spinvibe", "players"))
    assertEquals(r.status, 403, r.text)
    assertEquals(kit.request("GET", s"/v1/operators/$id/brand-roles/goldvibe", None, kit.asService("spinvibe", "players")).status, 403)
    // nor can any other service
    assertEquals(kit.request("PUT", s"/v1/operators/$id/brand-roles/goldvibe", Some("""{"roles":[]}"""), kit.asService("goldvibe", "wallet")).status, 403)
  }
  Then("the request is refused and the operator's roles are unchanged") { () =>
    assertEquals(made.status, 400, made.text)
    assertEquals(made.str("error"), "RESERVED_ROLE")
    for role <- Vector("resource_admin", "brand_spinvibe", "audit_compliance", "affiliate_manager") do
      assertEquals(brandRoles("goldvibe", role).status, 400, role)
    // a role named for a brand is refused by name: a giving names its roles without the brand
    val namedRole = brandRoles("goldvibe", "brand_admin@spinvibe")
    assertEquals((namedRole.status, namedRole.str("error")), (400, "role_named_for_brand"), namedRole.text)
    Thread.sleep(500)
    assertEquals(held, Set("resource_viewer"))
    assert(giving("goldvibe").json.get("state") == null, "a refused giving is not recorded as one")
    // a refusal records no giving; its audit record is AuditFactsSuite's (FR-014)
    val state = kit.components.forEventSourcedEntity(EntityId(id)).call(Operator.get).invoke()
    assert(state.givings.isEmpty, state.givings.toString)
  }

  Given("an operator holding the role naming the brand {string}") { (brand: String) =>
    invited()
    accepted(brandRoles(brand, "brand_support"))
    ends(brand, "in_force")
    assert(held(s"brand_$brand") && held(BrandRoles.nameIn(brand, "brand_support")), held.toString)
  }
  Then("the operator still holds the role naming the brand {string}") { (brand: String) =>
    accepted(made)
    ends("goldvibe", "in_force")
    assert(held(s"brand_$brand") && held("brand_goldvibe"), held.toString)
    assert(held(BrandRoles.nameIn(brand, "brand_support")) && held(BrandRoles.nameIn("goldvibe", "brand_support")), held.toString)
    // goldvibe giving nothing takes away only its own roles; the brand stays named
    accepted(brandRoles("goldvibe"))
    ends("goldvibe", "in_force")
    assert(!held(BrandRoles.nameIn("goldvibe", "brand_support")) && held("brand_goldvibe"), held.toString)
    assert(held(BrandRoles.nameIn(brand, "brand_support")) && held(s"brand_$brand"), held.toString)
  }

  // ── spec 032: a giving in force whatever the realm does part-way, and whatever another brand does ───

  private val ShortRole = Map("support" -> "brand_support", "finance" -> "brand_finance", "compliance" -> "brand_compliance", "admin" -> "brand_admin")

  Given("an operator holding the support role in {string}") { (brand: String) =>
    invited()
    accepted(brandRoles(brand, "brand_support"))
    ends(brand, "in_force")
  }
  Given("the realm stops answering part-way through the next giving") { () =>
    // the applier reads the operator's roles, then makes the role it gives: the realm answers those two calls only
    kit.keycloak.stopAnsweringAfter(2)
  }
  When("{string} gives the operator the {word} role instead") { (brand: String, role: String) =>
    made = accepted(brandRoles(brand, ShortRole(role)))
    Thread.sleep(2500)
    assertEquals(state(brand), "pending", "nothing is in force while the realm does not answer")
  }
  Then("once the realm answers again the operator holds the {word} role in {string} and not the {word} role") {
    (role: String, brand: String, gone: String) =>
      val back = java.time.Instant.now()
      kit.keycloak.answerAgain()
      ends(brand, "in_force")
      val took = java.time.Duration.between(back, java.time.Instant.now())
      assert(took.getSeconds < 30, s"in force $took after the realm answered again (SC-002)")
      assert(held(BrandRoles.nameIn(brand, ShortRole(role))), held.toString)
      assert(!held(BrandRoles.nameIn(brand, ShortRole(gone))), held.toString)
  }
  Then("nobody has asked again") { () =>
    assertEquals(giving("goldvibe").json.get("version").asLong, made.json.get("version").asLong, "the brand's giving was asked once")
  }

  Given("an operator whose roles name the brands {string} and {string}") { (a: String, b: String) =>
    invited()
    for brand <- Vector(a, b) do { accepted(brandRoles(brand)); ends(brand, "in_force") }
    assert(held(s"brand_$a") && held(s"brand_$b"), held.toString)
  }
  When("{string} gives the operator the {word} role at the same moment as {string} gives the operator the {word} role") {
    (a: String, ra: String, b: String, rb: String) =>
      import scala.concurrent.{Await, Future}
      import scala.concurrent.ExecutionContext.Implicits.global
      // SC-003: a hundred givings by the two brands at once; each brand's last asks for its role
      val others = Map(a -> ShortRole("support"), b -> ShortRole("admin"))
      val askings = (1 to 50).flatMap(i => Vector(a, b).map(brand => (brand, if i == 50 then None else Some(others(brand)))))
      val first = Future.sequence(askings.filter(_._2.isDefined).map((brand, role) => Future(brandRoles(brand, role.get))))
      Await.result(first, 60.seconds).foreach(accepted)
      val last = Future.sequence(Vector(Future(brandRoles(a, ShortRole(ra))), Future(brandRoles(b, ShortRole(rb)))))
      Await.result(last, 30.seconds).foreach(accepted)
  }
  Then("the operator holds the {word} role in {string} only") { (role: String, brand: String) =>
    ends(brand, "in_force", 60.seconds)
    val mine = held.filter(_.endsWith(s"@$brand"))
    assertEquals(mine, named(brand, ShortRole(role)), held.toString)
  }
  Then("the {word} role in {string} only") { (role: String, brand: String) =>
    ends(brand, "in_force", 60.seconds)
    assertEquals(held.filter(_.endsWith(s"@$brand")), named(brand, ShortRole(role)), held.toString)
    assert(!held(BrandRoles.nameIn(brand, "brand_finance")) && held.count(_.contains("@")) == 2, held.toString)
  }

  Given("{string} has given an operator the {word} role, not yet in force") { (brand: String, role: String) =>
    invited()
    kit.keycloak.stopAnsweringAfter(0)
    made = accepted(brandRoles(brand, ShortRole(role)))
  }
  When("{string} gives the operator the {word} role") { (brand: String, role: String) =>
    made = accepted(brandRoles(brand, ShortRole(role)))
    kit.keycloak.answerAgain()
  }
  Then("the operator ends holding the {word} role in {string} and not the {word} role") { (role: String, brand: String, gone: String) =>
    val r = ends(brand, "in_force")
    assertEquals(r.json.get("version").asLong, made.json.get("version").asLong, "the later giving is the one in force")
    assert(held(BrandRoles.nameIn(brand, ShortRole(role))) && !held(BrandRoles.nameIn(brand, ShortRole(gone))), held.toString)
  }

  Given("an operator of {string} the realm no longer knows") { (brand: String) =>
    invited()
    accepted(brandRoles(brand)); ends(brand, "in_force")
    kit.keycloak.forget(id)
  }
  Then("the giving ends as failed, with the realm's reason") { () =>
    val r = ends("goldvibe", "failed")
    assert(r.str("reason").nonEmpty, r.text)
  }
  Then("it is not tried again") { () =>
    kit.keycloak.clearReceived()
    Thread.sleep(3000)
    assertEquals(kit.keycloak.roleCalls(id), Vector.empty)
    assertEquals(state("goldvibe"), "failed")
  }
