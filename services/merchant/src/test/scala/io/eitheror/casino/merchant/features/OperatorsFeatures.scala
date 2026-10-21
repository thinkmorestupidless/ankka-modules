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
    kit.eventually("listed as disabled")(Option(kit.request("GET", "/v1/operators", None, kit.asOperator()))
      .flatMap(_.json.get("operators").elements.asScala.find(_.textOr("operatorId", "") == id)).filter(_.bool("enabled").contains(false)))
  }
