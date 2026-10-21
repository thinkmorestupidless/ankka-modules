package io.eitheror.casino.merchant.gateway

import io.eitheror.casino.merchant.cashier.CustomerFields
import io.eitheror.casino.merchant.it.{DirectServices, FakeGateway}
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.contract.personal.Personal

/** contracts/gateway.md, row for row, against the fake the suites use, through the real client. */
class GatewayClientSuite extends munit.FunSuite:

  private val gw     = FakeGateway()
  private val client = GatewayClient.http(DirectServices(Map("psp-gateway" -> gw.address)))
  override def afterAll(): Unit = gw.stop()
  override def beforeEach(context: BeforeEach): Unit = gw.reset()

  private def page(id: String) = HostedPage(id, 5000, "EUR", "VISA", s"/v1/payments/$id/capture", "DREAMZPAY", "sb-1", "acme:p1",
    "https://acme.example/return", "t-1", Map("memberId" -> "m"), CustomerFields(email = Some(Personal("p@example.com"))))

  test("an initiate carries the payment id as the key, the amount in minor units and the customer fields; the answer is read") {
    assertEquals(client.initiate(page("pay-1")), Right(Initiated("https://gateway.example/pay/pay-1", "psp-pay-1", None)))
    val sent = gw.initiates.head.json.get
    assertEquals((sent.textOr("paymentId", ""), sent.long("amount"), sent.textOr("customerEmail", "")), ("pay-1", Some(5000L), "p@example.com"))
    assertEquals(sent.get("terminalProperties").get("memberId").asText, "m")
  }

  test("a crypto destination is read") {
    gw.crypto = true
    assert(client.initiate(page("pay-2")).toOption.get.crypto.exists(_.paymentAddress == "bc1-pay-2"))
  }

  test("a player-safe refusal is read as the player's; any other non-2xx is a failure; no answer is unreachable") {
    gw.refusePlayer = Some("Minimum deposit is 10.00")
    assertEquals(client.initiate(page("pay-3")), Left(GatewayRefusal.Player("Minimum deposit is 10.00")))
    gw.reset(); gw.failWith = Some(502)
    assert(client.initiate(page("pay-4")).left.exists(_.isInstanceOf[GatewayRefusal.Failure]))
    val nowhere = GatewayClient.http(DirectServices(Map("psp-gateway" -> "http://127.0.0.1:1")))
    assert(nowhere.initiate(page("pay-5")).left.exists(_.isInstanceOf[GatewayRefusal.Unreachable]))
  }

  test("a session reads pending, resolved, undelivered, refused, unknown or unreadable") {
    assertEquals(client.session("p-a"), SessionStatus.Pending)
    gw.session("p-b", io.eitheror.casino.merchant.it.FakeGateway.Session.Resolved); assertEquals(client.session("p-b"), SessionStatus.Resolved(Some("failed")))
    gw.session("p-c", io.eitheror.casino.merchant.it.FakeGateway.Session.Undelivered); assertEquals(client.session("p-c"), SessionStatus.Undelivered)
    gw.session("p-r", io.eitheror.casino.merchant.it.FakeGateway.Session.Refused); assertEquals(client.session("p-r"), SessionStatus.Refused)
    gw.session("p-d", io.eitheror.casino.merchant.it.FakeGateway.Session.Unknown); assertEquals(client.session("p-d"), SessionStatus.Unknown)
    gw.session("p-e", io.eitheror.casino.merchant.it.FakeGateway.Session.Down); assert(client.session("p-e").isInstanceOf[SessionStatus.Unreadable])
  }
