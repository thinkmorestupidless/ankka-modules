package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.providers.dreamzpay.DreamzPay
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.time.Instant

/** `dreamzpay/signature_test.go`, `handler/failure_paths_test.go` and `handler/session_secrets_test.go`'s DreamzPay cases. */
class DreamzPaySuite extends munit.FunSuite:

  private val secureKey = "sk-test"
  private val credential = Credential(Map("memberId" -> "member-1", "totype" -> "sale", "secureKey" -> secureKey,
    "terminalId" -> "dp-terminal-1"))

  private def charge(ref: String, amount: Long, method: String = "CARD_VISA") = Charge(
    paymentId = ref, amount = amount, currency = Currency.unsafe("EUR"), method = method, terminalId = Some("terminal-twin-1"),
    returnUrl = s"https://psp.example.test/v1/psp/checkout/dreamzpay/$ref/return",
    webhookUrl = "https://psp.example.test/v1/webhooks/dreamzpay"
  )

  private val noCall: Call = _ => fail("DreamzPay makes no outbound call")
  private val now          = Instant.parse("2026-10-18T10:00:00Z")

  private def json(fields: (String, String)*): Raw =
    Raw(Json.render(Json.obj(fields*)), Some("application/json"))
  private def form(fields: (String, String)*): Raw =
    Raw(Form(fields.toVector).encode, Some("application/x-www-form-urlencoded"))

  // ── signature_test.go ───────────────────────────────────────────────────────

  test("TestOutboundChecksum_StableForIdenticalInputs"):
    val a = DreamzPay.outboundChecksum("MEM001", "CARD", "50.00", "ref-abc", "https://gw.example.com/relay", "s3cret")
    val b = DreamzPay.outboundChecksum("MEM001", "CARD", "50.00", "ref-abc", "https://gw.example.com/relay", "s3cret")
    assertEquals(a, b)

  test("TestOutboundChecksum_MatchesKnownVector"):
    val got = DreamzPay.outboundChecksum("MEM001", "CARD", "50.00", "ref-abc", "https://gw.example.com/relay", "s3cret")
    assertEquals(got, "a375e260bcef06a351637a0cf10ae979")
    assertEquals(got.length, 32)

  test("TestOutboundChecksum_DifferentInputsProduceDifferentDigests"):
    val base = DreamzPay.outboundChecksum("MEM001", "CARD", "50.00", "ref-abc", "https://gw.example.com/relay", "s3cret")
    assertNotEquals(DreamzPay.outboundChecksum("MEM001", "CARD", "50.00", "ref-abc", "https://gw.example.com/relay", "other-key"), base)
    assertNotEquals(DreamzPay.outboundChecksum("MEM002", "CARD", "50.00", "ref-abc", "https://gw.example.com/relay", "s3cret"), base)
    assertNotEquals(DreamzPay.outboundChecksum("MEM001", "CARD", "99.99", "ref-abc", "https://gw.example.com/relay", "s3cret"), base)

  private def signed(paymentId: String, ref: String, amount: String, status: String, key: String = secureKey) =
    json("paymentId" -> paymentId, "merchantTransactionId" -> ref, "amount" -> amount, "transactionStatus" -> status,
      "checksum" -> DreamzPay.inboundChecksum(paymentId, ref, amount, status, key))

  test("TestVerifyInbound_ValidChecksum"):
    assertEquals(DreamzPay.inboundChecksum("PAY-9999", "ref-xyz", "100.00", "Y", "webhook-secret"), "b057cc077ed9a33ebb2890feb21f034b")
    val raw = signed("PAY-9999", "ref-xyz", "100.00", "Y", "webhook-secret")
    assert(DreamzPay.verify(raw, Credential(Map("secureKey" -> "webhook-secret"))).isRight)

  test("TestVerifyInbound_TamperedChecksum"):
    val raw = json("paymentId" -> "PAY-9999", "merchantTransactionId" -> "ref-xyz", "amount" -> "100.00",
      "transactionStatus" -> "Y", "checksum" -> "000000000000000000000000deadbeef")
    assertEquals(DreamzPay.verify(raw, Credential(Map("secureKey" -> "webhook-secret"))), Left(Rejected("checksum does not verify")))

  test("TestVerifyInbound_WrongSecureKey"):
    val raw = signed("PAY-1", "ref-1", "25.00", "Y", "correct-key")
    assert(DreamzPay.verify(raw, Credential(Map("secureKey" -> "wrong-key"))).isLeft)

  test("TestVerifyInbound_FieldOrderMatters"):
    val swapped = json("paymentId" -> "PAY-1", "merchantTransactionId" -> "ref-1", "amount" -> "10.00",
      "transactionStatus" -> "Y", "checksum" -> DreamzPay.inboundChecksum("ref-1", "PAY-1", "10.00", "Y", "key"))
    assert(DreamzPay.verify(signed("PAY-1", "ref-1", "10.00", "Y", "key"), Credential(Map("secureKey" -> "key"))).isRight)
    assert(DreamzPay.verify(swapped, Credential(Map("secureKey" -> "key"))).isLeft)

  // ── failure_paths_test.go ───────────────────────────────────────────────────

  test("TestDreamzPayWebhookUsesTransactionStatusNotDescriptiveStatus: the checksum is over transactionStatus"):
    val raw = json("paymentId" -> "dp-real-1", "merchantTransactionId" -> "txn-real-shape-1", "amount" -> "10.00",
      "status" -> "capturesuccess", "transactionStatus" -> "Y",
      "checksum" -> DreamzPay.inboundChecksum("dp-real-1", "txn-real-shape-1", "10.00", "Y", secureKey))
    val handled = DreamzPay.webhook(raw, charge("txn-real-shape-1", 1000), credential, now)
    assertEquals(handled.verdict, Verdict.Settle(Outcome.Captured(), "Y"))
    assertEquals(handled.reply, Reply(200, """{"paymentId":"dp-real-1","result":"accepted"}""", "application/json"))

  test("TestDreamzPayWebhookUsesTransactionStatusForFailureToo"):
    val raw = json("paymentId" -> "dp-real-2", "merchantTransactionId" -> "txn-real-shape-2", "amount" -> "10.00",
      "status" -> "capturefailed", "transactionStatus" -> "N",
      "checksum" -> DreamzPay.inboundChecksum("dp-real-2", "txn-real-shape-2", "10.00", "N", secureKey))
    val handled = DreamzPay.webhook(raw, charge("txn-real-shape-2", 1000), credential, now)
    assertEquals(handled.verdict, Verdict.Settle(Outcome.Failed(Some("N"), "Payment declined"), "N"))
    assertEquals(handled.reply.status, 200)

  test("TestDreamzPayWebhookNotifiesFailureOnNonSuccessStatus"):
    val handled = DreamzPay.webhook(signed("dp-1", "txn-decline-1", "50.00", "N"), charge("txn-decline-1", 5000), credential, now)
    assertEquals(handled.verdict, Verdict.Settle(Outcome.Failed(Some("N"), "Payment declined"), "N"))
    assertEquals(handled.reply.status, 200)

  test("TestDreamzPayWebhookAcceptsFormEncodedBody"):
    val raw = form("paymentId" -> "dp-form-1", "merchantTransactionId" -> "txn-form-1", "amount" -> "10.00",
      "transactionStatus" -> "Y", "checksum" -> DreamzPay.inboundChecksum("dp-form-1", "txn-form-1", "10.00", "Y", secureKey))
    assertEquals(DreamzPay.locate(raw), Right(Located.Deposit("txn-form-1")))
    assertEquals(DreamzPay.webhook(raw, charge("txn-form-1", 1000), credential, now).verdict, Verdict.Settle(Outcome.Captured(), "Y"))

  test("TestDreamzPayWebhookNotifiesFailureOnFormEncodedDecline"):
    val raw = form("paymentId" -> "dp-form-2", "merchantTransactionId" -> "txn-form-2", "amount" -> "10.00",
      "transactionStatus" -> "N", "checksum" -> DreamzPay.inboundChecksum("dp-form-2", "txn-form-2", "10.00", "N", secureKey))
    assertEquals(DreamzPay.webhook(raw, charge("txn-form-2", 1000), credential, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("N"), "Payment declined"), "N"))

  test("a form body sent without a content type is still read, JSON being the fallback"):
    val raw = form("paymentId" -> "p", "merchantTransactionId" -> "txn-3", "amount" -> "1.00").copy(contentType = None)
    assertEquals(DreamzPay.locate(raw), Right(Located.Deposit("txn-3")))
    val jsonAsForm = signed("p", "txn-4", "1.00", "Y").copy(contentType = Some("application/x-www-form-urlencoded"))
    assertEquals(DreamzPay.locate(jsonAsForm), Right(Located.Deposit("txn-4")))

  // ── answers (webhook.go:139-247) ────────────────────────────────────────────

  test("a bad checksum answers 400 invalid_checksum and settles nothing"):
    val raw     = signed("dp-5", "txn-5", "10.00", "Y", "another-key")
    val handled = DreamzPay.webhook(raw, charge("txn-5", 1000), credential, now)
    assert(handled.verdict.isInstanceOf[Verdict.Reject])
    assertEquals(handled.reply, Reply(400, """{"error":"invalid_checksum"}""", "application/json"))

  test("an unknown transaction answers 200 unknown_txn with the paymentId, as Go's map encodes it"):
    assertEquals(DreamzPay.unknown(signed("dp-6", "txn-6", "10.00", "Y")),
      Reply(200, """{"paymentId":"dp-6","result":"unknown_txn"}""", "application/json"))

  test("a body naming no transaction is unknown; a body that reads as neither shape is 400"):
    assertEquals(DreamzPay.locate(Raw("", Some("application/json"))),
      Left(Reply(200, """{"paymentId":"","result":"unknown_txn"}""", "application/json")))
    assertEquals(DreamzPay.locate(Raw("{not json", Some("application/json"))).left.map(_.status), Left(400))

  test("cross-check (FR-013): a signed success for another amount, currency or reference does not verify"):
    val otherAmount = DreamzPay.webhook(signed("dp-7", "txn-7", "10.00", "Y"), charge("txn-7", 5000), credential, now)
    assertEquals(otherAmount.verdict, Verdict.Reject("amount does not match the session"))
    assertEquals(otherAmount.reply.status, 400)
    val otherRef = DreamzPay.webhook(signed("dp-7", "txn-7", "10.00", "Y"), charge("txn-8", 1000), credential, now)
    assertEquals(otherRef.verdict, Verdict.Reject("reference does not match the session"))
    val usd = json("paymentId" -> "dp-9", "merchantTransactionId" -> "txn-9", "amount" -> "10.00", "currency" -> "USD",
      "transactionStatus" -> "Y", "checksum" -> DreamzPay.inboundChecksum("dp-9", "txn-9", "10.00", "Y", secureKey))
    assertEquals(DreamzPay.webhook(usd, charge("txn-9", 1000), credential, now).verdict,
      Verdict.Reject("currency does not match the session"))

  // ── adapter.go:22-105 ───────────────────────────────────────────────────────

  test("initiate answers the relay page with the checkout form; the reference is the payment id"):
    val c = charge("7f4878aa-29d7-4b10-a572-b326e23ac03b", 5000)
    DreamzPay.initiate(c, credential, Customer(), "https://dreamzpay.example/", noCall) match
      case Initiation.Redirected(url, reference, data, Some(relay)) =>
        assertEquals(url, "https://psp.example.test/v1/psp/checkout/dreamzpay/7f4878aa-29d7-4b10-a572-b326e23ac03b")
        assertEquals(reference, None)
        assertEquals(data, Map("relayAction" -> "https://dreamzpay.example/transaction/Checkout"))
        assertEquals(relay.get("amount"), Some("50.00"))
        assertEquals(relay.get("currency"), Some("EUR"))
        assertEquals(relay.get("merchantTransactionId"), Some(c.paymentId))
        assertEquals(relay.get("merchantRedirectUrl"), Some(c.returnUrl))
        assertEquals(relay.get("notificationUrl"), Some(c.webhookUrl))
        assertEquals(relay.get("terminalId"), Some("dp-terminal-1"))
        assertEquals(relay.get("checksum"),
          Some(DreamzPay.outboundChecksum("member-1", "sale", "50.00", c.paymentId, c.returnUrl, secureKey)))
        assertEquals(relay.keys, relay.keys.sorted)
      case other => fail(s"expected a relay redirect, got $other")

  test("terminalId.{METHOD} overrides terminalId; neither leaves the field out"):
    val perMethod = Credential(credential.values + ("terminalId.CARD_VISA" -> "dp-visa"))
    assertEquals(DreamzPay.checkoutForm(charge("r", 100), perMethod).map(_.get("terminalId")), Right(Some("dp-visa")))
    val none = Credential(credential.values - "terminalId")
    assertEquals(DreamzPay.checkoutForm(charge("r", 100), none).map(_.get("terminalId")), Right(None))

  test("a missing memberId, totype or secureKey is the original's error, naming keys only"):
    val missing = Credential(credential.values - "totype")
    assertEquals(DreamzPay.initiate(charge("r", 100), missing, Customer(), "https://d.example", noCall),
      Initiation.Unavailable("dreamzpay: missing required credentials (memberId, totype, secureKey)"))

  test("TestDreamzPaySessionCarriesTerminalIdNotSecureKey: nothing the session records holds the secure key"):
    val initiation = DreamzPay.initiate(charge("r-1", 5000), credential, Customer(), "https://d.example", noCall)
    assert(!initiation.toString.contains(secureKey), initiation.toString)
    initiation match
      case Initiation.Redirected(_, _, data, relay) =>
        assert(!data.values.exists(_.contains(secureKey)))
        assert(!relay.toVector.flatMap(_.entries).exists(_._2.contains(secureKey)))
      case other => fail(s"unexpected $other")
