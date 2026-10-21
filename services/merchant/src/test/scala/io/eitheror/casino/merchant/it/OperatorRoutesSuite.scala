package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*

import scala.jdk.CollectionConverters.*

/** The operators' routes of contracts/routes.md beyond the features: scope, the credential's `?name=`, the catalogue. */
class OperatorRoutesSuite extends SharedKitSuite:

  private def as = kit.asOperator()

  test("a holding is created at the installation level only; a holding operator is refused INSUFFICIENT_SCOPE") {
    val r = kit.subBrand()
    val holdingOp = kit.asOperator(level = "holding", scopeId = r.holdingId, roles = "resource_owner")
    assertEquals(kit.request("POST", "/v1/holdings", Some("""{"name":"X"}"""), holdingOp).status, 403)
    assertEquals(kit.request("POST", "/v1/holdings", Some("""{"name":"X"}"""), as).status, 201)
  }

  test("a holding operator lists only their holding and reads another's as not found") {
    val mine  = kit.subBrand()
    val other = kit.subBrand()
    val op    = kit.asOperator(level = "holding", scopeId = mine.holdingId, roles = "resource_owner")
    val listed = kit.eventually("listed")(Option(kit.request("GET", "/v1/holdings", None, op)).filter(_.text.contains(mine.holdingId)))
    assert(!listed.text.contains(other.holdingId))
    assertEquals(kit.request("GET", s"/v1/holdings/${other.holdingId}", None, op).status, 404)
  }

  test("a credential is read, replaced, disabled, activated and deleted by its name as a query parameter (research R14)") {
    val r    = kit.subBrand()
    val base = s"/v1/merchants/${r.subBrandId}/psp-credentials/DREAMZPAY"
    assertEquals(kit.request("GET", base, None, as).status, 400, "the name is required")
    assertEquals(kit.request("GET", s"$base?name=main", None, as).str("status"), "active")
    assertEquals(kit.request("PUT", s"$base?name=main", Some("""{"credentials":{"secureKey":"sk-2","webhookSecret":"wh"}}"""), as).status, 200)
    val read = kit.request("GET", s"$base?name=main", None, as)
    assertEquals(read.json.get("credentialKeys").elements.asScala.map(_.asText).toVector, Vector("secureKey", "webhookSecret"))
    val internal = kit.request("GET", s"/internal/v1/sub-brands/${r.subBrandId}/psp-credentials/DREAMZPAY", None, kit.asGateway)
    assertEquals(internal.json.get("credentials").textOr("secureKey", ""), "sk-2")
    assertEquals(kit.request("PATCH", s"$base/disable?name=main", None, as).str("status"), "disabled")
    assertEquals(kit.request("GET", s"/internal/v1/sub-brands/${r.subBrandId}/psp-credentials/DREAMZPAY", None, kit.asGateway).status, 404)
    assertEquals(kit.request("POST", s"$base/activate?name=main", None, as).str("status"), "active")
    assertEquals(kit.request("DELETE", s"$base?name=main", None, as).status, 204)
    assertEquals(kit.request("GET", s"$base?name=main", None, as).status, 404)
  }

  test("a terminal the live routing tree names cannot be deleted") {
    val r  = kit.subBrand(deploy = false)
    val tr = kit.request("POST", s"/v1/merchants/${r.subBrandId}/gateways/T365/integrations",
      Some("""{"scheme":"OPEN_BANKING","name":"T365 OB","regions":["GB"],"properties":{"apiKey":"a","secretKey":"s","midId":"m"}}"""), as)
    val tid = tr.str("integrationId")
    kit.deployTree(io.eitheror.casino.merchant.routing.Level.SubBrand, r.subBrandId, Kit.catchAll(tid))
    val refused = kit.request("DELETE", s"/v1/merchants/${r.subBrandId}/gateways/integrations/$tid", None, as)
    assertEquals((refused.status, refused.str("error")), (409, "TERMINAL_IN_USE"))
  }

  test("the catalogue marks secret properties; a sub-brand create carrying withdrawal turnover is refused as the wallet's") {
    val c = kit.request("GET", "/v1/catalog/gateway/eupaymentz", None, as)
    val props = c.json.get("schemes").get(0).get("requiredProperties").elements.asScala.map(p => p.textOr("key", "") -> p.bool("secret").get).toMap
    assertEquals(props, Map("accountId" -> false, "password" -> true, "passphrase" -> true))
    val r = kit.subBrand()
    val refused = kit.request("POST", "/v1/sub-brands", Some(Json.render(Json.obj("companyId" -> r.companyId, "name" -> "x", "type" -> "Casino",
      "brand" -> "acme", "withdrawalTurnoverMultiplier" -> 2))), as)
    assertEquals(refused.status, 400)
  }

  test("the terminals listing names credential-named terminals and terminals with the directions each takes") {
    val r = kit.subBrand()
    kit.request("POST", s"/v1/merchants/${r.subBrandId}/gateways/DREAMZPAY/integrations",
      Some("""{"scheme":"VISA","name":"Visa","regions":[],"properties":{},"supportsWithdrawal":true}"""), as): Unit
    val ts = kit.eventually("two")(Option(kit.request("GET", s"/v1/payments/terminals?merchantId=${r.subBrandId}", None, as))
      .map(_.json.get("terminals").elements.asScala.toVector).filter(_.size == 2))
    assertEquals(ts.map(t => (t.textOr("integrationType", ""), t.bool("supportsWithdrawal").get)).toSet, Set(("credential", false), ("integration", true)))
  }
