package io.eitheror.casino.pspgateway.it

/** T030: the service starts, and each prefix admits its audience and no other (contracts/routes.md). */
class SmokeSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  test("/status answers the project's caller") {
    val r = kit.request("GET", "/status", headers = kit.asService("local", "ingest"))  // the kit runs unnamed: its project is `local`
    assertEquals(r.status, 200, r.text)
    assert(r.text.contains("DREAMZPAY"), r.text)
  }

  test("the initiate refuses payouts and a staff token (403)") {
    assertEquals(kit.request("POST", "/v1/psp/hpp/initiate", Some("{}"), kit.asPayouts("goldvibe")).status, 403)
    assertEquals(kit.request("POST", "/v1/psp/hpp/initiate", Some("{}"), kit.asStaff).status, 403)
  }

  test("the withdrawals refuse merchant (403)") {
    assertEquals(kit.request("POST", "/v1/payments/withdrawals", Some("{}"), kit.asMerchant).status, 403)
  }

  test("the operators' outcomes need a token (401)") {
    assertEquals(kit.request("GET", "/v1/psp/outcomes/undelivered", headers = kit.asBrowser).status, 401)
  }

  test("a webhook with no identity reaches the handler") {
    val r = kit.request("POST", "/v1/webhooks/dreamzpay", Some("status=Y"), kit.asProvider, "application/x-www-form-urlencoded")
    assert(r.status != 401 && r.status != 403, s"${r.status} ${r.text}")
  }

  test("a return page with no identity is 200 text/html, beside /v1/psp's identity routes (research R2)") {
    val r = kit.request("GET", "/v1/psp/checkout/t365/x/return", headers = kit.asBrowser)
    assertEquals(r.status, 200, r.text)
    assert(r.contentType.startsWith("text/html"), r.contentType)
    assert(r.text.contains("Your payment is being processed"), r.text)
  }
