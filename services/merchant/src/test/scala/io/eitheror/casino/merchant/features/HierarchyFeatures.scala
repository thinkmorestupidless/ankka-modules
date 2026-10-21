package io.eitheror.casino.merchant.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.it.{Kit, Reply}
import io.eitheror.casino.merchant.keys.{MerchantKeyIndex, RawKeys}
import io.eitheror.casino.merchant.routing.Level

import scala.jdk.CollectionConverters.*

/** features/payments/hierarchy.feature (US4: FR-001 to FR-012), through the operators' routes. */
class HierarchyFeatures extends MerchantFeatures("hierarchy.feature"):

  private def platform = kit.asOperator()
  private def post(path: String, body: (String, Any)*): Reply = kit.request("POST", path, Some(Json.render(Json.obj(body*))), platform)
  private def get(path: String, as: Seq[(String, String)] = Nil): Reply = kit.request("GET", path, None, if as.isEmpty then platform else as)

  private var holding, company, subBrand = ""
  private var other: (String, String, String) = ("", "", "")
  private var created: Reply = null
  private var rawKey = ""
  private var operator: Seq[(String, String)] = Nil

  private def makeSubBrand(brand: String = "acme"): (String, String, String) =
    val h  = post("/v1/holdings", "name" -> "Acme Holdings").str("holdingId")
    val c  = post("/v1/companies", "holdingId" -> h, "name" -> "Acme Ltd").str("companyId")
    val sb = post("/v1/sub-brands", "companyId" -> c, "name" -> "Acme Casino", "type" -> "Casino", "brand" -> brand)
    assertEquals(sb.status, 201, sb.text)
    (h, c, sb.str("subBrandId"))

  Given("an operator at the installation level") { () => () }
  When("they create a holding {string}, a company under it and a sub-brand {string} of type {string} naming brand {string}") {
    (h: String, sb: String, t: String, brand: String) =>
      holding = post("/v1/holdings", "name" -> h).str("holdingId")
      company = post("/v1/companies", "holdingId" -> holding, "name" -> s"$h company").str("companyId")
      created = post("/v1/sub-brands", "companyId" -> company, "name" -> sb, "type" -> t, "brand" -> brand)
      subBrand = created.str("subBrandId")
  }
  Then("each exists with status {string}") { (status: String) =>
    assertEquals(get(s"/v1/holdings/$holding").str("status"), status)
    assertEquals(get(s"/v1/companies/$company").str("status"), status)
    assertEquals(get(s"/v1/sub-brands/$subBrand").str("status"), status)
  }
  Then("each child records its parent") { () =>
    assertEquals(get(s"/v1/companies/$company").str("holdingId"), holding)
    val sb = get(s"/v1/sub-brands/$subBrand")
    assertEquals((sb.str("companyId"), sb.str("holdingId"), sb.str("brand")), (company, holding, "acme"))
  }
  Then("the sub-brand is listed under the company") { () =>
    val listed = kit.eventually("listed")(Option(get(s"/v1/sub-brands?companyId=$company")).filter(_.text.contains(subBrand)))
    assertEquals(listed.json.get("subBrands").size, 1)
  }

  Given("a sub-brand") { () =>
    val (h, c, s) = makeSubBrand()
    holding = h; company = c; subBrand = s
  }
  When("the operator registers a credential for payment provider {string} named {string} with its secrets") { (provider: String, name: String) =>
    created = kit.request("POST", s"/v1/merchants/$subBrand/psp-credentials", Some(Json.render(Json.obj("provider" -> provider, "name" -> name,
      "environment" -> "sandbox", "credentials" -> Json.objOf(Map("secureKey" -> "sk-live-secret-value"))))), platform)
  }
  Then("the credential is listed without its secrets and is active") { () =>
    assertEquals(created.status, 201, created.text)
    val listed = get(s"/v1/merchants/$subBrand/psp-credentials")
    assert(!listed.text.contains("sk-live-secret-value"), listed.text)
    assertEquals(listed.json.get("credentials").get(0).textOr("status", ""), "active")
    val one = get(s"/v1/merchants/$subBrand/psp-credentials/DREAMZPAY?name=main")
    assertEquals(one.json.get("credentialPreviews").textOr("secureKey", ""), "sk-l********")
    assert(!one.text.contains("sk-live-secret-value"))
  }
  Then("a terminal named {string} exists that takes deposits and never withdrawals") { (name: String) =>
    val t = get(s"/v1/payments/terminals?merchantId=$subBrand").json.get("terminals").elements.asScala.find(_.textOr("name", "") == name).get
    assertEquals((t.bool("supportsDeposit"), t.bool("supportsWithdrawal"), t.textOr("status", "")), (Some(true), Some(false), "ACTIVE"))
  }

  private var integration = ""
  When("the operator creates a terminal for payment provider {string} with scheme {string}, a name, regions and properties") {
    (gateway: String, scheme: String) =>
      created = kit.request("POST", s"/v1/merchants/$subBrand/gateways/${gateway.toUpperCase}/integrations", Some(Json.render(Json.obj(
        "scheme" -> scheme, "name" -> "Visa EU", "regions" -> Vector("GB", "DE"),
        "properties" -> Json.objOf(Map("memberId" -> "m-1", "secureKey" -> "sk-terminal-secret"))))), platform)
      integration = created.str("integrationId")
  }
  Then("the terminal exists inactive") { () =>
    assertEquals(created.status, 201, created.text)
    val t = get(s"/v1/merchants/$subBrand/gateways/integrations/$integration")
    assertEquals(t.str("status"), "INACTIVE")
    assert(!t.text.contains("sk-terminal-secret"), "a secret property's value never reaches a listing")
  }
  Then("it takes deposits, and withdrawals only when the request said so") { () =>
    val t = get(s"/v1/merchants/$subBrand/gateways/integrations/$integration").json
    assertEquals((t.bool("supportsDeposit"), t.bool("supportsWithdrawal")), (Some(true), Some(false)))
  }
  Then("it can be activated, disabled, given new properties or a new capability, or deleted") { () =>
    val base = s"/v1/merchants/$subBrand/gateways/integrations/$integration"
    assertEquals(kit.request("PATCH", s"$base/activate", None, platform).status, 204)
    assertEquals(get(base).str("status"), "ACTIVE")
    assertEquals(kit.request("PATCH", s"$base/disable", None, platform).status, 204)
    assertEquals(get(base).str("status"), "INACTIVE")
    val props = kit.request("PATCH", s"$base/properties", Some("""{"properties":{"memberId":"m-2","secureKey":"sk-new","totype":"t"}}"""), platform)
    assertEquals(props.json.get("propertyKeys").elements.asScala.map(_.asText).toVector, Vector("memberId", "secureKey", "totype"))
    val cap = kit.request("PATCH", s"$base/capability", Some("""{"supportsWithdrawal":true}"""), platform)
    assertEquals(cap.json.bool("supportsWithdrawal"), Some(true))
    val secret = kit.request("GET", s"/internal/v1/terminals/$integration/properties", None, kit.asGateway)
    assertEquals(secret.json.get("properties").textOr("secureKey", ""), "sk-new", "the gateway reads the secret at the moment of use")
    assertEquals(kit.request("DELETE", base, None, platform).status, 204)
    assertEquals(get(base).status, 404)
  }

  When("the operator generates a cashier key named {string}") { (name: String) =>
    created = post(s"/v1/merchants/$subBrand/keys", "name" -> name)
    rawKey = created.str("rawKey")
  }
  Then("the raw key is shown once") { () =>
    assertEquals(created.status, 201, created.text)
    assert(rawKey.startsWith("mk_") && rawKey.length > 20)
  }
  Then("the sub-brand lists the key by id and name without the raw value") { () =>
    val listed = get(s"/v1/merchants/$subBrand/keys")
    assertEquals(listed.json.get("keys").get(0).textOr("name", ""), "frontend")
    assert(!listed.text.contains(rawKey))
  }
  Then("a cashier session created with that key resolves to the sub-brand") { () =>
    val digest = RawKeys.digest(rawKey)
    val entry = kit.eventually("indexed")(Option(kit.components.forKeyValueEntity(EntityId(digest)).call(MerchantKeyIndex.get).invoke()).filter(_.active))
    assertEquals(entry.subBrandId, subBrand)
    val keys = kit.eventually("refreshable")(Option(kit.request("GET", "/v1/cashier/keys", None, kit.asIngest)).filter(_.text.contains(digest)))
    assert(!keys.text.contains(rawKey))
  }

  private val tree = """{"rootNodeId":"root","nodes":{"root":{"nodeId":"root","branches":null,"terminals":["main"]}}}"""
  private def draft(level: String, id: String, terminal: String = "main") =
    kit.request("POST", "/v1/payments/routing-rules", Some(s"""{"level":"$level","entityId":"$id","tree":${tree.replace("\"main\"", s"\"$terminal\"")}}"""), platform)
  private def deploy(level: String, id: String) = kit.request("POST", s"/v1/payments/routing-rules/deploy?level=$level&entityId=$id", None, platform)
  private var deployed: Reply = null

  Given("a routing tree saved as a draft at the sub-brand level") { () =>
    val (h, c, s) = makeSubBrand(); holding = h; company = c; subBrand = s
    assertEquals(draft("subBrand", subBrand).status, 202)
  }
  When("the operator deploys it") { () => deployed = deploy("subBrand", subBrand) }
  Then("it is live as version {string} with a deployment time") { (v: String) =>
    assertEquals((deployed.status, deployed.json.get("version").asInt.toString), (200, v))
    assert(deployed.str("deployedAt").nonEmpty)
  }
  Then("the history lists it") { () =>
    val st = get(s"/v1/payments/routing-rules/state?level=subBrand&entityId=$subBrand")
    assertEquals(st.json.get("history").get(0).get("version").asInt, 1)
  }
  Then("a later deploy becomes version {string}, from which a rollback to version {string} is possible") { (v2: String, v1: String) =>
    draft("subBrand", subBrand, "spare")
    assertEquals(deploy("subBrand", subBrand).json.get("version").asInt.toString, v2)
    assertEquals(kit.request("POST", s"/v1/payments/routing-rules/rollback/$v1?level=subBrand&entityId=$subBrand", None, platform).status, 204)
    val st = get(s"/v1/payments/routing-rules/state?level=subBrand&entityId=$subBrand").json
    assertEquals(st.get("draft").get("nodes").get("root").get("terminals").get(0).asText, "main")
    assertEquals(deploy("subBrand", subBrand).json.get("version").asInt, 3)
  }

  Given("a routing tree at the holding level and none at the company or sub-brand level") { () =>
    ready = kit.subBrand(brand = Some("acme"), deploy = false)
    kit.deployTree(Level.Holding, ready.holdingId, Kit.catchAll("main"))
  }
  When("a cashier session is created for a sub-brand under that holding") { () => last = kit.session(ready) }
  Then("the holding's routing tree governs it") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.get("available_terminals").get(0).textOr("terminalEntityId", ""), "main")
  }
  Then("the answer says which level the tree was resolved from") { () =>
    val from = last.json.get("resolvedFrom")
    assertEquals((from.textOr("level", ""), from.textOr("entityId", "")), ("holding", ready.holdingId))
  }

  private var simulated: Reply = null
  Given("a routing tree") { () => () }
  When("the operator simulates a deposit of {string} minor units of {string} from {string} with a deposit count of {string}") {
    (amount: String, currency: String, country: String, count: String) =>
      val t = """{"rootNodeId":"r","nodes":{"r":{"nodeId":"r","branches":[{"conditions":[{"conditionType":"COUNTRY","operator":"IN","value":"GB,DE"},{"conditionType":"AMOUNT","operator":"LT","value":"50000"}],"nextNodeId":"t"}]},"t":{"nodeId":"t","terminals":["main"]}}}"""
      simulated = kit.request("POST", "/v1/payments/routing-rules/simulate", Some(s"""{"tree":$t,"currency":"$currency","amountCents":$amount,"country":"$country","depositCount":$count}"""), platform)
  }
  Then("the answer names the terminals the tree reaches, or that nothing matched") { () =>
    assertEquals((simulated.status, simulated.str("outcome")), (200, "MATCH"), simulated.text)
    assertEquals(simulated.json.get("terminals").get(0).asText, "main")
  }

  Given("an operator at the level of one company") { () =>
    ready = kit.subBrand()
    other = { val o = kit.subBrand(); (o.holdingId, o.companyId, o.subBrandId) }
    operator = kit.asOperator(level = "company", scopeId = ready.companyId, roles = "resource_admin")
  }
  When("they read or change a sub-brand of another company") { () =>
    last = get(s"/v1/merchants/${other._3}", operator)
    created = kit.request("POST", s"/v1/merchants/${other._3}/keys", Some("""{"name":"x"}"""), operator)
  }
  Then("they are refused as outside their level") { () =>
    for r <- Vector(last, created) do
      assertEquals((r.status, r.str("error"), r.str("message")), (403, "scope_violation", "Your operator scope does not include this resource"))
  }
  When("they read a sub-brand of their own company") { () => last = get(s"/v1/merchants/${ready.subBrandId}", operator) }
  Then("they are served") { () => assertEquals((last.status, last.str("subBrandId")), (200, ready.subBrandId)) }
