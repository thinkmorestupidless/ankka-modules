package io.eitheror.casino.ingest.shape

import io.eitheror.casino.ingest.Json

/** FR-007, contracts/routes.md's table: the first field that fails is named; nothing is defaulted. */
class FieldsSuite extends munit.FunSuite:
  private val valid: Map[String, Any] = Map("amountCents" -> 5000L, "currency" -> "EUR", "customerId" -> "goldvibe:p1", "country" -> "GB",
    "redirectUrl" -> "https://example.test/return", "transactionId" -> "t-1")

  private def body(changes: (String, Any)*): String =
    val removed = changes.collect { case (k, None) => k }.toSet
    val fields  = (valid ++ changes.filterNot(_._2 == None)).filterNot((k, _) => removed(k))
    Json.render(Json.obj(fields.toSeq*))

  private def check(text: String, bound: Int = 128) = Fields.check(Json.parse(text).get, bound)
  private def refused(text: String): String = check(text).left.toOption.map(_.field).getOrElse(fail(s"accepted: $text"))

  test("quickstart §5's body passes, and every customer field is left untouched") {
    val text = Json.render(Json.obj((valid.toSeq ++ Seq("customerEmail" -> "p@example.com", "customerCity" -> "Leeds"))*))
    assertEquals(check(text), Right(Checked(5000, "EUR", "goldvibe:p1", "GB", "https://example.test/return", Some("t-1"))))
  }

  // cashier.feature lines 85–91, one per row.
  test("an amount that is not a whole number of minor units") { assertEquals(refused("""{"amountCents":50.5,"currency":"EUR","customerId":"g:p","country":"GB","redirectUrl":"r"}"""), "amountCents") }
  test("no amount")               { assertEquals(refused(body("amountCents" -> None)), "amountCents") }
  test("no currency")             { assertEquals(refused(body("currency" -> None)), "currency") }
  test("no player id")            { assertEquals(refused(body("customerId" -> None)), "customerId") }
  test("a player id without the brand part") { assertEquals(refused(body("customerId" -> "p1")), "customerId") }
  test("no country")              { assertEquals(refused(body("country" -> None)), "country") }
  test("no return address")       { assertEquals(refused(body("redirectUrl" -> None)), "redirectUrl") }

  test("a zero, a negative, a string and an amount over Long are refused") {
    assertEquals(refused(body("amountCents" -> 0L)), "amountCents")
    assertEquals(refused(body("amountCents" -> -5L)), "amountCents")
    assertEquals(refused(body("amountCents" -> "5000")), "amountCents")
    assertEquals(refused(body("amountCents" -> 0L).replace("\"amountCents\":0", "\"amountCents\":92233720368547758070")), "amountCents")
  }

  test("a player id with an empty player part, and an empty string, are refused") {
    assertEquals(refused(body("customerId" -> "goldvibe:")), "customerId")
    assertEquals(refused(body("currency" -> "  ")), "currency")
  }

  test("the request id: absent is minted later, at the bound is accepted, over it or with a tab is refused") {
    assertEquals(check(body("transactionId" -> None)).map(_.transactionId), Right(None))
    assertEquals(check(body("transactionId" -> "x" * 128)).map(_.transactionId), Right(Some("x" * 128)))
    assertEquals(refused(body("transactionId" -> "x" * 129)), "transactionId")
    assertEquals(refused(body("transactionId" -> "t\t1")), "transactionId")
    assertEquals(refused(body("transactionId" -> 7L)), "transactionId")
  }

  test("the first failing field in the table's order is the one named") {
    assertEquals(refused(body("currency" -> None, "country" -> None)), "currency")
    assertEquals(refused(body("amountCents" -> None, "redirectUrl" -> None)), "amountCents")
  }
