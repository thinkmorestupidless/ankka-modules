package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.deposits.DepositFlow

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.deposits.DepositWorkflow

/**
 * Spec 030 H3 (spec 004 FR-029c) at the routes: `/v1/sub-brands/{id}/deposit-bounds` GET and PUT under
 * the payments operator ACL and the operator's level, and the workflow's refusal before the gateway.
 */
class DepositBoundsRoutesSuite extends SharedKitSuite:

  private def path(r: Ready) = s"/v1/sub-brands/${r.subBrandId}/deposit-bounds"
  private def put(r: Ready, body: String, as: Seq[(String, String)] = kit.asOperator()) = kit.request("PUT", path(r), Some(body), as)

  test("PUT replaces the set and answers it; GET reads it with the catalogue's methods; an empty list removes them") {
    val r = kit.subBrand()
    val a = put(r, """{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":1000},{"method":"bank_transfer","currency":"EUR","maximumAmountCents":20000}]}""")
    assertEquals(a.status, 200, a.text)
    val read = kit.request("GET", path(r), None, kit.asOperator())
    assertEquals(read.text, a.text)
    val bounds = read.json.get("bounds")
    assertEquals((bounds.size, bounds.get(0).textOr("method", ""), bounds.get(1).textOr("method", "")), (2, "bank_transfer", "card"), "sorted by method")
    assertEquals((bounds.get(1).long("minimumAmountCents"), bounds.get(1).textOr("minimumAmount", ""), Option(bounds.get(1).get("maximumAmountCents"))),
      (Some(1000L), "10.00", None))
    assert(read.json.strings("methods").contains("card"), read.text)
    assertEquals(put(r, """{"bounds":[]}""").json.get("bounds").size, 0)
  }

  test("a body that is not whole minor units, a bad bound or a missing list is refused 400 and changes nothing") {
    val r = kit.subBrand()
    assertEquals(put(r, """{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":1000}]}""").status, 200)
    def refused(body: String, field: Option[String] = None) =
      val a = put(r, body)
      assertEquals((a.status, a.str("error")), (400, "INVALID_REQUEST"), a.text)
      field.foreach(f => assertEquals(a.str("field"), f))
    refused("""{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":10.5}]}""", Some("bounds[0].minimumAmountCents"))
    refused("""{"bounds":[{"method":"card","currency":"EUR","maximumAmountCents":"5000"}]}""", Some("bounds[0].maximumAmountCents"))
    refused("""{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":5000,"maximumAmountCents":10}]}""")
    refused("""{"bounds":[{"method":"card","currency":"EUR"}]}""")
    refused("""{"bounds":[{"method":"card","currency":"euro","minimumAmountCents":1}]}""")
    refused("""{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":1},{"method":"card","currency":"EUR","minimumAmountCents":2}]}""")
    refused("""{"min":1}""", Some("bounds"))
    assertEquals(kit.request("GET", path(r), None, kit.asOperator()).json.get("bounds").get(0).long("minimumAmountCents"), Some(1000L))
  }

  test("an operator outside the sub-brand's level is refused; an unknown sub-brand is not found; a brand operator and a service are refused") {
    val r     = kit.subBrand()
    val other = kit.subBrand()
    val body  = """{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":1000}]}"""
    val outside = kit.asOperator(level = "company", scopeId = other.companyId, roles = "resource_admin")
    assertEquals((put(r, body, outside).status, put(r, body, outside).str("error")), (403, "scope_violation"))
    assertEquals(kit.request("GET", path(r), None, outside).status, 403)
    val inside = kit.asOperator(level = "company", scopeId = r.companyId, roles = "resource_admin")
    assertEquals(put(r, body, inside).status, 200)
    assertEquals(kit.request("PUT", "/v1/sub-brands/sb-nowhere/deposit-bounds", Some(body), kit.asOperator()).str("error"), "SUB_BRAND_NOT_FOUND")
    assertEquals(kit.request("GET", path(r), None, kit.asOperator(level = "none", roles = s"brand_${r.brand.get}")).status, 403)
    assertEquals(kit.request("GET", path(r), None, kit.asIngest).status / 100, 4, "no service reads them")
  }

  test("a refused deposit makes no payment, asks no gateway, and leaves the session open for another amount's session or method") {
    val r = kit.subBrand()
    assertEquals(put(r, """{"bounds":[{"method":"card","currency":"EUR","minimumAmountCents":1000,"maximumAmountCents":500000}]}""").status, 200)
    val s = kit.session(r, amount = 999)
    val before = kit.gateway.initiates.size
    val a = kit.submit(s.str("session_token"))
    assertEquals((a.status, a.str("error"), a.str("minimumAmount"), a.str("currency")), (403, "DEPOSIT_BELOW_MINIMUM", "10.00", "EUR"), a.text)
    assertEquals(kit.gateway.initiates.size, before)
    val again = kit.submit(s.str("session_token"))
    assertEquals(again.str("error"), "DEPOSIT_BELOW_MINIMUM", "a second submit is decided again, not answered as a payment")
    val paymentId = kit.payment(r, amount = 1000)
    assertEquals(kit.paymentState(paymentId).opened.map(_.amount), Some(1000L), "the minimum itself is taken")
    val maxId = kit.payment(r, amount = 500000)
    assertEquals(kit.components.forWorkflow(EntityId(maxId)).call(DepositWorkflow.status).invoke().status == DepositFlow.Refused, false, "the maximum itself is taken")
    val usd = kit.payment(r, amount = 1, currency = "USD")
    assert(kit.paymentState(usd).exists, "another currency is not bounded")
  }
