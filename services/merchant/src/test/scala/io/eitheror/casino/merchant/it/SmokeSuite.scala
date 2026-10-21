package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/** The service starts and each audience is told apart (FR-033, research R1, R14). */
class SmokeSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  private var kit: Kit = null
  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  test("the project's status answers the uncredited count and the bound") {
    val r = kit.request("GET", "/status", None, kit.asService("local", "anything"))
    assertEquals(r.status, 200, r.text)
    assertEquals((r.json.get("uncreditedCaptures").asLong, r.str("bound")), (0L, "15m"))
  }

  test("an operator route without a token asks for a sign-in; with a payments operator's token it is served") {
    assertEquals(kit.request("GET", "/v1/holdings", None, kit.asBrowser).status, 401)
    assertEquals(kit.request("GET", "/v1/holdings", None, kit.asOperator()).status, 200)
  }

  test("an identity route refuses a staff token and another service, and serves its caller") {
    assertEquals(kit.request("GET", "/v1/cashier/keys", None, kit.asOperator()).status, 403)
    assertEquals(kit.request("GET", "/v1/cashier/keys", None, kit.asGateway).status, 403)
    assertEquals(kit.request("GET", "/v1/cashier/keys", None, kit.asIngest).status, 200)
    assertEquals(kit.request("GET", "/internal/v1/terminals/t-0/properties", None, kit.asIngest).status, 403)
    assertEquals(kit.request("GET", "/internal/v1/terminals/t-0/properties", None, kit.asGateway).status, 404)
    assertEquals(kit.request("POST", "/v1/payments/p-0/capture", Some("{}"), kit.asOperator()).status, 403)
  }

  test("a capture for a payment that does not exist is acknowledged and ignored (FR-019)") {
    assertEquals(kit.capture("no-such-payment").status, 200)
  }
