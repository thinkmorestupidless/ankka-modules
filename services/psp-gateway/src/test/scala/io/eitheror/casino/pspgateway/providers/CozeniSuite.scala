package io.eitheror.casino.pspgateway.providers

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.providers.cozeni.Cozeni
import io.eitheror.casino.pspgateway.sessions.{Destination, Outcome, Variance}

import java.security.SecureRandom
import java.time.Instant

/** `cozeni/adapter_test.go` and `handler/callback_auth_test.go`'s Cozeni cases, with spec correction 2's three `03` cases. */
class CozeniSuite extends munit.FunSuite with LogCapturing:

  // `callback_auth_test.go:35-39`.
  val merchantId = "9876543210123"
  val apiKey     = "abcde12345fghijk67890"
  val salt       = "1234567890123"
  val credential = Credential(Map("accountId" -> "acc-001", "apiKey" -> apiKey, "merchantId" -> merchantId))
  val base       = "https://cozeni.test"
  val now        = Instant.parse("2026-10-18T12:00:00Z")

  def charge(ref: String = "cz-auth-ok", data: Map[String, String] = Map("cryptoAmount" -> "10.00000000")) = Charge(
    paymentId = ref, amount = 10000, currency = Currency.unsafe("USD"), method = "USDT", terminalId = Some("t-1"),
    returnUrl = "https://gw.test/return", webhookUrl = "https://gw.test/v1/webhooks/cozeni",
    generated = Map(Cozeni.SaltKey -> salt), providerData = data)

  def withdrawal(ref: String = "pay-ref-001", amount: Long = 9900, currency: String = "EUR") = Withdrawal(
    requestId = ref, brand = "goldvibe", amount = amount, currency = Currency.unsafe(currency), method = "CRYPTO",
    terminalId = "t-1", webhookUrl = "https://gw.test/v1/webhooks/cozeni/withdrawal",
    destination = Map("coin" -> "USDT", "address" -> "0xABCDEF1234567890"), generated = Map(Cozeni.SaltKey -> salt))

  def callback(ref: String, token: Option[String], status: String = "COMPLETED", resultCode: String = "00",
      received: String = "10.00", convert: String = "100.00", currency: String = "USD", resultMsg: String = ""): Raw =
    val fields = Vector("paymentRef" -> ref, "txnId" -> "54321", "status" -> status, "resultCode" -> resultCode,
      "receivedAmount" -> received, "convertAmount" -> convert, "convertCurrency" -> currency) ++
      Option(resultMsg).filter(_.nonEmpty).map("resultMsg" -> _) ++ token.map("txnauthz" -> _)
    Raw(Form(fields).encode, Some("application/x-www-form-urlencoded"))

  def sign(ref: String) = Cozeni.txnauthz(merchantId, salt, ref, apiKey)

  /** A scripted provider: every call is recorded, and answered by `answer`. */
  final class Script(answer: Outbound => Either[CallFailure, Answered]):
    var calls: Vector[Outbound] = Vector.empty
    val call: Call = o =>
      calls :+= o
      answer(o)

  def ok(body: String)                = Right(Answered(200, Map.empty, body))
  def answered(status: Int, body: String) = Right(Answered(status, Map.empty, body))

  // ── signature.go ────────────────────────────────────────────────────────────

  test("SignPayIn: hex SHA-256 of merchantId + salt + paymentRef + apiKey") {
    assertEquals(Cozeni.txnauthz("merchant-abc", "1234567890", "ref-xyz", "secret-key"),
      "8dad560d9da634405f4f3078eb7855aa1b343cf6e759015a31a6bb371a517d53")
    assertEquals(sign("cz-auth-ok"), "013ec6e86eb6142b8a4430385604d6ccd8ff6ac62f8523dca24e6f431bd05364")
  }

  test("TestVerifyCallback: a correct token verifies; a wrong token or another API key does not") {
    val ref   = "ref-xyz"
    val cred  = Credential(Map("merchantId" -> "merchant-abc", "apiKey" -> "secret-key"))
    val valid = Cozeni.txnauthz("merchant-abc", "1234567890", ref, "secret-key")
    assertEquals(Cozeni.verify(Form.of("txnauthz" -> valid), cred, Some("1234567890"), ref), Right(()))
    assert(Cozeni.verify(Form.of("txnauthz" -> "wrong-token"), cred, Some("1234567890"), ref).isLeft)
    val other = Credential(Map("merchantId" -> "merchant-abc", "apiKey" -> "different-key"))
    assert(Cozeni.verify(Form.of("txnauthz" -> valid), other, Some("1234567890"), ref).isLeft)
  }

  test("verify fails closed without the session's salt or the credential's keys") {
    val form = Form.of("txnauthz" -> sign("r"))
    assertEquals(Cozeni.verify(form, credential, None, "r"), Left("session carries no salt"))
    assertEquals(Cozeni.verify(form, Credential(Map("apiKey" -> apiKey)), Some(salt), "r"), Left("credential has no merchantId"))
  }

  test("generate makes a 13-digit salt, fresh each time") {
    val r = SecureRandom()
    val a = Cozeni.generate(r)(Cozeni.SaltKey)
    assert(a.matches("[1-9][0-9]{12}"), a)
    assertNotEquals(Cozeni.generate(r)(Cozeni.SaltKey), a)
  }

  // ── MapStatus ───────────────────────────────────────────────────────────────

  test("TestMapStatus: COMPLETED (any case), FAILED and CANCELLED final; PENDING, empty and unknown open") {
    import Cozeni.Status.*
    val cases = Vector("COMPLETED" -> Completed, "completed" -> Completed, "FAILED" -> Failed, "CANCELLED" -> Failed,
      "PENDING" -> Open, "" -> Open, "UNKNOWN_STATE" -> Open)
    cases.foreach((word, want) => assertEquals(Cozeni.status(word), want, word))
  }

  // ── Initiate ────────────────────────────────────────────────────────────────

  test("initiate asks the rate, then opens a pay-in for fiat ÷ rate to 8 places, keyed by the payment id") {
    val script = Script { o =>
      if o.url.contains("/pub/rate/") then ok("""{"rate": 1.001}""")
      else ok("""{"txnId":777,"status":"PENDING","paymentAddress":"TXyz","requestAmount":99.9000999,"validUntil":"2026-10-18T13:00:00Z"}""")
    }
    val result = Cozeni.initiate(charge("pay-1"), credential, Customer(), base, script.call)
    assertEquals(result, Initiation.Quoted(
      Destination.Crypto("TXyz", "99.90009990", "USDT", Some("2026-10-18T13:00:00Z")), Some("777"),
      Map("cryptoAmount" -> "99.90009990", "cozeniTxnId" -> "777")))

    val Vector(rate, payin) = script.calls
    assertEquals(rate.method, "GET")
    assertEquals(rate.url, "https://cozeni.test/pub/rate/USDT/USD")
    assertEquals(payin.url, s"https://cozeni.test/mxapi/$merchantId/payintxn")
    assertEquals(payin.headers, Vector("X-cozeni-txnauthz" -> Cozeni.txnauthz(merchantId, salt, "pay-1", apiKey)))
    val body = Json.parseObject(payin.body.get).get
    assertEquals(body.get("requestAmount").decimalValue.toPlainString, "99.90009990")
    assertEquals(body.get("accountId").asText, "acc-001")
    assertEquals(body.get("requestSalt").asText, salt)
    assertEquals(body.get("requestCurrency").asText, "USDT")
    assertEquals(body.get("convertCurrency").asText, "USD")
    assertEquals(body.get("paymentRef").asText, "pay-1")
    assertEquals(body.get("paymentDesc").asText, "deposit pay-1")
    assertEquals(body.get("callbackUrl").asText, "https://gw.test/v1/webhooks/cozeni")
    assert(body.get("dupCheck").asBoolean)
    assert(!payin.body.get.contains(apiKey), "the API key is only ever hashed")
  }

  test("a bare numeric rate is read as the original's fallback") {
    val script = Script(o => if o.url.contains("/pub/rate/") then ok(" 65000 ") else ok("""{"paymentAddress":"bc1q","requestAmount":0.00153846,"txnId":1}"""))
    val result = Cozeni.initiate(charge("p").copy(method = "BTC"), credential, Customer(), base, script.call)
    assert(result.isInstanceOf[Initiation.Quoted], result)
    assertEquals(Json.parseObject(script.calls(1).body.get).get.get("requestAmount").decimalValue.toPlainString, "0.00153846")
  }

  test("initiate is unavailable when the rate cannot be read, and calls no pay-in") {
    for answer <- Vector(answered(500, ""), ok("""{"rate":0}"""), ok("nope"), Left(CallFailure.TimedOut)) do
      val script = Script(_ => answer)
      assert(Cozeni.initiate(charge(), credential, Customer(), base, script.call).isInstanceOf[Initiation.Unavailable])
      assertEquals(script.calls.size, 1)
  }

  test("initiate is unavailable on a 422, another status, an unreadable answer or no address") {
    val rate = ok("""{"rate":1}""")
    for payin <- Vector(answered(422, """{"msgCode":"91","msgText":"unsupported"}"""), answered(500, ""), ok("{"),
        ok("""{"txnId":1,"resultCode":"99"}""")) do
      val script = Script(o => if o.url.contains("/pub/rate/") then rate else payin)
      assert(Cozeni.initiate(charge(), credential, Customer(), base, script.call).isInstanceOf[Initiation.Unavailable])
  }

  test("a missing credential key is named, never valued, and nothing is paid in") {
    val script = Script(o => if o.url.contains("/pub/rate/") then ok("""{"rate":1}""") else ok("{}"))
    val result = Cozeni.initiate(charge(), Credential(Map("accountId" -> "a", "apiKey" -> apiKey)), Customer(), base, script.call)
    assertEquals(result, Initiation.Unavailable("cozeni: missing merchantId credential"))
    assertEquals(script.calls.size, 1)
  }

  // ── Webhook (callback_auth_test.go) ─────────────────────────────────────────

  test("locate reads paymentRef; none named is acknowledged") {
    assertEquals(Cozeni.locate(callback("cz-1", None)), Right(Located.Deposit("cz-1")))
    assertEquals(Cozeni.locate(Raw("status=COMPLETED", Some("application/x-www-form-urlencoded"))), Left(Reply.ok))
    assertEquals(Cozeni.unknown(Raw("")), Reply.ok)
  }

  test("TestCozeniWebhookSettlesWithAValidToken") {
    val h = Cozeni.webhook(callback("cz-auth-ok", Some(sign("cz-auth-ok"))), charge("cz-auth-ok"), credential, now)
    assertEquals(h, Handled(Verdict.Settle(Outcome.Captured(), "COMPLETED"), Reply.ok))
  }

  test("TestCozeniWebhookRejectsAForgedToken") {
    val h = Cozeni.webhook(callback("cz-auth-forged", Some("0" * 64)), charge("cz-auth-forged"), credential, now)
    assertEquals(h.verdict, Verdict.Reject("invalid txnauthz"))
    assertEquals(h.reply, Reply.invalidSignature)
  }

  test("TestCozeniWebhookRejectsAMissingToken") {
    val h = Cozeni.webhook(callback("cz-auth-none", None), charge("cz-auth-none"), credential, now)
    assertEquals(h.verdict, Verdict.Reject("no txnauthz in callback body"))
    assertEquals(h.reply, Reply.invalidSignature)
  }

  test("a token signed for another payment does not verify against this session") {
    val h = Cozeni.webhook(callback("cz-a", Some(sign("cz-b"))), charge("cz-a"), credential, now)
    assertEquals(h.verdict, Verdict.Reject("invalid txnauthz"))
  }

  test("a session with no salt refuses the callback (webhook.go:860-863)") {
    val c = charge("cz-1").copy(generated = Map.empty)
    assertEquals(Cozeni.webhook(callback("cz-1", Some(sign("cz-1"))), c, credential, now).verdict,
      Verdict.Reject("session carries no salt"))
  }

  test("TestCozeniWebhookCreditsAnUnderpaymentAtTheReceivedValue (spec correction 2: 03 valued)") {
    val ref = "cz-underpay-1"
    val h = Cozeni.webhook(callback(ref, Some(sign(ref)), resultCode = "03", received = "5.00", convert = "49.50"),
      charge(ref), credential, now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Captured(Some(
      Variance("UNDERPAYMENT", Some("10.00000000"), Some("5.00"), Some("49.50"), Some("USD")))), "COMPLETED"))
  }

  test("TestCozeniWebhookRecordsAFailureWhenAnUnderpaymentIsUnpriced (spec correction 2: 03 unvalued)") {
    val ref = "cz-underpay-unpriced"
    for convert <- Vector("", "0", "0.00", "not-a-number", "-1") do
      val h = Cozeni.webhook(callback(ref, Some(sign(ref)), resultCode = "03", received = "5.00", convert = convert),
        charge(ref), credential, now)
      h.verdict match
        case Verdict.Settle(Outcome.Failed(code, reason, Some(received)), _) =>
          assertEquals(code, Some("03"), convert)
          assertEquals(reason, Cozeni.ReasonUnderpaymentUnpriced)
          assertEquals(received.receivedCryptoAmount, Some("5.00"), "the on-chain amount is what ops reconciles against")
        case other => fail(s"convertAmount=$convert: $other")
  }

  test("an underpayment missing its received fields still fails rather than captures (spec correction 2)") {
    val ref = "cz-underpay-bare"
    val raw = Raw(Form.of("paymentRef" -> ref, "status" -> "COMPLETED", "resultCode" -> "03", "txnauthz" -> sign(ref)).encode,
      Some("application/x-www-form-urlencoded"))
    assert(Cozeni.webhook(raw, charge(ref), credential, now).verdict match
      case Verdict.Settle(Outcome.Failed(Some("03"), _, _), _) => true
      case _                                                   => false)
  }

  test("TestCozeniWebhookCreditsOnFullPayment") {
    val ref = "cz-full-pay-1"
    val h = Cozeni.webhook(callback(ref, Some(sign(ref)), resultCode = "00"), charge(ref), credential, now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Captured(), "COMPLETED"))
  }

  test("TestCozeniWebhookRecordsAnOverpaymentOnTheCapture") {
    val ref = "cz-overpay-1"
    val h = Cozeni.webhook(callback(ref, Some(sign(ref)), resultCode = "02", received = "12.00", convert = "120.00"),
      charge(ref), credential, now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Captured(Some(
      Variance("OVERPAYMENT", Some("10.00000000"), Some("12.00"), Some("120.00"), Some("USD")))), "COMPLETED"))
  }

  test("FAILED and CANCELLED fail with resultMsg, or the original's reason; PENDING is open") {
    val ref = "cz-f"
    def verdict(status: String, msg: String) =
      Cozeni.webhook(callback(ref, Some(sign(ref)), status = status, resultCode = "10", resultMsg = msg), charge(ref), credential, now).verdict
    assertEquals(verdict("FAILED", "Timed out"), Verdict.Settle(Outcome.Failed(Some("10"), "Timed out"), "FAILED"))
    assertEquals(verdict("CANCELLED", ""), Verdict.Settle(Outcome.Failed(Some("10"), "Payment declined"), "CANCELLED"))
    assertEquals(verdict("PENDING", ""), Verdict.Open("PENDING"))
  }

  // ── Payouts (adapter_test.go) ───────────────────────────────────────────────

  test("a payout answered 401 or 403 is rejected unauthorised, whatever the body (the 2026-10-19 decision)") {
    assertEquals(Cozeni.payoutAnswer(Answered(401, Map.empty, "")), PayoutAnswer.Rejected("unauthorised"))
    assertEquals(Cozeni.payoutAnswer(Answered(403, Map.empty, "<html/>")), PayoutAnswer.Rejected("unauthorised"))
  }

  test("TestMapPayoutResultCode: 00 completed, 01 initiated, every other code rejected") {
    def answer(code: String) = Cozeni.payoutAnswer(Answered(200, Map.empty, s"""{"txnId":1,"resultCode":"$code","resultMsg":"m"}"""))
    assertEquals(answer("00"), PayoutAnswer.Completed(Some("1")))
    assertEquals(answer("01"), PayoutAnswer.Initiated(Some("1")))
    for code <- Vector("90", "91", "92", "93", "99", "", "02") do assertEquals(answer(code), PayoutAnswer.Rejected("m"), code)
  }

  test("TestPayout_INITIATED: payouttxn's own field names, not the deposit's") {
    val script = Script(_ => ok("""{"txnId":12345,"resultCode":"01","resultMsg":"Pending"}"""))
    assertEquals(Cozeni.payout(withdrawal(), credential, base, script.call), PayoutAnswer.Initiated(Some("12345")))
    val Vector(o) = script.calls
    assertEquals(o.method, "POST")
    assertEquals(o.contentType, Some("application/json"))
    assertEquals(o.url, s"https://cozeni.test/mxapi/$merchantId/payouttxn")
    assertEquals(o.headers, Vector("X-cozeni-txnauthz" -> Cozeni.txnauthz(merchantId, salt, "pay-ref-001", apiKey)))
    val body = Json.parseObject(o.body.get).get
    for f <- Vector("amount", "currency", "payoutCurrency", "paymentAddress") do assert(body.has(f), f)
    for f <- Vector("requestAmount", "requestCurrency", "convertCurrency", "destinationAddress") do assert(!body.has(f), f)
    assertEquals(body.get("payoutCurrency").asText, "USDT")
    assertEquals(body.get("paymentAddress").asText, "0xABCDEF1234567890")
    assertEquals(body.get("paymentRef").asText, "pay-ref-001")
    assertEquals(body.get("callbackUrl").asText, "https://gw.test/v1/webhooks/cozeni/withdrawal")
    assertEquals(body.get("requestSalt").asText, salt)
  }

  test("TestPayout_COMPLETED") {
    val script = Script(_ => ok("""{"txnId":99999,"resultCode":"00","resultMsg":"Success"}"""))
    assertEquals(Cozeni.payout(withdrawal("pay-ref-002", 5000, "USD"), credential, base, script.call),
      PayoutAnswer.Completed(Some("99999")))
  }

  test("TestPayout_422Rejected: an error there, in doubt here, as the handler reports it (payout.go:149-153)") {
    val script = Script(_ => answered(422, """{"msgCode":"92","msgText":"Invalid wallet address"}"""))
    assert(Cozeni.payout(withdrawal("pay-ref-003"), credential, base, script.call).isInstanceOf[PayoutAnswer.InDoubt])
  }

  test("a transport failure, another status or an unreadable body is in doubt") {
    for answer <- Vector(Left(CallFailure.TimedOut), Left(CallFailure.Unreachable("reset")), answered(500, ""), ok("<html>")) do
      assert(Cozeni.payout(withdrawal(), credential, base, Script(_ => answer).call).isInstanceOf[PayoutAnswer.InDoubt], answer)
  }

  test("TestPayout_FiatAmountConversion: 9900 minor units are sent as 99") {
    val script = Script(_ => ok("""{"txnId":1,"resultCode":"01","resultMsg":"Pending"}"""))
    Cozeni.payout(withdrawal("pay-ref-004"), credential, base, script.call)
    val amount = Json.parseObject(script.calls.head.body.get).get.get("amount")
    assert(amount.isNumber)
    assertEquals(amount.decimalValue.compareTo(java.math.BigDecimal("99.0")), 0)
  }

  test("a payout without the credential's keys is rejected missing_credentials, and nothing is called") {
    val script = Script(_ => ok("{}"))
    assertEquals(Cozeni.payout(withdrawal(), Credential(Map("apiKey" -> apiKey)), base, script.call),
      PayoutAnswer.Rejected("missing_credentials"))
    assert(script.calls.isEmpty)
  }

  test("the withdrawal callback verifies with the payout's salt and settles COMPLETED or FAILED") {
    val ref = "wr-1"
    val raw = callback(ref, Some(sign(ref)))
    assertEquals(Cozeni.locatePayout(raw), Right(Located.Payout(ref)))
    assertEquals(Cozeni.payoutWebhook(raw, withdrawal(ref), credential, now),
      Handled(Verdict.Settle(Outcome.Completed, "COMPLETED"), Reply.ok))
    val failed = callback(ref, Some(sign(ref)), status = "FAILED", resultCode = "92", resultMsg = "Invalid wallet address")
    assertEquals(Cozeni.payoutWebhook(failed, withdrawal(ref), credential, now).verdict,
      Verdict.Settle(Outcome.PayoutFailed(Some("92"), "Invalid wallet address"), "FAILED"))
    assertEquals(Cozeni.payoutWebhook(callback(ref, Some("0" * 64)), withdrawal(ref), credential, now),
      Handled(Verdict.Reject("invalid txnauthz"), Reply.invalidSignature))
    assertEquals(Cozeni.payoutWebhook(callback(ref, Some(sign(ref)), status = "PENDING"), withdrawal(ref), credential, now).verdict,
      Verdict.Open("PENDING"))
  }

  test("no credential value appears in a verdict, a reply or an answer") {
    val secrets = credential.secretValues
    val texts = Vector(
      Cozeni.webhook(callback("x", Some("0" * 64)), charge("x"), credential, now).toString,
      Cozeni.payout(withdrawal(), credential, base, Script(_ => answered(422, "")).call).toString,
      Cozeni.initiate(charge(), credential, Customer(), base, Script(_ => answered(500, "")).call).toString)
    for t <- texts; s <- secrets do assert(!t.contains(s), t)
  }
