package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.providers.t365.T365
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.time.Instant

/** `t365/signature_test.go`, `handler/session_secrets_test.go`'s T365 case, and `adapter.go`/`webhook.go:410-548`. */
class T365Suite extends munit.FunSuite:

  private val apiKey     = "t365-api-key"
  private val secretKey  = "t365-secret-key"
  private val credential = Credential(Map("apiKey" -> apiKey, "secretKey" -> secretKey, "midId" -> "123"))
  private val paymentId  = "7f4878aa-29d7-4b10-a572-b326e23ac03b"
  private val orderId    = "7f4878aa29d74b10a572b326e23ac03b"
  private val now        = Instant.parse("2026-10-18T10:00:00Z")

  private def charge(amount: Long = 5000, currency: String = "EUR", method: String = "BANK_TRANSFER") = Charge(
    paymentId = paymentId, amount = amount, currency = Currency.unsafe(currency), method = method, terminalId = None,
    returnUrl = s"https://psp.example.test/v1/psp/checkout/t365/$paymentId/return",
    webhookUrl = "https://psp.example.test/v1/webhooks/t365"
  )

  private val customer = Customer(email = Some("player@example.test"), ip = Some("1.2.3.4"), country = Some("DE"),
    firstName = Some("Ada"), lastName = Some("Lovelace"))

  private def scripted(status: Int, body: String): (Call, () => Vector[Outbound]) =
    var seen = Vector.empty[Outbound]
    val call: Call = out =>
      seen :+= out
      Right(Answered(status, Map("content-type" -> "application/json"), body))
    (call, () => seen)

  // ── signature_test.go ───────────────────────────────────────────────────────

  test("TestPadSecretKey"):
    assertEquals(T365.padSecretKey("abc"), "abc" + "0" * 13)
    assertEquals(T365.padSecretKey("abc").length, 16)
    assertEquals(T365.padSecretKey("exactly16chars!!"), "exactly16chars!!")
    assertEquals(T365.padSecretKey("longer_than_sixteen_characters"), "longer_than_sixteen_characters")

  test("TestSignIsUppercaseHex"):
    val hash = T365.sign("secret", "50.00", "USD", "order1234567890", "SUCCESS")
    assertEquals(hash.length, 64)
    assertEquals(hash, hash.toUpperCase)

  test("TestSignDeterministic"):
    assertEquals(T365.sign("mykey", "100.00", "EUR", "orderabc1234567", "PENDING"),
      T365.sign("mykey", "100.00", "EUR", "orderabc1234567", "PENDING"))

  test("TestVerifyRoundTrip (and the vector, the key unpadded at 32 characters)"):
    val hash = T365.sign("qvDTnhrUqykIpG7cvifm99YRXzhguMfw", "50.00", "USD", "abc1234567890abcdef", "SUCCESS")
    assertEquals(hash, "AC3F51BA48DA81BEA462B27094117B58AF1BFDB7BD3DA4B82A2C098ECEEB1B61")
    val n = T365.Notification("t-1", "abc1234567890abcdef", "SUCCESS", Some("50.00"), Some("USD"), hash)
    assert(T365.verify(n, charge(), Credential(Map("secretKey" -> "qvDTnhrUqykIpG7cvifm99YRXzhguMfw")), None).isRight)

  test("the padded key is what is signed: secret becomes secret0000000000"):
    assertEquals(T365.sign("secret", "50.00", "USD", "o", "SUCCESS"),
      Digests.sha256Hex("secretKey=secret0000000000&amount=50.00&currency=USD&id=o&orderId=o&status=SUCCESS").toUpperCase)

  test("TestVerifyRejectsWrongHash"):
    val n = T365.Notification("t", "order1234567890", "SUCCESS", Some("50.00"), Some("USD"), "DEADBEEF")
    assert(T365.verify(n, charge(), Credential(Map("secretKey" -> "key")), None).isLeft)

  test("TestVerifyStatusSensitive"):
    val successHash = T365.sign("testkey", "25.00", "GBP", "order9876543210", "SUCCESS")
    val n           = T365.Notification("t", "order9876543210", "FAIL", Some("25.00"), Some("GBP"), successHash)
    assert(T365.verify(n, charge(), Credential(Map("secretKey" -> "testkey")), None).isLeft)

  // ── adapter.go:41-174 ───────────────────────────────────────────────────────

  test("initiate posts the hosted-payment request: apikey header, dashless orderId, midId a number, Go's field order"):
    val (call, seen) = scripted(200, """{"paymentUrl":"https://t365.example/pay/1","status":"OK","id":"T-77"}""")
    val initiation   = T365.initiate(charge(), credential, customer, "https://t365.example/", call)
    assertEquals(initiation, Initiation.Redirected("https://t365.example/pay/1", Some("T-77"), Map("orderId" -> orderId)))
    val out = seen().head
    assertEquals(out.method, "POST")
    assertEquals(out.url, "https://t365.example/api/v1/external/payment/hosted")
    assertEquals(out.headers, Vector("apikey" -> apiKey))
    assertEquals(out.body, Some(
      s"""{"amount":"50.00","callbackUrl":"https://psp.example.test/v1/webhooks/t365","country":"DE","currency":"EUR",""" +
        s""""email":"player@example.test","ip":"1.2.3.4","method":"BANK_TRANSFER","midId":123,"orderId":"$orderId",""" +
        s""""returnUrl":"https://psp.example.test/v1/psp/checkout/t365/$paymentId/return"}"""))

  test("a midId that is not a number is sent as a string; method codes map as the original's"):
    val (call, seen) = scripted(200, """{"paymentUrl":"https://t365.example/pay/2"}""")
    T365.initiate(charge(method = "CRYPTO_TRC20"), Credential(credential.values + ("midId" -> "FXMB")), Customer(),
      "https://t365.example", call)
    val body = Json.parseObject(seen().head.body.get).get
    assertEquals(body.get("midId").asText, "FXMB")
    assert(body.get("midId").isTextual)
    assertEquals(body.get("method").asText, "TRC20")
    assertEquals(body.get("email").asText, "")

  test("paymentUrl is required; an unreadable answer is unavailable; a failed call is unavailable"):
    assertEquals(T365.initiate(charge(), credential, customer, "https://t", scripted(400, """{"status":"ERR","message":"bad"}""")._1),
      Initiation.Unavailable("t365: response missing paymentUrl (HTTP 400 status=\"ERR\" message=\"bad\")"))
    assertEquals(T365.initiate(charge(), credential, customer, "https://t", scripted(502, "<html>")._1),
      Initiation.Unavailable("t365: failed to decode response (HTTP 502)"))
    assertEquals(T365.initiate(charge(), credential, customer, "https://t", _ => Left(CallFailure.TimedOut)),
      Initiation.Unavailable("t365: payment request timed out"))

  test("an unsupported method is refused; missing credentials are the original's error, naming keys only"):
    assertEquals(T365.initiate(charge(method = "CARD"), credential, customer, "https://t", scripted(200, "{}")._1),
      Initiation.Refused("unsupported_method", "t365: unsupported method \"CARD\""))
    assertEquals(T365.initiate(charge(), Credential(credential.values - "midId"), customer, "https://t", scripted(200, "{}")._1),
      Initiation.Unavailable("t365: missing required credentials (apiKey, secretKey, midId)"))

  test("TestT365SessionCarriesTerminalIdNotApiCredentials: nothing the session records holds a key"):
    val initiation = T365.initiate(charge(), credential, customer, "https://t",
      scripted(200, """{"paymentUrl":"https://t365.example/pay/1","id":"T-1"}""")._1)
    assert(!initiation.toString.contains(apiKey) && !initiation.toString.contains(secretKey), initiation.toString)

  // ── webhook.go:410-548 ──────────────────────────────────────────────────────

  private def postback(status: String, amount: Option[String] = Some("50.00"), currency: Option[String] = Some("EUR"),
      order: String = orderId, key: String = secretKey, headers: Vector[(String, String)] = Vector.empty): Raw =
    val hash = T365.sign(key, amount.getOrElse("50.00"), currency.getOrElse("EUR"), order, status)
    Raw(Json.render(Json.obj("id" -> "T-77", "orderId" -> order, "status" -> status, "amount" -> amount,
      "currency" -> currency, "hash" -> hash)), Some("application/json"), headers)

  test("locate answers the dashless orderId through the index (research R4)"):
    assertEquals(T365.locate(postback("SUCCESS")), Right(Located.Indexed(orderId)))
    assertEquals(T365.locate(Raw("not json")), Left(Reply.ok))
    assertEquals(T365.unknown(postback("SUCCESS")), Reply.ok)

  test("SUCCESS captures; REJECTED and FAIL fail Payment declined; PENDING is open; always 200"):
    assertEquals(T365.webhook(postback("SUCCESS"), charge(), credential, now), Handled(Verdict.Settle(Outcome.Captured(), "SUCCESS"), Reply.ok))
    assertEquals(T365.webhook(postback("REJECTED"), charge(), credential, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("REJECTED"), "Payment declined"), "REJECTED"))
    assertEquals(T365.webhook(postback("FAIL"), charge(), credential, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("FAIL"), "Payment declined"), "FAIL"))
    assertEquals(T365.webhook(postback("PENDING"), charge(), credential, now), Handled(Verdict.Open("PENDING"), Reply.ok))
    assert(T365.webhook(postback("WEIRD"), charge(), credential, now).verdict.isInstanceOf[Verdict.Ignore])

  test("without amount and currency in the postback the session's are signed"):
    assertEquals(T365.webhook(postback("SUCCESS", None, None), charge(), credential, now).verdict,
      Verdict.Settle(Outcome.Captured(), "SUCCESS"))

  test("a bad hash is rejected and answered 400 (the 2026-10-19 decision)"):
    assertEquals(T365.webhook(postback("SUCCESS", key = "other"), charge(), credential, now),
      Handled(Verdict.Reject("hash does not verify"), Reply.invalidSignature))

  test("the apikey header is checked only when present"):
    assertEquals(T365.webhook(postback("SUCCESS", headers = Vector("apikey" -> apiKey)), charge(), credential, now).verdict,
      Verdict.Settle(Outcome.Captured(), "SUCCESS"))
    assertEquals(T365.webhook(postback("SUCCESS", headers = Vector("APIKEY" -> "wrong")), charge(), credential, now).verdict,
      Verdict.Reject("apikey header does not match"))

  test("cross-check (FR-013): another amount, currency or order does not verify, and is answered 400"):
    def handled(p: Raw) = { val h = T365.webhook(p, charge(), credential, now); (h.verdict, h.reply) }
    assertEquals(handled(postback("SUCCESS", Some("10.00"))), (Verdict.Reject("amount does not match the session"), Reply.invalidSignature))
    assertEquals(handled(postback("SUCCESS", currency = Some("USD"))), (Verdict.Reject("currency does not match the session"), Reply.invalidSignature))
    assertEquals(handled(postback("SUCCESS", order = "0" * 32)), (Verdict.Reject("orderId does not match the session"), Reply.invalidSignature))
