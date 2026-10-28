package io.eitheror.casino.pspgateway.providers

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.providers.digitarium.Digitarium
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.time.Instant
import java.util.Base64
import scala.collection.mutable

/** `digitarium/adapter_test.go` and `handler/webhook_digitarium_test.go`, ported. */
class DigitariumSuite extends munit.FunSuite with LogCapturing:
  private val eur  = Currency.unsafe("EUR")
  private val base = "https://engine-sandbox.g2pay.test"
  private val now  = Instant.parse("2026-10-13T10:00:00Z")

  private val dmTxnRef     = "txn-dig-abc-123"
  private val dmG2PayId    = "6e290467be0d4a1a9f237b0182dbca6b"
  private val dmSigningKey = "test-signing-key-unit-only"

  private def charge(amount: Long = 1000, currency: Currency = eur, data: Map[String, String] = Map("g2payId" -> dmG2PayId)) =
    Charge(dmTxnRef, amount, currency, "CARD", None, s"https://gw/digitarium/$dmTxnRef/return", "https://gw/digitarium",
      providerData = data)

  private final class Script(answer: Either[CallFailure, Answered]):
    val calls = mutable.Buffer.empty[Outbound]
    val call: Call = out =>
      calls += out
      answer

  private val answeredOk = Answered(200, Map.empty,
    s"""{"timestamp":"2026-10-13T10:00:00Z","status":200,"result":{"id":"$dmG2PayId","referenceId":"$dmTxnRef","state":"CHECKOUT","redirectUrl":"https://pay.g2pay.test/c/1"}}""")

  // ── adapter_test.go ──────────────────────────────────────────────────────────────────────────

  test("TestMapState"):
    def state(s: String) = Digitarium.outcome(Json.obj("referenceId" -> "r", "state" -> s))
    assertEquals(state("COMPLETED"), Verdict.Settle(Outcome.Captured(), "COMPLETED"))
    assert(state("DECLINED").isInstanceOf[Verdict.Settle])
    assert(state("CANCELLED").isInstanceOf[Verdict.Settle])
    Seq("CHECKOUT", "PENDING", "AUTHORIZED", "unknown", "").foreach(s => assertEquals(state(s), Verdict.Open(s)))

  test("TestVerifyWebhook: hex either case, base64 std or url, padded or not; nothing else"):
    val key    = "v6u70pm8BLVd"
    val body   = """{"id":"abc","referenceId":"txn-123","state":"COMPLETED"}"""
    val digest = Digests.hmacSha256(key, body)
    val hexSig = Digests.hex(digest)
    val cases = Seq(
      ("hex lowercase", key, body, hexSig, true),
      ("hex uppercase", key, body, hexSig.toUpperCase, true),
      ("base64 std", key, body, Base64.getEncoder.encodeToString(digest), true),
      ("base64 raw std", key, body, Base64.getEncoder.withoutPadding.encodeToString(digest), true),
      ("base64 url", key, body, Base64.getUrlEncoder.encodeToString(digest), true),
      ("base64 raw url", key, body, Base64.getUrlEncoder.withoutPadding.encodeToString(digest), true),
      ("surrounding whitespace", key, body, s" $hexSig\n", true),
      ("wrong key", "wrongkey", body, hexSig, false),
      ("tampered body", key, """{"tampered":true}""", hexSig, false),
      ("truncated hex", key, body, hexSig.take(40), false),
      ("garbage", key, body, "not-a-signature!", false),
      ("empty signature", key, body, "", false),
      ("empty key", "", body, hexSig, false)
    )
    cases.foreach { (name, k, b, sig, want) => assertEquals(Digitarium.verify(b, sig, k), want, name) }

  test("TestParseWebhook: flat, wrapped, and neither"):
    val flat = Digitarium.payment(Json.parse("""{"id":"g2-1","referenceId":"txn-1","state":"COMPLETED"}""").get)
    assertEquals((flat.get("referenceId").asText, flat.get("id").asText), ("txn-1", "g2-1"))
    val wrapped = Digitarium.payment(Json.parse(
      """{"timestamp":"2026-10-13T10:00:00Z","status":200,"result":{"id":"g2-2","referenceId":"txn-2","state":"DECLINED","errorCode":"05"}}""").get)
    assertEquals((wrapped.get("referenceId").asText, wrapped.get("state").asText, wrapped.get("errorCode").asText),
      ("txn-2", "DECLINED", "05"))
    assert(!Digitarium.payment(Json.parse("""{"id":"g2-3","state":"COMPLETED"}""").get).has("referenceId"))
    assertEquals(Digitarium.locate(Raw("not json")).left.map(_.status), Left(400))

  test("TestAmountDecimal: minor units to the number sent"):
    Seq(1000L -> "10", 5050L -> "50.5", 100L -> "1", 1L -> "0.01").foreach { (minor, want) =>
      val s = Script(Right(answeredOk))
      Digitarium.initiate(charge(minor), Credential(Map("apiKey" -> "k")), Customer(), base, s.call)
      assert(s.calls.head.body.get.contains(s""""amount":$want,"""), s.calls.head.body.get)
    }

  test("TestInitiateRejectsNonTwoDecimalCurrency: refused naming the currency, before any call"):
    Seq("JPY", "KRW", "KWD").foreach { code =>
      val s = Script(Right(answeredOk))
      Digitarium.initiate(charge(1000, Currency.unsafe(code)), Credential(Map("apiKey" -> "k")), Customer(), base, s.call) match
        case Initiation.Refused("unsupported_currency", m) => assert(m.contains(code) && m.contains("not a 2-decimal currency"), m)
        case other                                         => fail(s"$code: $other")
      assert(s.calls.isEmpty)
    }

  test("TestResolveBaseUrl: production only for production, the sandbox otherwise"):
    assertEquals(Digitarium.host(Credential.empty), "DIGITARIUM_SANDBOX_URL")
    assertEquals(Digitarium.host(Credential(Map("environment" -> "sandbox"))), "DIGITARIUM_SANDBOX_URL")
    assertEquals(Digitarium.host(Credential(Map("environment" -> "production"))), "DIGITARIUM_PRODUCTION_URL")
    assertEquals(Digitarium.host(Credential(Map("environment" -> "Production"))), "DIGITARIUM_SANDBOX_URL", "as the original, exact")

  test("TestSessionStorePrunesExpiredEntries: the session is the entity's now; what it keeps is g2payId"):
    Digitarium.initiate(charge(), Credential(Map("apiKey" -> "k")), Customer(), base, Script(Right(answeredOk)).call) match
      case Initiation.Redirected(url, reference, data, None) =>
        assertEquals(url, "https://pay.g2pay.test/c/1")
        assertEquals(reference, Some(dmG2PayId))
        assertEquals(data, Map("g2payId" -> dmG2PayId))
      case other => fail(s"expected a redirect, got $other")

  test("the request: Bearer apiKey, DEPOSIT by BASIC_CARD, the payment id, the gateway's addresses"):
    val s = Script(Right(answeredOk))
    Digitarium.initiate(charge(), Credential(Map("apiKey" -> "api-key-1")), Customer(), base, s.call)
    val out = s.calls.head
    assertEquals(out.url, s"$base/api/v1/payments")
    assert(out.headers.contains("Authorization" -> "Bearer api-key-1"))
    assertEquals(out.body.get,
      s"""{"referenceId":"$dmTxnRef","paymentType":"DEPOSIT","paymentMethod":"BASIC_CARD","amount":10,"currency":"EUR",""" +
        s""""returnUrl":"https://gw/digitarium/$dmTxnRef/return","webhookUrl":"https://gw/digitarium"}""")

  test("the answer: 401 a bad key, 4xx/5xx, no redirectUrl, or a failed call leave the provider unavailable"):
    Seq[Either[CallFailure, Answered]](
      Right(Answered(401, Map.empty, "")), Right(Answered(500, Map.empty, "{}")), Right(Answered(200, Map.empty, "<html/>")),
      Right(Answered(200, Map.empty, """{"result":{"state":"DECLINED","errorCode":"05"}}""")), Left(CallFailure.TimedOut)
    ).foreach { a =>
      val r = Digitarium.initiate(charge(), Credential(Map("apiKey" -> "k")), Customer(), base, Script(a).call)
      assert(r.isInstanceOf[Initiation.Unavailable], s"$a: $r")
    }
    assertEquals(Digitarium.redirectFrom(Answered(401, Map.empty, "")), Initiation.Unavailable("digitarium: invalid API key (HTTP 401)"))

  test("a missing apiKey is refused, naming the key"):
    assertEquals(Digitarium.initiate(charge(), Credential(Map("signingKey" -> "s")), Customer(), base, Script(Right(answeredOk)).call),
      Initiation.Refused("missing_credentials", "digitarium: missing required credential (apiKey)"))

  // ── handler/webhook_digitarium_test.go ───────────────────────────────────────────────────────

  private def dmBody(state: String, ref: String = dmTxnRef) =
    s"""{"id":"$dmG2PayId","referenceId":"$ref","paymentType":"DEPOSIT","state":"$state","amount":10.0,"currency":"EUR"}"""

  private def dmWrappedBody(state: String, ref: String = dmTxnRef) =
    s"""{"timestamp":"2026-10-13T10:00:00Z","status":200,"result":{"id":"$dmG2PayId","referenceId":"$ref","paymentType":"DEPOSIT","state":"$state","amount":10.0,"currency":"EUR"}}"""

  private def dmSign(key: String, body: String) = Digests.hex(Digests.hmacSha256(key, body))

  private def raw(body: String, sig: String) =
    Raw(body, Some("application/json"), if sig.isEmpty then Vector.empty else Vector("Signature" -> sig))

  private val signing = Credential(Map("apiKey" -> "k", "signingKey" -> dmSigningKey))

  private def deliver(body: String, sig: String, cr: Credential = signing, c: Charge = charge()) =
    Digitarium.webhook(raw(body, sig), c, cr, now)

  test("TestDigitariumWebhook_CompletedTriggersCapture"):
    val body = dmBody("COMPLETED")
    assertEquals(Digitarium.locate(raw(body, dmSign(dmSigningKey, body))), Right(Located.Deposit(dmTxnRef)))
    assertEquals(deliver(body, dmSign(dmSigningKey, body)), Handled(Verdict.Settle(Outcome.Captured(), "COMPLETED"), Reply.ok))

  test("TestDigitariumWebhook_DeclinedTriggersFail: no errorMessage, the generic reason"):
    val body = dmBody("DECLINED")
    assertEquals(deliver(body, dmSign(dmSigningKey, body)).verdict,
      Verdict.Settle(Outcome.Failed(None, "Payment declined"), "DECLINED"))

  test("TestDigitariumWebhook_CancelledTriggersFail: errorCode and errorMessage carried"):
    val body = s"""{"id":"$dmG2PayId","referenceId":"$dmTxnRef","state":"CANCELLED","errorCode":"05","errorMessage":"Do not honour"}"""
    assertEquals(deliver(body, dmSign(dmSigningKey, body)),
      Handled(Verdict.Settle(Outcome.Failed(Some("05"), "Do not honour"), "CANCELLED"), Reply.ok))

  test("TestDigitariumWebhook_WrappedPayloadIsAccepted"):
    val body = dmWrappedBody("COMPLETED")
    assertEquals(Digitarium.locate(raw(body, "")), Right(Located.Deposit(dmTxnRef)))
    assertEquals(deliver(body, dmSign(dmSigningKey, body)).verdict, Verdict.Settle(Outcome.Captured(), "COMPLETED"))

  test("TestDigitariumWebhook_Base64SignatureIsAccepted"):
    val body = dmBody("COMPLETED")
    assertEquals(deliver(body, Digests.base64(Digests.hmacSha256(dmSigningKey, body))).verdict,
      Verdict.Settle(Outcome.Captured(), "COMPLETED"))

  test("TestDigitariumWebhook_NonTerminalStateAcknowledgedWithoutCallback"):
    Seq("CHECKOUT", "PENDING", "AUTHORIZED").foreach { s =>
      val body = dmBody(s)
      assertEquals(deliver(body, dmSign(dmSigningKey, body)), Handled(Verdict.Open(s), Reply.ok), s)
    }

  test("TestDigitariumWebhook_WrongKeyReturns400"):
    val body = dmBody("COMPLETED")
    assertEquals(deliver(body, dmSign("wrong-key", body)), Handled(Verdict.Reject("invalid signature"), Reply.text(400, "invalid signature\n")))

  test("TestDigitariumWebhook_MissingSignatureHeaderReturns400"):
    assertEquals(deliver(dmBody("COMPLETED"), "").reply.status, 400)

  test("TestDigitariumWebhook_UnknownTxnRefReturns200"):
    assertEquals(Digitarium.unknown(raw(dmBody("COMPLETED", "unknown-txn-ref"), "")), Reply.ok)

  test("TestDigitariumWebhook_SubstrateErrorReturns502: the session store unreadable"):
    assertEquals(Digitarium.sessionUnreadable, Reply.text(502, "session lookup failed\n"))

  test("TestDigitariumWebhook_SessionRecoveredFromSubstrate: verified against the session's recorded g2payId"):
    val body = dmBody("COMPLETED")
    assertEquals(deliver(body, dmSign(dmSigningKey, body), c = charge(data = Map("g2payId" -> dmG2PayId))).verdict,
      Verdict.Settle(Outcome.Captured(), "COMPLETED"))

  test("TestDigitariumWebhook_DuplicateDeliveryRepostsIdempotentOutcome: a repeat reads the same, answered 200"):
    val body = dmBody("COMPLETED")
    val sig  = dmSign(dmSigningKey, body)
    assertEquals(deliver(body, sig), deliver(body, sig))
    assertEquals(deliver(body, sig).reply, Reply.ok)

  test("TestDigitariumWebhook_MerchantServiceErrorReturns502"):
    assertEquals(Digitarium.credentialUnreadable, Reply.text(502, "credential lookup failed\n"))

  test("TestDigitariumWebhook_MissingSigningKeyReturns500"):
    val body = dmBody("COMPLETED")
    assertEquals(deliver(body, dmSign("unused", body), Credential(Map("apiKey" -> "k"))),
      Handled(Verdict.Reject("no signing key"), Reply.text(500, "signing key not configured\n")))

  test("TestDigitariumWebhook_MalformedBodyReturns400"):
    assertEquals(Digitarium.locate(raw("not json", dmSign(dmSigningKey, "not json"))),
      Left(Reply.text(400, "invalid request body\n")))

  test("TestDigitariumWebhook_MissingReferenceIdReturns400"):
    val body = """{"id":"abc","state":"COMPLETED","amount":10.0,"currency":"EUR"}"""
    assertEquals(Digitarium.locate(raw(body, dmSign(dmSigningKey, body))), Left(Reply.text(400, "missing referenceId\n")))

  // ── what the port adds ───────────────────────────────────────────────────────────────────────

  test("the cross-check: another payment id, amount or currency does not settle the session (FR-013)"):
    def signed(body: String) = deliver(body, dmSign(dmSigningKey, body))
    val otherId = s"""{"id":"other","referenceId":"$dmTxnRef","state":"COMPLETED"}"""
    assertEquals(signed(otherId).verdict, Verdict.Reject("payment id does not match the session"))
    assertEquals(signed(otherId).reply.status, 400)
    assertEquals(signed(dmBody("COMPLETED").replace("10.0", "10.01")).verdict, Verdict.Reject("amount does not match the session"))
    assertEquals(signed(dmBody("COMPLETED").replace("EUR", "USD")).verdict, Verdict.Reject("currency does not match the session"))
    assertEquals(signed(dmBody("COMPLETED", "txn-other")).verdict, Verdict.Reject("reference does not match the session"))
    val bare = s"""{"referenceId":"$dmTxnRef","state":"COMPLETED"}"""
    assertEquals(signed(bare).verdict, Verdict.Settle(Outcome.Captured(), "COMPLETED"), "nothing reported, nothing to compare")
