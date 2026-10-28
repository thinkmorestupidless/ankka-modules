package io.eitheror.casino.players.clients

import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.testkit.{LogCapturing, ScriptedServices}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.it.{Answer, FakeServer, Received}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}

/** T021: each client's answers mapped; a silent neighbour is `Unanswered` at its bound. */
class ClientsSuite extends munit.FunSuite with LogCapturing:

  private val bound = Duration.ofMillis(300)
  private val quick = Duration.ofMillis(50)

  private def json(status: Int, body: String) = ServiceResponse(status, "application/json", body.getBytes(UTF_8), Vector.empty)

  private def answered[A](r: Raced[A]): A = r match
    case Raced.Answered(a) => a
    case other             => fail(s"not answered: $other")

  private def scripted(name: String)(answer: ScriptedServices.Request => ServiceResponse) = ScriptedServices().answer(name)(answer)

  test("may-log-in: allowed, excluded, cooling off with its end; a non-2xx or an unknown decision is failed") {
    val until = Instant.parse("2026-11-01T00:00:00Z")
    def rg(body: String, status: Int = 200) = Rg.http(scripted(Rg.Service)(_ => json(status, body)), "goldvibe")
    assertEquals(answered(rg("""{"decision":"allowed"}""").mayLogIn("p-1", bound)), LogIn.Allowed)
    assertEquals(answered(rg("""{"decision":"excluded"}""").mayLogIn("p-1", bound)), LogIn.Excluded)
    assertEquals(answered(rg(s"""{"decision":"cooling_off","endsAt":"$until"}""").mayLogIn("p-1", bound)), LogIn.CoolingOff(until))
    assertEquals(answered(rg(s"""{"decision":"cooling_off","endsAt":${until.toEpochMilli}}""").mayLogIn("p-1", bound)), LogIn.CoolingOff(until))
    assert(rg("""{"decision":"maybe"}""").mayLogIn("p-1", bound).isInstanceOf[Raced.Failed[?]])
    assert(rg("{}", 500).mayLogIn("p-1", bound).isInstanceOf[Raced.Failed[?]])
  }

  test("may-log-in is asked at the brand's rg prefix with the player; unreachable is failed") {
    val services = scripted(Rg.Service)(_ => json(200, """{"decision":"allowed"}"""))
    Rg.http(services, "goldvibe").mayLogIn("p-7", bound): Unit
    val req = services.requests.head
    assertEquals((req.method, req.path), ("POST", "/v1/casino/brands/goldvibe/rg/may-log-in"))
    assertEquals(Json.parse(String(req.body, UTF_8)).flatMap(_.text("playerId")), Some("p-7"))
    assert(Rg.http(ScriptedServices().unanswered(Rg.Service), "goldvibe").mayLogIn("p", bound).isInstanceOf[Raced.Failed[?]])
  }

  test("a silent neighbour is Unanswered at the bound") {
    val rg = Rg.http(scripted(Rg.Service) { _ => Thread.sleep(1000); json(200, """{"decision":"allowed"}""") }, "goldvibe")
    val started = System.nanoTime
    assert(rg.mayLogIn("p-1", quick).isInstanceOf[Raced.Unanswered[?]])
    assert((System.nanoTime - started) / 1_000_000 < 500)
  }

  test("may-deposit: allowed, or the refusal with its body; the sums are sent") {
    val services = scripted(Rg.Service)(_ => json(200, """{"decision":"over_limit","period":"daily","limit":1000,"used":900,"remaining":100}"""))
    answered(Rg.http(services, "goldvibe").mayDeposit("p-1", "EUR", 500, DepositSums(900, 900, 900), bound)) match
      case DepositDecision.Refused(code, body) => assertEquals((code, body.long("remaining")), ("over_limit", Some(100L)))
      case other                               => fail(other.toString)
    val sent = Json.parse(String(services.requests.head.body, UTF_8)).get
    assertEquals((sent.long("amount"), sent.long("last7d"), sent.text("currency")), (Some(500L), Some(900L), Some("EUR")))
    val ok = Rg.http(scripted(Rg.Service)(_ => json(200, """{"decision":"allowed"}""")), "goldvibe")
    assertEquals(answered(ok.mayDeposit("p-1", "EUR", 1, DepositSums(0, 0, 0), bound)), DepositDecision.Allowed)
  }

  test("gamstop refusal passed on; kyc's deposit check allowed or refused with code and message") {
    val rg = scripted(Rg.Service)(_ => json(200, "{}"))
    assertEquals(answered(Rg.http(rg, "goldvibe").gamstopRefusal("p-1", "excluded", bound)), ())
    assertEquals(rg.requests.head.path, "/v1/casino/brands/goldvibe/rg/gamstop-refusal")
    val sent = Json.parse(String(rg.requests.head.body, UTF_8)).get
    assertEquals((sent.text("playerId"), sent.text("result"), sent.text("email")), (Some("p-1"), Some("excluded"), None))
    def kyc(body: String) = Kyc.http(scripted(Kyc.Service)(_ => json(200, body)), "goldvibe")
    assertEquals(answered(kyc("""{"allowed":true}""").depositCheck("p-1", 100, bound)), KycDecision.Allowed)
    assertEquals(answered(kyc("""{"allowed":false,"code":"DEPOSIT_BLOCKED_KYC","message":"Verify first"}""").depositCheck("p-1", 100, bound)),
      KycDecision.Refused("DEPOSIT_BLOCKED_KYC", "Verify first"))
    assert(kyc("""{"allowed":false}""").depositCheck("p-1", 100, bound).isInstanceOf[Raced.Failed[?]])
  }

  test("engagement's send: accepted, not configured, suppressed; the key, trigger and link are sent") {
    val services = scripted(Engagement.Service)(_ => json(200, """{"status":"accepted"}"""))
    assertEquals(answered(Engagement.http(services, "goldvibe").send("k-1", "p-1", "EmailAddressVerification", "a@example.test",
      "https://site/verify?t=x", bound)), SendOutcome.Accepted)
    val req  = services.requests.head
    val sent = Json.parse(String(req.body, UTF_8)).get
    assertEquals(req.path, "/v1/casino/brands/goldvibe/engagement/sends")
    assertEquals((sent.text("key"), sent.text("trigger"), sent.text("link")), (Some("k-1"), Some("EmailAddressVerification"), Some("https://site/verify?t=x")))
    def outcome(body: String) =
      answered(Engagement.http(scripted(Engagement.Service)(_ => json(200, body)), "goldvibe").send("k", "p", "t", "a", "l", bound))
    assertEquals(outcome("""{"status":"not_configured"}"""), SendOutcome.NotConfigured)
    assertEquals(outcome("""{"status":"suppressed","reason":"bounced"}"""), SendOutcome.Suppressed("bounced"))
  }

  test("the wallet's deposit sums; erasure's certificate and export's part from a target, in another project too") {
    val wallet = scripted(Wallet.Service)(_ => json(200, """{"last24h":1,"last7d":2,"last30d":3}"""))
    assertEquals(answered(Wallet.http(wallet, "goldvibe").depositSums("p 1", "EUR", bound)), DepositSums(1, 2, 3))
    assertEquals(wallet.requests.head.path, "/v1/casino/brands/goldvibe/wallets/p%201/EUR/deposit-sums")
    val services = ScriptedServices()
      .answer("merchant", "payments")(r => json(200, s"""{"subject":"${r.path}","confirmed":true}"""))
      .answer("wallet")(_ => json(200, """{"service":"wallet","records":[]}"""))
    val cert = answered(Erasure.http(services).apply(Target("merchant", "/v1/payments", Some("payments")), "p-1", bound))
    assertEquals(cert.text("subject"), Some("/v1/payments/erasure/players/p-1"))
    assertEquals(answered(Export.http(services).part(Target("wallet", "/v1/casino/brands/goldvibe"), "p-1", bound)).text("service"), Some("wallet"))
    assertEquals(services.requests.last.method, "GET")
    val refused = ScriptedServices().answer("wallet")(_ => json(404, "{}"))
    assert(Export.http(refused).part(Target("wallet", ""), "p", bound).isInstanceOf[Raced.Failed[?]])
  }

  private final class Scripted(answer: Received => Answer) extends FakeServer():
    protected def route(r: Received): Answer = answer(r)

  test("ingest: the cashier opened, refused with ingest's code, or TRY_AGAIN as failed; the key in the header") {
    val ingest = Scripted(_ => Answer.ok(Json.obj("redirectUrl" -> "https://pay/x", "transactionId" -> "pay-1", "session_token" -> "s")))
    try
      val out = Ingest.http(ingest.address).createSession(Json.obj("amountCents" -> 100L), "mk-1", bound)
      assertEquals(answered(out), CashierOutcome.Opened("https://pay/x", "pay-1"))
      assertEquals(ingest.received.head.headers.get("merchantkey"), Some("mk-1"))
    finally ingest.stop()
    val refusing = Scripted(_ => Answer.json(400, Json.obj("error" -> "INVALID_REQUEST", "field" -> "country")))
    try assertEquals(answered(Ingest.http(refusing.address).createSession(Json.obj(), "k", bound)), CashierOutcome.Refused(400, "INVALID_REQUEST"))
    finally refusing.stop()
    val busy = Scripted(_ => Answer.json(503, Json.obj("error" -> "TRY_AGAIN")))
    try assert(Ingest.http(busy.address).createSession(Json.obj(), "k", bound).isInstanceOf[Raced.Failed[?]])
    finally busy.stop()
  }

  test("geo: built-in and private addresses asked of nobody; ip-api's answer cached; a failure is null, never an error") {
    val api = Scripted(r =>
      if r.path == "/json/8.8.8.8" then Answer.ok(Json.obj("status" -> "success", "countryCode" -> "DE"))
      else Answer.ok(Json.obj("status" -> "fail")))
    try
      val geo = Geo.http(api.address)
      assertEquals(geo.lookup(Some("81.2.69.144")), GeoAnswer(Some("GB"), Some("+44")))
      assertEquals(geo.lookup(Some("192.168.1.4")), GeoAnswer(None, None))
      assertEquals(geo.lookup(None), GeoAnswer(None, None))
      assert(api.received.isEmpty)
      assertEquals(geo.lookup(Some("8.8.8.8")), GeoAnswer(Some("DE"), Some("+49")))
      assertEquals(geo.lookup(Some("8.8.8.8")), GeoAnswer(Some("DE"), Some("+49")))
      assertEquals(api.received.size, 1)
      assertEquals(geo.lookup(Some("1.1.1.1")), GeoAnswer(None, None))
    finally api.stop()
    assertEquals(Geo.http("http://127.0.0.1:1").lookup(Some("9.9.9.9")), GeoAnswer(None, None))
    assertEquals(Geo.callerAddress(Map("x-forwarded-for" -> "5.5.5.5, 10.0.0.1", "x-real-ip" -> "6.6.6.6").get), Some("5.5.5.5"))
    assertEquals(Geo.dialCodes.size, 128)
  }
