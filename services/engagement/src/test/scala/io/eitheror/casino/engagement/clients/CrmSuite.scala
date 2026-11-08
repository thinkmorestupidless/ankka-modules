package io.eitheror.casino.engagement.clients

import io.eitheror.casino.engagement.Json

/** The calls out of contracts/routes.md: the bodies, the hosts by region, and which answers a step retries. */
class CrmSuite extends munit.FunSuite:
  test("an identify names the person and carries the attributes, FR-003's nine as numbers") {
    val b = Json.render(CustomerIo.identifyBody("p-1", Vector("id" -> "p-1", "deposit_count" -> "3", "real_money" -> "12.50",
      "created_at" -> "not-a-number", "currency" -> "EUR")))
    assertEquals(b, """{"type":"person","identifiers":{"id":"p-1"},"action":"identify","attributes":{"id":"p-1","deposit_count":3,"real_money":12.50,"created_at":"not-a-number","currency":"EUR"}}""")
  }

  test("an event carries its name; a deletion only the person") {
    assertEquals(Json.render(CustomerIo.trackBody("p-1", "deposit_confirmed", Vector("amount" -> "10.00"))),
      """{"type":"person","identifiers":{"id":"p-1"},"action":"event","name":"deposit_confirmed","attributes":{"amount":10.00}}""")
    assertEquals(Json.render(CustomerIo.deleteBody("p-1")), """{"type":"person","identifiers":{"id":"p-1"},"action":"delete"}""")
  }

  test("a transactional send's message data is all strings, in order") {
    assertEquals(Json.render(CustomerIo.sendBody("7", "ada@example.com", Vector("verification_link" -> "https://x", "amount" -> "5.00"))),
      """{"transactional_message_id":"7","to":"ada@example.com","identifiers":{"email":"ada@example.com"},"message_data":{"verification_link":"https://x","amount":"5.00"}}""")
  }

  test("the hosts are the region's") {
    assertEquals((CustomerIo.trackHost("us"), CustomerIo.appHost("us")), ("https://track.customer.io", "https://api.customer.io"))
    assertEquals((CustomerIo.trackHost("eu"), CustomerIo.appHost("eu")), ("https://track-eu.customer.io", "https://api-eu.customer.io"))
  }

  test("a 2xx is accepted, a 429 or a 5xx retried, any other answer refused by its status") {
    assertEquals(CustomerIo.answer(200), CrmAnswer.Accepted(200))
    assert(CustomerIo.answer(429).isInstanceOf[CrmAnswer.Retryable])
    assert(CustomerIo.answer(503).isInstanceOf[CrmAnswer.Retryable])
    assertEquals(CustomerIo.answer(400), CrmAnswer.Refused(400, "Customer.io refused 400"))
    assertEquals(CustomerIo.answer(401), CrmAnswer.Refused(401, "Customer.io refused 401"))
  }

  test("an unreachable host is retried, and a missing key refused without a call") {
    val crm = CustomerIo(Some("http://127.0.0.1:9"), Some("http://127.0.0.1:9"), java.time.Duration.ofSeconds(2))
    val c   = CrmCredentials("site", "us", Some("tk"), None)
    assert(crm.identify(c, "p-1", Vector.empty).isInstanceOf[CrmAnswer.Retryable])
    assert(crm.send(c, "7", "a@b", Vector.empty).isInstanceOf[CrmAnswer.Refused])
  }
