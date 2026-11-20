package io.eitheror.casino.pspgateway.deposits

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.{Json, Settings}
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.merchant.{MerchantClient, Terminal, Unread}
import io.eitheror.casino.pspgateway.providers.{Answered, CallFailure, Credential, Outbound}
import io.eitheror.casino.pspgateway.sessions.Provider

import scala.collection.mutable

/**
 * The test payment (spec 030 T2): each provider's test mode, the smallest charge, the provider's
 * answer read as an outcome, and the refusals before any call. The class is given no component
 * client, so it can claim no session; these say what reaches the provider and `merchant`.
 */
class TestPaymentSuite extends munit.FunSuite with LogCapturing:

  private final class Credentials(named: Map[(String, Provider, String), Map[String, String]], down: Boolean = false) extends MerchantClient:
    val reads = mutable.Buffer.empty[(String, Provider, String)]
    def credential(subBrandId: String, provider: Provider) = fail("the test payment reads the named credential, never the usable one")
    def namedCredential(subBrandId: String, provider: Provider, name: String): Either[Unread, Credential] =
      reads += ((subBrandId, provider, name))
      if down then Left(Unread.Unavailable("merchant answered 503"))
      else named.get((subBrandId, provider, name)).map(Credential(_)).toRight(Unread.None)
    def terminalProperties(terminalId: String) = fail("no terminal is read")
    def brandTerminals(brand: String): Either[Unread, Vector[Terminal]] = fail("no terminal is read")

  private val urls = Map("UNITYFINANCE_PAY_URL" -> "https://unity.test/pay", "GIGADAT_BASE_URL" -> "https://gigadat.test",
    "DIGITARIUM_SANDBOX_URL" -> "https://g2pay-sandbox.test", "DIGITARIUM_PRODUCTION_URL" -> "https://g2pay.test",
    "DREAMZPAY_BASE_URL" -> "https://dreamzpay.test")
  private val settings = Settings("https://gateway.example", urls)

  private val unity = Map("signKey" -> "unity-sign", "checkoutId.CARD_TEST" -> "co-test")
  private val gigadat = Map("campaignId" -> "camp-1", "accessToken" -> "gd-a", "securityToken" -> "gd-s",
    "siteUrl" -> "https://casino.example", "environment" -> "production")
  private val digitarium = Map("apiKey" -> "dg-key", "environment" -> "production")

  private def merchant(down: Boolean = false) = Credentials(Map(
    ("sb-1", Provider.UnityFinance, "main") -> unity,
    ("sb-1", Provider.Gigadat, "main") -> gigadat,
    ("sb-1", Provider.Digitarium, "main") -> digitarium,
    ("sb-1", Provider.DreamzPay, "main") -> Map("memberId" -> "m", "totype" -> "t", "secureKey" -> "k")), down)

  /** Answers every call with `answer`, recording what was sent. */
  private final class Provider_(answer: Outbound => Either[CallFailure, Answered]):
    val sent = mutable.Buffer.empty[Outbound]
    def call(p: Provider): Outbound => Either[CallFailure, Answered] = o => { sent += o; answer(o) }

  private def body(provider: Provider, fields: (String, Any)*): String =
    Json.render(Json.obj((Vector[(String, Any)]("subBrandId" -> "sb-1", "psp" -> provider.wire, "credentialName" -> "main") ++ fields)*))

  test("Unity Finance is 501 test_mode_unavailable: no request can keep a CARD_TEST charge off a live checkout (review Q5)") {
    // The credential is a production one: Unity Finance has one address for test and live, and test mode
    // is a setting of the checkout in its portal. Nothing is read from merchant and nothing is sent.
    val psp = Provider_(_ => fail("Unity Finance is never called for a test payment"))
    val m   = merchant()
    val a   = TestPayment(m, settings, psp.call)(body(Provider.UnityFinance))
    assertEquals((a.status, a.body.textOr("error", ""), a.body.textOr("provider", "")), (501, "test_mode_unavailable", "UNITYFINANCE"))
    assert(a.body.textOr("message", "").contains("live money"), a.body.toString)
    assertEquals(TestMode.of(Provider.UnityFinance), None)
    assert(m.reads.isEmpty && psp.sent.isEmpty)
  }

  test("the currency asked is used, at one whole unit of it") {
    val page = Json.render(Json.obj("result" -> Json.obj("id" -> "g2-1", "redirectUrl" -> "https://g2pay-sandbox.test/p/1")))
    val psp  = Provider_(_ => Right(Answered(200, Map.empty, page)))
    val a    = TestPayment(merchant(), settings, psp.call)(body(Provider.Digitarium, "currency" -> "eur"))
    assertEquals((a.body.long("amount"), a.body.textOr("currency", "")), (Some(100L), "EUR"))
    assertEquals(TestMode.amount(Provider.Digitarium, io.eitheror.casino.contract.money.Currency.unsafe("JPY")), 1L)
    assertEquals(TestMode.amount(Provider.Digitarium, io.eitheror.casino.contract.money.Currency.unsafe("KWD")), 1000L)
  }

  test("Gigadat: the sandbox flag is sent whatever the credential's environment says, in CAD") {
    val token = Json.render(Json.obj("token" -> "tk", "data" -> Json.obj("transactionId" -> "gd-txn")))
    val psp   = Provider_(_ => Right(Answered(200, Map.empty, token)))
    val a     = TestPayment(merchant(), settings, psp.call)(body(Provider.Gigadat))
    assertEquals((a.status, a.body.textOr("outcome", ""), a.body.textOr("testMode", "")), (200, "accepted", "sandbox"))
    assertEquals(a.body.textOr("providerReference", ""), "gd-txn")
    val sent = Json.parse(psp.sent.head.body.get).get
    assertEquals((sent.bool("sandbox"), sent.textOr("currency", ""), BigDecimal(sent.get("amount").asText)), (Some(true), "CAD", BigDecimal(1)))
    assertEquals(sent.textOr("transactionId", ""), a.body.textOr("reference", "?"))
  }

  test("Digitarium: the sandbox host, never the production one the credential names") {
    val page = Json.render(Json.obj("result" -> Json.obj("id" -> "g2-1", "redirectUrl" -> "https://g2pay-sandbox.test/p/1")))
    val psp  = Provider_(_ => Right(Answered(200, Map.empty, page)))
    val a    = TestPayment(merchant(), settings, psp.call)(body(Provider.Digitarium))
    assertEquals(a.body.textOr("outcome", ""), "accepted")
    assert(psp.sent.head.url.startsWith("https://g2pay-sandbox.test/"), psp.sent.head.url)
  }

  test("a provider's refusal and a provider that fails are answered as the outcome, with its words") {
    val digit401 = Provider_(_ => Right(Answered(401, Map.empty, "")))
    val k        = TestPayment(merchant(), settings, digit401.call)(body(Provider.Digitarium))
    assertEquals((k.body.textOr("outcome", ""), k.body.textOr("reason", "")), ("unavailable", "digitarium: invalid API key (HTTP 401)"))
    val gone = Provider_(_ => Left(CallFailure.TimedOut))
    assertEquals(TestPayment(merchant(), settings, gone.call)(body(Provider.Gigadat)).body.textOr("outcome", ""), "unavailable")
    val gigaRefused = TestPayment(merchant(), settings, gone.call)(body(Provider.Gigadat, "currency" -> "EUR"))
    assertEquals((gigaRefused.status, gigaRefused.body.textOr("error", "")), (400, "unsupported_currency"))
  }

  test("a provider with no test mode is 501 test_mode_unavailable, and neither merchant nor the provider is asked") {
    val psp = Provider_(_ => fail("no provider is called"))
    val m   = merchant()
    Seq(Provider.UnityFinance, Provider.DreamzPay, Provider.T365, Provider.Cozeni, Provider.EuPaymentz, Provider.BounceMoney, Provider.Morphosis).foreach { p =>
      val a = TestPayment(m, settings, psp.call)(body(p))
      assertEquals((a.status, a.body.textOr("error", ""), a.body.textOr("provider", "")), (501, "test_mode_unavailable", p.wire))
    }
    assert(m.reads.isEmpty)
  }

  test("the refusals before any call: unknown credential, one lacking keys, merchant down, no base URL, a bad body") {
    val psp = Provider_(_ => fail("no provider is called"))
    def run(m: MerchantClient, b: String, s: Settings = settings) = TestPayment(m, s, psp.call)(b)
    val unknown = run(merchant(), body(Provider.Digitarium, "credentialName" -> "other"))
    assertEquals((unknown.status, unknown.body.textOr("error", "")), (404, "no_credentials"))
    val lacking = run(Credentials(Map(("sb-1", Provider.Digitarium, "main") -> Map("signingKey" -> "s"))), body(Provider.Digitarium))
    assertEquals((lacking.status, lacking.body.textOr("error", "")), (404, "no_credentials"))
    assert(lacking.body.textOr("message", "").contains("apiKey"), lacking.body.toString)
    assert(!lacking.body.toString.contains("\"s\""), "no value is named")
    assertEquals(run(merchant(down = true), body(Provider.Digitarium)).status, 502)
    val unset = run(merchant(), body(Provider.Digitarium), Settings("https://gateway.example", urls - "DIGITARIUM_SANDBOX_URL"))
    assertEquals((unset.status, unset.body.textOr("error", "")), (502, "provider_not_configured"))
    Seq("{}", "not json", body(Provider.Digitarium, "currency" -> "not a currency"), Json.render(Json.obj("psp" -> "DIGITARIUM", "subBrandId" -> "sb-1")))
      .foreach(b => assertEquals(run(merchant(), b).status, 400, b))
  }

  test("no secret is in the answer") {
    val page = Json.render(Json.obj("result" -> Json.obj("id" -> "g2-1", "redirectUrl" -> "https://g2pay-sandbox.test/p/1")))
    val psp  = Provider_(_ => Right(Answered(200, Map.empty, page)))
    val a    = Json.render(TestPayment(merchant(), settings, psp.call)(body(Provider.Digitarium)).body)
    Seq("dg-key").foreach(s => assert(!a.contains(s), a))
  }
