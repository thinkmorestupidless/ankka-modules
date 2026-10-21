package io.eitheror.casino.pspgateway.delivery

import io.eitheror.casino.pspgateway.{Json, Scripted}
import io.eitheror.casino.pspgateway.sessions.{Outcome, Receiver, Variance}

/** Contracts/merchant.md §3, contracts/payouts.md: where an outcome goes, what it carries, and its answers. */
class ReceiversSuite extends munit.FunSuite:

  private val variance = Variance(Variance.Underpayment, Some("0.0012"), Some("0.0010"), Some("80.00"), Some("EUR"))

  test("a capture goes to merchant's capture route within the project, signed as given") {
    val s = Scripted(_ => Some(200 -> """{"ok":true}"""))
    val r = Receivers.http(s, "merchant")
    val body = Receivers.body(Receiver.Merchant("pay-1"), Outcome.Captured(), Some("cz-1"))
    val a = r.deliver(Receiver.Merchant("pay-1"), Outcome.Captured(), body, Vector("X-PSP-Signature" -> "ab", "X-PSP-Timestamp" -> "1"))
    assertEquals(a.status, Some(200))
    val call = s.calls.head
    assertEquals((call.project, call.name, call.method, call.path), ("payments", "merchant", "POST", "/v1/payments/pay-1/capture"))
    assertEquals(call.body, Some(body), "sent exactly as signed")
    assertEquals(call.headers.toMap.get("X-PSP-Signature"), Some("ab"))
  }

  test("the capture body: outcome success, psp_transaction_id and the variance where reported") {
    val plain = Json.parseObject(Receivers.body(Receiver.Merchant("pay-1"), Outcome.Captured(), None)).get
    assertEquals(Json.render(plain), """{"outcome":"success","psp_transaction_id":"pay-1"}""")
    val v = Json.parseObject(Receivers.body(Receiver.Merchant("pay-1"), Outcome.Captured(Some(variance)), Some("cz-1"))).get
    assertEquals(Json.text(v)("variance"), Some("UNDERPAYMENT"))
    assertEquals(Json.text(v)("receivedConvertAmount"), Some("80.00"))
    assertEquals(Json.text(v)("psp_transaction_id"), Some("cz-1"))
  }

  test("a failure goes to fail with failureCode, failureReason and the received amounts") {
    val f = Outcome.Failed(Some("03"), "Less crypto arrived than requested", Some(variance))
    assertEquals(Receivers.path(Receiver.Merchant("pay-1"), f), "/v1/payments/pay-1/fail")
    val b = Json.parseObject(Receivers.body(Receiver.Merchant("pay-1"), f, None)).get
    assertEquals(Json.text(b)("failureCode"), Some("03"))
    assertEquals(Json.text(b)("failureReason"), Some("Less crypto arrived than requested"))
    assertEquals(Json.text(b)("receivedCryptoAmount"), Some("0.0010"))
    assertEquals(Json.text(b)("variance"), None, "a failure carries the received amounts, not a variance (the original's body)")
  }

  test("a payout outcome goes to the brand's payouts under its grant, with outcome, providerReference and reason") {
    val s = Scripted(_ => Some(200 -> "{}"))
    val o = Outcome.PayoutFailed(None, "Payout cancelled")
    val body = Receivers.body(Receiver.Payouts("goldvibe", "wd-1"), o, Some("uf-9"))
    Receivers.http(s, "merchant").deliver(Receiver.Payouts("goldvibe", "wd-1"), o, body, Vector.empty): Unit
    val call = s.calls.head
    assertEquals((call.project, call.name, call.path), ("goldvibe", "payouts", "/v1/casino/brands/goldvibe/withdrawals/wd-1/outcome"))
    assertEquals(Json.render(Json.parseObject(body).get), """{"outcome":"failed","providerReference":"uf-9","reason":"Payout cancelled"}""")
  }

  test("each answer is read as the contract's table reads it") {
    def answered(status: Int, body: String) =
      Receivers.http(Scripted(_ => Some(status -> body)), "merchant").deliver(Receiver.Merchant("p"), Outcome.Captured(), "{}", Vector.empty)
    assertEquals(Backoff.read(answered(200, """{"ok":true}""").status), Backoff.Reading.Delivered)
    assertEquals(Backoff.read(answered(503, """{"error":"OUTCOME_PENDING"}""").status), Backoff.Reading.Retry)
    assertEquals(answered(503, """{"error":"OUTCOME_PENDING"}""").text, "OUTCOME_PENDING")
    assertEquals(Backoff.read(answered(429, "").status), Backoff.Reading.Retry)
    assertEquals(Backoff.read(answered(401, """{"error":"missing_token"}""").status), Backoff.Reading.Refused)
    assertEquals(Backoff.read(answered(403, """{"error":"invalid_token"}""").status), Backoff.Reading.Refused)
    val silent = Receivers.http(Scripted(_ => None), "merchant").deliver(Receiver.Merchant("p"), Outcome.Captured(), "{}", Vector.empty)
    assertEquals(silent.status, None)
    assertEquals(Backoff.read(silent.status), Backoff.Reading.Retry)
  }
