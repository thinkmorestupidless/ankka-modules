package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.providers.bouncemoney.BounceMoney
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyPairGenerator, PublicKey, Signature}
import java.time.Instant
import java.util.Base64
import scala.collection.mutable

/** `bouncemoney/adapter_test.go`, `signature_test.go` and `handler/webhook_bouncemoney_test.go`, ported. */
class BounceMoneySuite extends munit.FunSuite:
  private val aud  = Currency.unsafe("AUD")
  private val usd  = Currency.unsafe("USD")
  private val base = "https://bm.test/api/v1"
  private val now  = Instant.ofEpochSecond(1750593911)

  /** A fresh Ed25519 pair, the private key as PKCS8 PEM (`signature_test.go:19-30`). */
  private def generateTestKey(): (PublicKey, String) =
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val b64  = Base64.getEncoder.encodeToString(pair.getPrivate.getEncoded).grouped(64).mkString("\n")
    (pair.getPublic, s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n")

  private def testHmac(secret: String, ts: Long, body: String): String =
    Digests.base64Url(Digests.hmacSha256(secret, s"$ts.$body"))

  private def verifies(pub: PublicKey, ts: String, body: String, header: String): Boolean =
    val v = Signature.getInstance("Ed25519")
    v.initVerify(pub)
    v.update(s"$ts.$body".getBytes(UTF_8))
    v.verify(Base64.getUrlDecoder.decode(header.stripPrefix("v1=")))

  private def creds(extra: (String, String)*) = Credential(Map("keyId" -> "bmk_test", "privateKey" -> generateTestKey()._2) ++ extra)

  private def charge(amount: Long = 5000, currency: Currency = aud) =
    Charge("pay-1", amount, currency, "CARD", None, "https://gw/bouncemoney/pay-1/return", "https://gw/bouncemoney",
      customerId = Some("cust-001"))

  private final class Script(answer: Answered):
    val calls = mutable.Buffer.empty[Outbound]
    val call: Call = out =>
      calls += out
      Right(answer)

  private def created(url: String = "https://checkout.banxa.com/order/mock") = Answered(201, Map.empty,
    s"""{"statusCode":201,"data":{"id":"0192f8c1-mock-uuid","url":"$url","status":"open","referenceId":"pay-1"}}""")

  private def initiate(c: Charge, cr: Credential, answer: Answered, customer: Customer = Customer()) =
    val s = Script(answer)
    (BounceMoney.initiateAt(c, cr, customer, base, s.call, now), s.calls.toVector)

  private def header(out: Outbound, name: String) = out.headers.collectFirst { case (k, v) if k == name => v }

  // ── signature_test.go: Ed25519 signing ────────────────────────────────────────────────────────

  test("TestSignRequest_ProducesV1Header"):
    val (_, pem) = generateTestKey()
    val headers  = BounceMoney.sign("bmk_test123", pem, 1750000000, """{"fiatAmount":50.00,"fiat":"AUD","referenceId":"test-ref-001"}""").toOption.get.toMap
    assertEquals(headers("X-BounceMoney-Key-Id"), "bmk_test123")
    assertEquals(headers("X-BounceMoney-Timestamp"), "1750000000")
    assert(headers("X-BounceMoney-Signature").startsWith("v1=") && headers("X-BounceMoney-Signature").length > 3)

  test("TestSignRequest_DifferentTimestampDifferentSignature"):
    val (_, pem) = generateTestKey()
    val sig      = (ts: Long) => BounceMoney.sign("bmk_test", pem, ts, """{"fiatAmount":10.00}""").toOption.get.last._2
    assertNotEquals(sig(1750000000), sig(1750000001))

  test("TestSignRequest_MalformedPEM"):
    assert(BounceMoney.sign("bmk_test", "not a valid pem", 1750000000, "{}").isLeft)

  test("TestSignRequest_KeyDamagedInTransit: every damaged shape signs as the pristine PEM does"):
    val (pub, pem) = generateTestKey()
    val body       = """{"fiatAmount":25.00,"fiat":"USD","referenceId":"damaged-key"}"""
    val want       = BounceMoney.sign("bmk_test", pem, 1750000000, body).toOption.get.last._2
    val stripped   = pem.trim.replace("\n", "")
    val bare       = stripped.stripPrefix("-----BEGIN PRIVATE KEY-----").stripSuffix("-----END PRIVATE KEY-----")
    val cases = Map(
      "newlines stripped by a single-line input" -> stripped,
      "newlines escaped by a JSON round trip"    -> pem.trim.replace("\n", "\\n"),
      "CRLF line endings"                        -> pem.trim.replace("\n", "\r\n"),
      "armour stripped entirely"                 -> bare,
      "surrounding whitespace"                   -> ("  \n\t" + pem + "  \n"),
      "indented armour"                          -> ("\t" + pem.trim.replace("\n", "\n\t")),
      "non-standard armour label"                -> s"-----BEGIN ED25519 PRIVATE KEY-----$bare-----END ED25519 PRIVATE KEY-----",
      "newlines replaced by spaces"              -> pem.trim.replace("\n", " ")
    )
    cases.foreach { (name, key) =>
      val got = BounceMoney.sign("bmk_test", key, 1750000000, body).fold(e => fail(s"$name: $e"), _.last._2)
      assertEquals(got, want, name)
      assert(verifies(pub, "1750000000", body, got), name)
    }

  test("TestParseEd25519Key_FixedVector: the four shapes recover the same key"):
    val header     = "-----BEGIN PRIVATE KEY-----"
    val footer     = "-----END PRIVATE KEY-----"
    val body       = "MC4CAQAwBQYDK2VwBCIEIGGx819/Cc3I0duqFR5RnNDNf0Yt7j+2TK0+2LwYqYRo"
    val wellFormed = s"$header\n$body\n$footer\n"
    val want       = BounceMoney.privateKey(wellFormed).toOption.get.getEncoded.toVector
    Map(
      "already well-formed"                -> wellFormed,
      "literal \\n escape sequences"       -> s"$header\\n$body\\n$footer\\n",
      "newlines stripped by browser input" -> s"$header$body$footer",
      "bare base64 body only"              -> body
    ).foreach { (name, in) =>
      assertEquals(BounceMoney.privateKey(in).map(_.getEncoded.toVector), Right(want), name)
    }

  test("TestSignRequest_EmptyKey"):
    assert(BounceMoney.sign("bmk_test", "   ", 1750000000, "{}").isLeft)

  test("TestSignRequest_NonEd25519Key: an RSA key in PKCS8 PEM is refused"):
    val rsaPem = "-----BEGIN PRIVATE KEY-----\nMIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC7o4qne60TB3wo\n-----END PRIVATE KEY-----"
    assert(BounceMoney.sign("bmk_test", rsaPem, 1750000000, "{}").isLeft)

  test("a parse error names the key's length, never its material"):
    val key = "-----BEGIN PRIVATE KEY MC4C"
    assertEquals(BounceMoney.privateKey(key), Left(s"PEM armour present but malformed (${key.length} chars)"))

  // ── signature_test.go: HMAC webhook verification ──────────────────────────────────────────────

  private val secret = "test-signing-secret-unit-only"

  test("TestVerifyWebhook_ValidSignature"):
    val ts   = 1750593911L
    val body = """{"id":"evt-001","type":"checkout.session.completed","data":{"referenceId":"txn-abc"}}"""
    assert(BounceMoney.verify(body, s"t=$ts,v1=${testHmac(secret, ts, body)}", secret, Instant.ofEpochSecond(ts)))

  test("TestVerifyWebhook_TamperedSignature"):
    assert(!BounceMoney.verify("""{"id":"evt-001"}""", "t=1750593911,v1=invalidsignaturevalue", secret, now))

  test("TestVerifyWebhook_WrongSecret"):
    val body = """{"id":"evt-001"}"""
    assert(!BounceMoney.verify(body, s"t=1750593911,v1=${testHmac("correct-secret", 1750593911, body)}", "wrong-secret", now))

  test("TestVerifyWebhook_StaleTimestamp: six minutes after is outside the window"):
    val body = """{"id":"evt-001"}"""
    assert(!BounceMoney.verify(body, s"t=1750593911,v1=${testHmac(secret, 1750593911, body)}", secret, now.plusSeconds(361)))

  test("TestVerifyWebhook_FutureTimestampWithinWindow: four minutes ahead is inside it"):
    val ts = 1750000300L
    assert(BounceMoney.verify("{}", s"t=$ts,v1=${testHmac("s", ts, "{}")}", "s", Instant.ofEpochSecond(ts - 240)))

  test("TestVerifyWebhook_MalformedHeaders"):
    Seq("", "v1=abc", "t=123", "garbage", "t=abc,v1=sig").foreach { h =>
      assert(!BounceMoney.verify("body", h, "secret", now), h)
    }

  test("an empty signing secret never verifies"):
    assert(!BounceMoney.verify("{}", s"t=${now.getEpochSecond},v1=x", "", now))

  // ── adapter_test.go ──────────────────────────────────────────────────────────────────────────

  test("TestInitiate_ReturnsRedirectURL"):
    val (result, calls) = initiate(charge(), creds(), created(), Customer(email = Some("player@example.com")))
    assertEquals(result, Initiation.Redirected("https://checkout.banxa.com/order/mock", Some("0192f8c1-mock-uuid")))
    val out = calls.head
    assertEquals(out.url, s"$base/checkout/sessions")
    val b = Json.parseObject(out.body.get).get
    assertEquals(b.get("referenceId").asText, "pay-1")
    assertEquals(b.get("description").asText, "Deposit pay-1")
    assertEquals(b.get("redirectUrl").asText, "https://gw/bouncemoney/pay-1/return")
    assertEquals(b.get("email").asText, "player@example.com")
    assertEquals(b.get("customerReference").asText, "cust-001")

  test("TestInitiate_SetsIdempotencyKeyHeader: the payment id, and the request signed over its exact body"):
    val (pub, pem) = generateTestKey()
    val (_, calls) = initiate(charge(), Credential(Map("keyId" -> "bmk_test", "privateKey" -> pem)), created())
    val out        = calls.head
    assertEquals(header(out, "Idempotency-Key"), Some("pay-1"))
    assertEquals(header(out, "X-BounceMoney-Key-Id"), Some("bmk_test"))
    assertEquals(header(out, "X-BounceMoney-Timestamp"), Some("1750593911"))
    assert(verifies(pub, "1750593911", out.body.get, header(out, "X-BounceMoney-Signature").get))

  test("TestInitiate_WithPaymentMethodId"):
    val (_, calls) = initiate(charge(), creds("paymentMethodId" -> "pay-id"), created())
    assert(calls.head.body.get.contains(""""paymentMethodId":"pay-id""""))

  test("TestInitiate_WithoutPaymentMethodId"):
    val (_, calls) = initiate(charge(), creds(), created())
    assert(!calls.head.body.get.contains("paymentMethodId"))

  test("TestInitiate_AmountConversion: 5000 cents is 50"):
    val (_, calls) = initiate(charge(), creds(), created())
    assert(calls.head.body.get.contains(""""fiatAmount":50,"""), calls.head.body.get)

  private def refusedMissing(cr: Credential) =
    val (result, calls) = initiate(charge(), cr, created())
    assert(calls.isEmpty)
    result match
      case Initiation.Refused("missing_credentials", m) => assert(m.contains("missing required credentials"))
      case other                                        => fail(s"expected a missing-credentials refusal, got $other")

  test("TestInitiate_MissingKeyId"):
    refusedMissing(Credential(Map("privateKey" -> generateTestKey()._2)))

  test("TestInitiate_MissingPrivateKey"):
    refusedMissing(Credential(Map("keyId" -> "bmk_test")))

  test("TestInitiate_WhitespaceOnlyCredentials: an unconfigured terminal, not a malformed key"):
    refusedMissing(Credential(Map("keyId" -> "   ", "privateKey" -> generateTestKey()._2)))
    refusedMissing(Credential(Map("keyId" -> "bmk_test", "privateKey" -> "  \n\t ")))

  test("TestInitiate_MalformedPrivateKey"):
    val (result, calls) = initiate(charge(), Credential(Map("keyId" -> "bmk_test", "privateKey" -> "not-a-pem")), created())
    assert(result.isInstanceOf[Initiation.Unavailable] && calls.isEmpty, result.toString)

  test("TestInitiate_NonCreatedStatusCode"):
    val (result, _) = initiate(charge(), creds(), Answered(409, Map.empty, """{"error":"CHECKOUT_REFERENCE_ID_IN_USE"}"""))
    assertEquals(result, Initiation.Unavailable("bouncemoney: CHECKOUT_REFERENCE_ID_IN_USE"))

  test("TestInitiate_EmptyURLInResponse"):
    val (result, _) = initiate(charge(), creds(), Answered(201, Map.empty, """{"statusCode":201,"data":{"id":"","url":""}}"""))
    assert(result.isInstanceOf[Initiation.Unavailable])

  test("TestInitiate_BelowMinimumAmount: a player error naming 15.00, before any call"):
    val (result, calls) = initiate(charge(1499, usd), creds(), created())
    assertEquals(result, Initiation.Refused("player_error", "minimum deposit amount is 15.00"))
    assert(calls.isEmpty)

  test("TestInitiate_InternalFailuresAreNotPlayerErrors"):
    val cases = Map(
      "upstream rejects the session" -> initiate(charge(5000, Currency.unsafe("XYZ")), creds(),
        Answered(400, Map.empty, """{"statusCode":400,"error":"Bad Request","message":"fiat not supported"}"""))._1,
      "upstream body will not decode" -> initiate(charge(5000, usd), creds(), Answered(201, Map.empty, "<html>502 Bad Gateway</html>"))._1,
      "terminal has no credentials"   -> initiate(charge(5000, usd), Credential.empty, created())._1
    )
    cases.foreach { (name, result) =>
      result match
        case Initiation.Refused("player_error", _) => fail(s"$name: an internal failure reached the player")
        case Initiation.Redirected(_, _, _, _)     => fail(s"$name: expected a failure")
        case _                                     => ()
    }

  test("TestInitiate_ExactMinimumAmount"):
    assert(initiate(charge(1500, usd), creds(), created())._1.isInstanceOf[Initiation.Redirected])

  test("TestUserMessage_StringMessage"):
    assertEquals(BounceMoney.userMessage(Json.parse("""{"statusCode":422,"error":"VALIDATION_ERROR","message":"Amount must be between 15.00 and 10000.00"}""").get),
      "Amount must be between 15.00 and 10000.00")

  test("TestUserMessage_ObjectMessage"):
    assertEquals(BounceMoney.userMessage(Json.parse("""{"statusCode":422,"error":"VALIDATION_ERROR","message":{"fiatAmount":"Amount must be between 15.00 and 10000.00 (got 10.00)"}}""").get),
      "Amount must be between 15.00 and 10000.00 (got 10.00)")

  test("TestUserMessage_FallsBackToErrorField"):
    assertEquals(BounceMoney.userMessage(Json.parse("""{"statusCode":403,"error":"MERCHANT_SETTLEMENT_WALLET_NOT_SET","message":null}""").get),
      "MERCHANT_SETTLEMENT_WALLET_NOT_SET")

  test("TestAmountDecimal: minor units to the decimal number sent"):
    Seq(5000L -> "50", 100L -> "1", 1L -> "0.01", 100000L -> "1000").foreach { (minor, want) =>
      val sent = initiate(charge(math.max(minor, 1500), usd), creds(), created())._2.head.body.get
      if minor >= 1500 then assert(sent.contains(s""""fiatAmount":$want,"""), sent)
      assertEquals(io.eitheror.casino.pspgateway.money.Render.decimalNumber(minor, usd).toPlainString, want)
    }

  test("TestBaseURL: no default host; the base is the installation's (research R10)"):
    val (_, calls) = initiate(charge(), creds(), created())
    assert(calls.head.url.startsWith(base))

  test("TestMapEventType"):
    def kind(t: String) = BounceMoney.outcome(Json.obj("type" -> t))
    assertEquals(kind("checkout.session.completed"), Verdict.Settle(Outcome.Captured(), "checkout.session.completed"))
    assert(kind("checkout.session.failed").isInstanceOf[Verdict.Settle])
    assert(kind("checkout.session.expired").isInstanceOf[Verdict.Settle])
    Seq("checkout.session.processing", "unknown.event", "").foreach(t => assert(kind(t).isInstanceOf[Verdict.Ignore], t))

  // ── handler/webhook_bouncemoney_test.go ──────────────────────────────────────────────────────

  private def bmBody(eventType: String, reference: String) =
    s"""{"id":"evt-001","type":"$eventType","created":1750593911,"data":{"id":"sess-001","referenceId":"$reference","status":"x"}}"""

  private def bmSign(s: String, ts: Long, body: String) = s"t=$ts,v1=${testHmac(s, ts, body)}"

  private def raw(body: String, sig: String) =
    Raw(body, Some("application/json"), if sig.isEmpty then Vector.empty else Vector("X-BounceMoney-Signature" -> sig))

  private val signing = Credential(Map("signingSecret" -> secret))

  private def deliver(body: String, sig: String, cr: Credential = signing, at: Instant = now) =
    BounceMoney.webhook(raw(body, sig), charge(), cr, at)

  test("TestBounceMoneyWebhook_Outcomes: completed captured, failed and expired failed, all answered 200"):
    Seq("checkout.session.completed" -> "captured", "checkout.session.failed" -> "failed", "checkout.session.expired" -> "failed")
      .foreach { (t, word) =>
        val body = bmBody(t, "pay-1")
        assertEquals(BounceMoney.locate(raw(body, bmSign(secret, now.getEpochSecond, body))), Right(Located.Deposit("pay-1")))
        val h = deliver(body, bmSign(secret, now.getEpochSecond, body))
        assertEquals(h.reply.status, 200)
        h.verdict match
          case Verdict.Settle(o, `t`) => assertEquals(o.word, word)
          case other                  => fail(s"$t: $other")
      }

  test("TestBounceMoneyWebhook_FailureCarriesTheReason: the reason the player sees, the type as the code"):
    Seq("checkout.session.failed" -> "Payment declined", "checkout.session.expired" -> "Payment session expired").foreach { (t, reason) =>
      val body = bmBody(t, "pay-1")
      assertEquals(deliver(body, bmSign(secret, now.getEpochSecond, body)).verdict,
        Verdict.Settle(Outcome.Failed(Some(t), reason), t))
    }

  test("TestBounceMoneyWebhook_InvalidSignatureIsRejectedWith400"):
    val body = bmBody("checkout.session.completed", "pay-1")
    val h    = deliver(body, bmSign("some-other-secret", now.getEpochSecond, body))
    assertEquals(h.verdict, Verdict.Reject("invalid signature"))
    assertEquals(h.reply, Reply.json(400, Json.obj("error" -> "invalid_signature")))

  test("TestBounceMoneyWebhook_UnsignedRequestIsRejectedBeforeAnyLookup"):
    Seq("", "t=1750593911", "v1=abc", "sha256=abc").foreach { h =>
      BounceMoney.locate(raw(bmBody("checkout.session.completed", "pay-1"), h)) match
        case Left(reply) => assertEquals(reply.status, 400, h)
        case Right(l)    => fail(s"'$h' located $l")
    }

  test("TestBounceMoneyWebhook_WellFormedSignatureStillVerifies"):
    val body = bmBody("checkout.session.completed", "pay-1")
    val sig  = s"t=${now.getEpochSecond},v1=not-the-right-mac"
    assert(BounceMoney.locate(raw(body, sig)).isRight, "a well-formed header reaches the session and its secret")
    assertEquals(deliver(body, sig).reply.status, 400)

  test("TestBounceMoneyWebhook_StaleTimestampIsRejected: a correctly signed replay settles nothing"):
    val body  = bmBody("checkout.session.completed", "pay-1")
    val stale = now.minusSeconds(6 * 60).getEpochSecond
    val h     = deliver(body, bmSign(secret, stale, body))
    assertEquals(h.reply.status, 400)
    assert(h.verdict.isInstanceOf[Verdict.Reject])

  test("TestBounceMoneyWebhook_UnknownTxnRefIsAcknowledged"):
    assertEquals(BounceMoney.unknown(raw(bmBody("checkout.session.completed", "never-initiated"), "t=1,v1=x")), Reply.ok)

  test("TestBounceMoneyWebhook_UnrecognisedEventTypeIsAcknowledged: nothing moves"):
    val body = bmBody("checkout.session.created", "pay-1")
    val h    = deliver(body, bmSign(secret, now.getEpochSecond, body))
    assertEquals(h.reply, Reply.ok)
    assert(h.verdict.isInstanceOf[Verdict.Ignore])

  test("TestBounceMoneyWebhook_DuplicateDeliveryDoesNotRefire: a repeat reads the same; the session settles once"):
    val body = bmBody("checkout.session.completed", "pay-1")
    val sig  = bmSign(secret, now.getEpochSecond, body)
    assertEquals(deliver(body, sig), deliver(body, sig))

  test("TestBounceMoneyWebhook_OurFailuresAreNotAcknowledged: no signing secret 500; unreadable 502"):
    val body = bmBody("checkout.session.completed", "pay-1")
    val h    = deliver(body, bmSign(secret, now.getEpochSecond, body), Credential(Map("keyId" -> "bmk_test")))
    assertEquals(h.reply.status, 500)
    assert(h.verdict.isInstanceOf[Verdict.Reject])
    assertEquals(BounceMoney.sessionUnreadable.status, 502)
    assertEquals(BounceMoney.credentialUnreadable, Reply.text(502, "credential lookup failed\n"))

  test("TestBounceMoneyWebhook_MalformedBodyIsRejected"):
    Seq("not json", """{"type":"checkout.session.completed","data":{}}""").foreach { body =>
      assertEquals(BounceMoney.locate(raw(body, bmSign(secret, now.getEpochSecond, body))).left.map(_.status), Left(400), body)
    }

  test("the cross-check: a signed event naming another session does not settle this one (FR-013)"):
    val body = bmBody("checkout.session.completed", "pay-2")
    val h    = deliver(body, bmSign(secret, now.getEpochSecond, body))
    assertEquals(h.verdict, Verdict.Reject("reference does not match the session"))
    assertEquals(h.reply.status, 400)

  test("the cross-check: a signed event for another amount or currency settles nothing (FR-013)"):
    def event(amount: String, fiat: String) =
      s"""{"id":"evt-001","type":"checkout.session.completed","created":1750593911,"data":{"id":"sess-001","referenceId":"pay-1","fiatAmount":$amount,"fiat":"$fiat"}}"""
    def verdict(body: String) = deliver(body, bmSign(secret, now.getEpochSecond, body)).verdict
    assertEquals(verdict(event("500.00", "AUD")), Verdict.Reject("amount does not match the session"))
    assertEquals(verdict(event("\"500.00\"", "AUD")), Verdict.Reject("amount does not match the session"))
    assertEquals(verdict(event("50.00", "EUR")), Verdict.Reject("currency does not match the session"))
    assertEquals(verdict(event("50", "aud")), Verdict.Settle(Outcome.Captured(), "checkout.session.completed"))
