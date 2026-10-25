package io.eitheror.casino.ingest.shape

import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.Json.*

/** The public create into 004's command (contracts/merchant.md §1): the key's sub-brand, the customer fields as sent. */
class ShapeSuite extends munit.FunSuite:
  private val checked = Checked(5000, "EUR", "goldvibe:p1", "GB", "https://example.test/return", Some("t-1"))
  private val body = Json.parseObject("""{"amountCents":5000,"currency":"EUR","customerId":"goldvibe:p1","country":"GB",
    "redirectUrl":"https://example.test/return","transactionId":"t-1","subBrandId":"someone-else","customerEmail":"p@example.com",
    "customerFirstName":"Ada","customerPostCode":"LS1 1AA"}""").get

  test("every field 004 names is there, spelt as 004 spells it") {
    val c = Shape.toCommand("sb-1", "t-1", checked, body, "1.2.3.4")
    assertEquals((c.text("subBrandId"), c.text("transactionId"), c.long("amount"), c.text("currency"), c.text("country")),
      (Some("sb-1"), Some("t-1"), Some(5000L), Some("EUR"), Some("GB")))
    assertEquals((c.text("customerId"), c.text("redirectUrl"), c.text("clientIp")), (Some("goldvibe:p1"), Some("https://example.test/return"), Some("1.2.3.4")))
    assertEquals((c.text("customerEmail"), c.text("customerFirstName"), c.text("customerPostCode")), (Some("p@example.com"), Some("Ada"), Some("LS1 1AA")))
    assert(c.get("amountCents") == null)
  }

  test("the body's own subBrandId never reaches the command (FR-003)") {
    assertEquals(Shape.toCommand("sb-1", "t-1", checked, body, "").text("subBrandId"), Some("sb-1"))
  }

  test("the client's address is the first forwarded-for entry, else empty") {
    assertEquals(Shape.clientIp(Some("1.2.3.4, 10.0.0.1")), "1.2.3.4")
    assertEquals(Shape.clientIp(Some(" 203.0.113.9 ")), "203.0.113.9")
    assertEquals(Shape.clientIp(None), "")
  }
