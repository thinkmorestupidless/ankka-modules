package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.hierarchy.{Actor, ChangeCredentialStatus, CredentialRecord, SubBrand}

/**
 * Spec 030 H4 (spec 004 FR-029d) at the routes: `POST /v1/sub-brands/{id}/psp-credentials/{provider}/test-payment?name=`
 * under the payments operator ACL and the operator's level; the gateway's refusals passed on; and the
 * internal read by name the gateway makes for it, a disabled credential included.
 */
class TestPaymentRoutesSuite extends SharedKitSuite:

  private def testPay(r: Ready, as: Seq[(String, String)] = kit.asOperator(), name: String = "main", provider: String = "UNITYFINANCE",
      body: String = "{}") =
    kit.request("POST", s"/v1/sub-brands/${r.subBrandId}/psp-credentials/$provider/test-payment?name=$name", Some(body), as)

  test("the currency asked is passed on; the answer carries the amount in minor units and as a decimal; no deposit is started") {
    val r = kit.subBrand(provider = "UNITYFINANCE")
    val a = testPay(r, body = """{"currency":"EUR"}""")
    assertEquals(a.status, 200, a.text)
    assertEquals((a.str("currency"), a.json.long("amountCents"), a.str("amount"), a.str("subBrandId")), ("EUR", Some(100L), "1.00", r.subBrandId))
    assertEquals(kit.gateway.testPayments.map(_.json.get.textOr("currency", "")), Vector("EUR"))
    assertEquals(kit.gateway.initiates, Vector.empty)
    assertEquals(kit.wallet.received.filter(w => (w.path + w.body).contains(a.str("reference"))), Vector.empty)
  }

  test("the name is required, an unknown name is CREDENTIAL_NOT_FOUND, another level's sub-brand a scope violation; the gateway is not asked") {
    val r = kit.subBrand(provider = "UNITYFINANCE")
    val other = kit.subBrand(provider = "UNITYFINANCE")
    val noName = kit.request("POST", s"/v1/sub-brands/${r.subBrandId}/psp-credentials/UNITYFINANCE/test-payment", Some("{}"), kit.asOperator())
    assertEquals((noName.status, noName.str("error")), (400, "INVALID_REQUEST"))
    assertEquals(testPay(r, name = "nope").str("error"), "CREDENTIAL_NOT_FOUND")
    val outside = testPay(r, as = kit.asOperator(level = "sub_brand", scopeId = other.subBrandId, roles = "resource_admin"))
    assertEquals((outside.status, outside.str("error")), (403, "scope_violation"))
    assertEquals(testPay(r, as = kit.asBrowser).status, 401)
    assertEquals(kit.gateway.testPayments, Vector.empty)
  }

  test("the gateway's refusal is passed on in upper case; a gateway down is 503 UNAVAILABLE") {
    val dreamz = kit.subBrand(provider = "DREAMZPAY")
    val none   = testPay(dreamz, provider = "DREAMZPAY")
    assertEquals((none.status, none.str("error"), none.str("provider")), (501, "TEST_MODE_UNAVAILABLE", "DREAMZPAY"), none.text)
    kit.gateway.testAnswer = Some(Answer(404, """{"error":"no_credentials","message":"the credential lacks checkoutId.CARD_TEST"}"""))
    val r = kit.subBrand(provider = "UNITYFINANCE")
    val lacking = testPay(r)
    assertEquals((lacking.status, lacking.str("error"), lacking.str("message")), (404, "NO_CREDENTIALS", "the credential lacks checkoutId.CARD_TEST"))
    kit.gateway.testAnswer = None
    kit.gateway.down = true
    assertEquals(testPay(r).str("error"), "UNAVAILABLE")
  }

  test("the gateway reads the named credential, disabled or not; without a name, only the usable one") {
    val r = kit.subBrand(provider = "UNITYFINANCE")
    kit.components.forEventSourcedEntity(EntityId(r.subBrandId)).call(SubBrand.changeCredentialStatus)
      .invoke(ChangeCredentialStatus(r.credential.id, CredentialRecord.Disabled, Actor.operator("kit"), kit.now())): Unit
    val named = kit.request("GET", s"/internal/v1/sub-brands/${r.subBrandId}/psp-credentials/UNITYFINANCE?name=main", None, kit.asGateway)
    assertEquals(named.status, 200, named.text)
    assert(named.json.get("credentials").has("secureKey"), named.text)
    assertEquals(kit.request("GET", s"/internal/v1/sub-brands/${r.subBrandId}/psp-credentials/UNITYFINANCE", None, kit.asGateway).status, 404)
    assertEquals(kit.request("GET", s"/internal/v1/sub-brands/${r.subBrandId}/psp-credentials/UNITYFINANCE?name=other", None, kit.asGateway).status, 404)
    assertEquals(kit.request("GET", s"/internal/v1/sub-brands/${r.subBrandId}/psp-credentials/UNITYFINANCE?name=main", None, kit.asOperator()).status, 403)
  }
