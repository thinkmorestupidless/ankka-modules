package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.providers.gigadat.Gigadat
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import scala.collection.mutable

/** `gigadat/adapter_test.go`, `webhook_test.go`, `sessions_test.go` and `handler/gigadat_test.go`, ported. */
class GigadatSuite extends munit.FunSuite:
  private val cad  = Currency.unsafe("CAD")
  private val base = "https://gigadat.test"
  private val now  = Instant.parse("2026-10-18T12:00:00Z")

  private def creds(environment: String = "sandbox") = Credential(Map(
    "campaignId" -> "camp1", "accessToken" -> "at1", "securityToken" -> "st1", "siteUrl" -> "https://casino.com",
    "environment" -> environment
  ))

  private def charge(amount: Long = 2000, currency: Currency = cad) =
    Charge("pay-1", amount, currency, "INTERAC", None, "https://gw/gigadat/pay-1/return", "https://gw/gigadat",
      customerId = Some("cust1"))

  private def withdrawal(method: String, destination: Map[String, String], currency: Currency = cad, amount: Long = 5000) =
    Withdrawal("wr-001", "spinvibe", amount, currency, method, "term-1", "https://gw/gigadat", destination,
      customerId = Some("cu1"))

  /** A scripted provider: each call recorded, each answered by `answer`. */
  private final class Script(answer: Outbound => Either[CallFailure, Answered]):
    val calls = mutable.Buffer.empty[Outbound]
    val call: Call = out =>
      calls += out
      answer(out)

  private val tokenOk = Answered(200, Map.empty, """{"token":"tok123","data":{"transactionId":"gig-txn-456"}}""")

  private def body(out: Outbound) = Json.parseObject(out.body.get).get

  private def basicOf(out: Outbound): String =
    val h = out.headers.collectFirst { case ("Authorization", v) => v }.get
    String(Base64.getDecoder.decode(h.stripPrefix("Basic ")), UTF_8)

  private def form(typ: String, rcode: String, amount: String) = s"type=$typ&rcode=$rcode&amount=$amount"

  private def raw(reference: String, status: String, body: String) =
    Raw(body, Some("application/x-www-form-urlencoded"), query = Map("transactionId" -> reference, "status" -> status))

  // ── adapter_test.go ──────────────────────────────────────────────────────────────────────────

  test("TestInitiateCurrency: a currency other than CAD is refused before any call"):
    val s = Script(_ => Right(tokenOk))
    Gigadat.initiate(charge(5000, Currency.unsafe("EUR")), creds(), Customer(), base, s.call) match
      case Initiation.Refused("unsupported_currency", message) => assert(message.contains("EUR"))
      case other                                               => fail(s"expected a refusal, got $other")
    assert(s.calls.isEmpty)

  test("TestInitiateCPIBodyAndHeaders: Basic auth, CPI, CAD, digits-only mobile, province, sandbox, the redirect"):
    val s        = Script(_ => Right(tokenOk))
    val customer = Customer(email = Some("player@test.com"), firstName = Some("John"), lastName = Some("Doe"),
      mobile = Some("(416) 555-0100"), province = Some("ON"), ip = Some("1.2.3.4"))
    val result = Gigadat.initiate(charge(), creds(), customer, base, s.call)
    val out    = s.calls.head
    assertEquals(out.url, s"$base/api/payment-token/camp1")
    assertEquals(basicOf(out), "at1:st1")
    val b = body(out)
    assertEquals(b.get("type").asText, "CPI")
    assertEquals(b.get("currency").asText, "CAD")
    assertEquals(b.get("mobile").asText, "4165550100")
    assertEquals(b.get("province").asText, "ON")
    assertEquals(b.get("sandbox").asBoolean, true)
    assertEquals(b.get("transactionId").asText, "pay-1", "the payment id is the reference (R3)")
    assertEquals(b.get("userId").asText, "cust1")
    assertEquals(b.get("name").asText, "John Doe")
    assertEquals(b.get("site").asText, "https://casino.com")
    assertEquals(out.body.get.take(12), """{"amount":20""", "dollars as a number, first, as Go's encoder writes it")
    result match
      case Initiation.Redirected(url, reference, data, None) =>
        assertEquals(url, s"$base/webflow?transaction=gig-txn-456&token=tok123")
        assertEquals(reference, Some("gig-txn-456"))
        assertEquals(data, Map("gigadatTxnId" -> "gig-txn-456"))
      case other => fail(s"expected a redirect, got $other")

  test("TestInitiateSandboxFlagOmittedForProduction"):
    val s = Script(_ => Right(tokenOk))
    Gigadat.initiate(charge(1000), creds("production"), Customer(), base, s.call)
    assert(!body(s.calls.head).has("sandbox"))

  test("TestInitiateETOBodyNoProvince"):
    val s = Script(_ => Right(tokenOk))
    Gigadat.payout(withdrawal("ETO", Map("email" -> "p@test.com", "mobile" -> "4165550100", "firstName" -> "Jane",
      "lastName" -> "Smith")), creds(), base, s.call)
    val b = body(s.calls.head)
    assertEquals(b.get("type").asText, "ETO")
    assertEquals(b.get("transactionId").asText, "wr-001", "the request id is the reference (R3)")
    assertEquals(b.get("email").asText, "p@test.com")
    assert(!b.has("province"))

  test("TestInitiateACHBodyFields: acct, fi, transit; no email, mobile or province"):
    val s = Script(_ => Right(tokenOk))
    Gigadat.payout(withdrawal("ACH", Map("account" -> "12345678", "institution" -> "004", "transit" -> "00123",
      "firstName" -> "Bob", "lastName" -> "Builder"), amount = 7500), creds(), base, s.call)
    val b = body(s.calls.head)
    assertEquals(b.get("type").asText, "ACH")
    assertEquals(b.get("acct").asText, "12345678")
    assertEquals(b.get("fi").asText, "004")
    assertEquals(b.get("transit").asText, "00123")
    assertEquals(b.get("amount").decimalValue, java.math.BigDecimal("75"))
    assert(!b.has("email") && !b.has("mobile") && !b.has("province"))

  test("TestInitiatePayoutUnknownMethod"):
    val s = Script(_ => Right(tokenOk))
    assertEquals(Gigadat.payout(withdrawal("WIRE", Map.empty), creds(), base, s.call), PayoutAnswer.Rejected("unsupported_method"))
    assert(s.calls.isEmpty)

  // ── webhook_test.go ──────────────────────────────────────────────────────────────────────────

  private val pairs =
    """[{"key":"type","value":"CPI"},{"key":"rcode","value":"100"},{"key":"amount","value":"50"},""" +
      """{"key":"cookie","value":"abc123"},{"key":"transactionId","value":"TEST-001"}]"""

  test("TestParseWebhookBodyJSONArray: the array as a form's data value"):
    val n = Gigadat.notice("data=" + pairs)
    assert(n.recognised)
    assertEquals((n.kind, n.rcode, n.cookie, n.transactionId), ("CPI", "100", "abc123", "TEST-001"))

  test("TestParseWebhookBodyBareJSONArray"):
    val n = Gigadat.notice("""[{"key":"type","value":"ETO"},{"key":"rcode","value":"100"},{"key":"transactionId","value":"TEST-002"}]""")
    assert(n.recognised)
    assertEquals((n.kind, n.transactionId), ("ETO", "TEST-002"))

  test("TestParseWebhookBodyFlatForm"):
    val n = Gigadat.notice("type=ETO&rcode=201&amount=20&cookie=xyz")
    assert(n.recognised)
    assertEquals((n.kind, n.rcode), ("ETO", "201"))

  test("TestParseWebhookBodyUnrecognisedShape: a flat JSON object is not a shape Gigadat sends"):
    assert(!Gigadat.notice("""{"type":"CPI","rcode":"100"}""").recognised)

  test("TestMapStatusTerminal"):
    val cases = Seq(
      ("STATUS_SUCCESS", "100", true), ("STATUS_ABORTED1", "201", false), ("STATUS_EXPIRED", "230", false),
      ("STATUS_REJECTED", "231", false), ("STATUS_REJECTED1", "241", false), ("STATUS_REJECTED1", "248", false),
      ("STATUS_FAILED", "700", false), ("STATUS_FAILED", "600", false)
    )
    cases.foreach { (status, rcode, success) =>
      Gigadat.mapStatus(status, rcode) match
        case Some(Right(()))            => assert(success, status)
        case Some(Left((code, reason))) => assert(!success && code == rcode && reason.nonEmpty, s"$status/$rcode")
        case None                       => fail(s"$status should be terminal")
    }

  test("TestMapStatusTerminal: without an rcode the status is the code"):
    assertEquals(Gigadat.mapStatus("STATUS_EXPIRED", ""), Some(Left(("STATUS_EXPIRED", "Payment session expired"))))

  test("TestMapStatusNonTerminal"):
    assertEquals(Gigadat.mapStatus("STATUS_INITED", ""), None)

  test("TestMapStatusUnknown"):
    assertEquals(Gigadat.mapStatus("STATUS_WHATEVER", "999"), None)

  // ── sessions_test.go: the store is DepositSession's now; what Gigadat leaves it is providerData ──

  test("TestSessionStoreBasicOps: the Gigadat transaction id is what the session keeps"):
    Gigadat.initiate(charge(), creds(), Customer(), base, Script(_ => Right(tokenOk)).call) match
      case Initiation.Redirected(_, _, data, _) => assertEquals(data.get("gigadatTxnId"), Some("gig-txn-456"))
      case other                                => fail(s"expected a redirect, got $other")

  // ── handler/gigadat_test.go ──────────────────────────────────────────────────────────────────

  test("TestGigadatWebhookCPISuccessFiresCallback: captured, answered 200"):
    val r = raw("pay-1", "STATUS_SUCCESS", form("CPI", "100", "50.00"))
    assertEquals(Gigadat.locate(r), Right(Located.Deposit("pay-1")))
    val h = Gigadat.webhook(r, charge(5000), creds(), now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Captured(), "STATUS_SUCCESS"))
    assertEquals(h.reply.status, 200)

  test("TestGigadatWebhookCPIFailureFiresFailureNotification: failed with the rcode and a reason"):
    val h = Gigadat.webhook(raw("pay-1", "STATUS_REJECTED", form("CPI", "231", "50.00")), charge(5000), creds(), now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Failed(Some("231"), "Payment rejected by bank"), "STATUS_REJECTED"))
    assertEquals(h.reply.status, 200)

  test("TestGigadatWebhookCPINonTerminalStatusDoesNotFireCallback"):
    val h = Gigadat.webhook(raw("pay-1", "STATUS_INITED", form("CPI", "", "")), charge(5000), creds(), now)
    assertEquals(h.verdict, Verdict.Open("STATUS_INITED"))
    assertEquals(h.reply.status, 200)

  test("TestGigadatWebhookCPIUnknownTxnRefReturns200"):
    assertEquals(Gigadat.unknown(raw("txn-unknown", "STATUS_SUCCESS", form("CPI", "100", "50.00"))).status, 200)

  test("a webhook naming no session, or no kind, is acknowledged"):
    assertEquals(Gigadat.locate(raw("", "STATUS_SUCCESS", form("CPI", "100", "50.00"))), Left(Reply.ok))
    assertEquals(Gigadat.locate(raw("pay-1", "STATUS_SUCCESS", form("XYZ", "100", "50.00"))), Left(Reply.ok))

  test("TestGigadatWebhookETOSuccessNotifiesCasinoService: a payout, completed"):
    val r = raw("wr-001", "STATUS_SUCCESS", form("ETO", "100", "75.00"))
    assertEquals(Gigadat.locatePayout(r), Right(Located.Payout("wr-001")))
    val h = Gigadat.payoutWebhook(r, withdrawal("ETO", Map.empty, amount = 7500), creds(), now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Completed, "STATUS_SUCCESS"))
    assertEquals(h.reply.status, 200)

  test("TestGigadatWebhookACHSuccessNotifiesCasinoService"):
    val r = raw("wr-002", "STATUS_SUCCESS", form("ACH", "100", "150.00"))
    assertEquals(Gigadat.locate(r), Right(Located.Payout("wr-002")))
    assertEquals(Gigadat.payoutWebhook(r, withdrawal("ACH", Map.empty, amount = 15000), creds(), now).verdict,
      Verdict.Settle(Outcome.Completed, "STATUS_SUCCESS"))

  test("TestGigadatWebhookETOFailureNotifiesCasinoServiceWithFailedOutcome"):
    val h = Gigadat.payoutWebhook(raw("wr-003", "STATUS_REJECTED", form("ETO", "231", "75.00")),
      withdrawal("ETO", Map.empty, amount = 7500), creds(), now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.PayoutFailed(Some("231"), "Payment rejected by bank"), "STATUS_REJECTED"))

  test("TestGigadatPayoutInitiatesETO: token, then the two webflow GETs, initiated under the request id"):
    val s = Script(_ => Right(Answered(200, Map.empty, """{"token":"tok","data":{"transactionId":"gig-eto-1"}}""")))
    val answer = Gigadat.payout(withdrawal("ETO", Map("email" -> "player@test.com", "mobile" -> "4165550100",
      "firstName" -> "Jane", "lastName" -> "Doe")), creds(), base, s.call)
    assertEquals(answer, PayoutAnswer.Initiated(Some("wr-001")))
    assertEquals(body(s.calls.head).get("type").asText, "ETO")
    assertEquals(s.calls.drop(1).map(o => (o.method, o.url)).toVector, Vector(
      "GET" -> s"$base/webflow?transaction=gig-eto-1&token=tok",
      "GET" -> s"$base/webflow/deposit?transaction=gig-eto-1&token=tok"
    ))
    assert(s.calls.drop(1).forall(basicOf(_) == "at1:st1"))

  test("TestGigadatPayoutInitiatesACH"):
    val s = Script(_ => Right(Answered(200, Map.empty, """{"token":"tok","data":{"transactionId":"gig-ach-1"}}""")))
    val answer = Gigadat.payout(withdrawal("ACH", Map("account" -> "12345678", "institution" -> "004", "transit" -> "00123",
      "firstName" -> "Bob", "lastName" -> "Builder"), amount = 7500), creds(), base, s.call)
    assertEquals(answer, PayoutAnswer.Initiated(Some("wr-001")))
    val b = body(s.calls.head)
    assertEquals((b.get("type").asText, b.get("acct").asText, b.get("fi").asText, b.get("transit").asText),
      ("ACH", "12345678", "004", "00123"))

  test("TestGigadatPayoutRejectsUnknownPayoutMethod: rejected, never in doubt, nothing called"):
    val s = Script(_ => Right(tokenOk))
    assertEquals(Gigadat.payout(withdrawal("WIRE", Map.empty), creds(), base, s.call), PayoutAnswer.Rejected("unsupported_method"))
    assert(s.calls.isEmpty)

  test("TestGigadatPayoutRejectsNonCadCurrency"):
    val s = Script(_ => Right(tokenOk))
    assertEquals(Gigadat.payout(withdrawal("ETO", Map.empty, Currency.unsafe("EUR")), creds(), base, s.call),
      PayoutAnswer.Rejected("unsupported_currency"))
    assert(s.calls.isEmpty)

  test("TestGigadatPayoutRejectsMissingCredentials"):
    val s = Script(_ => Right(tokenOk))
    assertEquals(Gigadat.payout(withdrawal("ETO", Map.empty), Credential(Map("campaignId" -> "camp1")), base, s.call),
      PayoutAnswer.Rejected("missing_credentials"))
    assert(s.calls.isEmpty)

  // ── what the port adds ───────────────────────────────────────────────────────────────────────

  test("a 401 or 403 to the payout's token call is rejected unauthorised; one to a webflow step is in doubt"):
    Seq(401, 403).foreach { status =>
      val s = Script(_ => Right(Answered(status, Map.empty, "")))
      assertEquals(Gigadat.payout(withdrawal("ETO", Map.empty), creds(), base, s.call), PayoutAnswer.Rejected("unauthorised"))
    }
    val later = Script(out => Right(if out.url.contains("/webflow/") then Answered(401, Map.empty, "") else tokenOk))
    assert(Gigadat.payout(withdrawal("ETO", Map.empty), creds(), base, later.call).isInstanceOf[PayoutAnswer.InDoubt])

  test("a payout whose finalising GET fails is in doubt (handler/gigadat.go:273-277)"):
    val s = Script(out => Right(if out.url.contains("/webflow/deposit") then Answered(500, Map.empty, "") else tokenOk))
    Gigadat.payout(withdrawal("ETO", Map.empty), creds(), base, s.call) match
      case PayoutAnswer.InDoubt(_) => ()
      case other                   => fail(s"expected in doubt, got $other")
    val t = Script(_ => Left(CallFailure.TimedOut))
    assert(Gigadat.payout(withdrawal("ETO", Map.empty), creds(), base, t.call).isInstanceOf[PayoutAnswer.InDoubt])

  test("a token call that fails or answers other than 200 leaves the provider unavailable"):
    Seq[Either[CallFailure, Answered]](Left(CallFailure.Unreachable("refused")), Right(Answered(201, Map.empty, "{}")),
      Right(Answered(200, Map.empty, "<html/>"))).foreach { a =>
      assert(Gigadat.initiate(charge(), creds(), Customer(), base, _ => a).isInstanceOf[Initiation.Unavailable], a.toString)
    }

  test("a missing credential key is refused naming the key, never a value"):
    Gigadat.initiate(charge(), Credential(Map("campaignId" -> "camp1", "accessToken" -> "at1")), Customer(), base,
      _ => Right(tokenOk)) match
      case Initiation.Refused("missing_credentials", message) =>
        assert(message.contains("securityToken") && !message.contains("at1"), message)
      case other => fail(s"expected a refusal, got $other")

  test("the cross-check: another kind or another amount does not settle the session (FR-013)"):
    assert(Gigadat.webhook(raw("pay-1", "STATUS_SUCCESS", form("CPI", "100", "51.00")), charge(5000), creds(), now)
      .verdict.isInstanceOf[Verdict.Reject])
    assert(Gigadat.webhook(raw("pay-1", "STATUS_SUCCESS", form("ETO", "100", "50.00")), charge(5000), creds(), now)
      .verdict.isInstanceOf[Verdict.Reject])
    assertEquals(Gigadat.webhook(raw("pay-1", "STATUS_SUCCESS", "type=CPI&rcode=100"), charge(5000), creds(), now).verdict,
      Verdict.Settle(Outcome.Captured(), "STATUS_SUCCESS"), "no amount, nothing to compare")
    assertEquals(Gigadat.webhook(raw("pay-1", "STATUS_REJECTED", form("CPI", "231", "51.00")), charge(5000), creds(), now).reply,
      Reply.ok, "acknowledged even so: Gigadat retries every non-2xx")
