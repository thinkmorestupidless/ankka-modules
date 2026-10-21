package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.providers.morphosis.Morphosis
import io.eitheror.casino.pspgateway.sessions.{Destination, Outcome, Variance}

import java.security.SecureRandom
import java.time.Instant

/**
 * `morphosis/adapter_test.go` and `handler/webhook_morphosis_test.go` (24). Every webhook case asserts
 * the plain-text `OK` reply: QVEI retries anything else every 60 s, twenty times.
 */
class MorphosisSuite extends munit.FunSuite:

  // `webhook_morphosis_test.go:34-38`.
  val depositToken = "3f2b8c1e-6d4a-4e9b-8a7c-1d2e3f4a5b6c"
  val payoutToken  = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
  val otherToken   = "11111111-2222-4333-8444-555555555555"

  val credential = Credential(Map("user" -> "qvei-user", "pass" -> "qvei-pass"))
  val base       = "https://wpb.test/"
  val now        = Instant.parse("2026-10-18T12:00:00Z")
  val ok         = Reply(200, "OK", "text/plain; charset=utf-8")

  /** A session as `webhook_morphosis_test.go:92-101` stores one, initiated with QVEI's id and quote. */
  def charge(wpbId: Option[String] = Some("5473001"), expected: String = "0.00500") = Charge(
    paymentId = "pay-1", amount = 5000, currency = Currency.unsafe("USD"), method = "BTC", terminalId = Some("t-1"),
    returnUrl = "", webhookUrl = s"https://psp.example.com/v1/webhooks/morphosis/deposit/$depositToken",
    generated = Map("token" -> depositToken), providerReference = wpbId,
    providerData = wpbId.map(id => Map("wpbId" -> id, "expectedCoinAmount" -> expected)).getOrElse(Map.empty))

  def withdrawal(wpbId: Option[String] = Some("880001"), currency: String = "USD", amount: Long = 1999) = Withdrawal(
    requestId = "wr-100", brand = "brand-1", amount = amount, currency = Currency.unsafe(currency), method = "CRYPTO",
    terminalId = "t-1", webhookUrl = s"https://psp.example.com/v1/webhooks/morphosis/withdrawal/$payoutToken",
    destination = Map("coin" -> "USDT", "address" -> "0xA1B2"), generated = Map("token" -> payoutToken),
    providerReference = wpbId)

  /** A JSON callback on the route's token, which the endpoint copies into the query. */
  def json(token: String, body: String): Raw = Raw(body, Some("application/json"), query = Map("token" -> token))

  def deposit(body: String, token: String = depositToken, c: Charge = charge()): Handled =
    Morphosis.webhook(json(token, body), c, credential, now)

  final class Script(answer: Outbound => Either[CallFailure, Answered]):
    var calls: Vector[Outbound] = Vector.empty
    val call: Call = o =>
      calls :+= o
      answer(o)

  def answered(body: String) = Right(Answered(200, Map("content-type" -> "application/json"), body))
  def form(o: Outbound): Form = Form.decode(o.body.getOrElse(""))

  // ── adapter_test.go ─────────────────────────────────────────────────────────

  test("TestMapMethodName: QVEI's names, multi-network variants, lower case in, unknown passed through") {
    val cases = Vector("BTC" -> "bitcoin", "ETH" -> "ethereum", "LTC" -> "litecoin", "XRP" -> "ripple",
      "USDT" -> "tether", "USDC" -> "usdcoin", "USDTRX" -> "tether/trx", "USDTSOL" -> "tether/sol",
      "USDCSOL" -> "usdcoin/sol", "DOGE" -> "dogecoin", "BCH" -> "bitcoincash", "BNB" -> "binance",
      "AVAX" -> "avalanche", "SOL" -> "solana", "ADA" -> "cardano", "TRX" -> "tron", "XLM" -> "stellar",
      "DASH" -> "dash", "MATIC" -> "matic", "SHIB" -> "shibainu", "LINK" -> "chainlink", "APE" -> "apecoin",
      "ZRX" -> "0x", "PAX" -> "paxos", "QUANTUM" -> "quantum", "SCOR" -> "scor", "TRUMP" -> "trump",
      "usdt" -> "tether", "UNKNOWN" -> "unknown")
    cases.foreach((in, want) => assertEquals(Morphosis.method(in), want, in))
  }

  test("TestMapStatus and TestMapPayoutStatus: ac final, de final, pe and anything else open") {
    val c = charge()
    def cb(status: String) = Morphosis.Callback(Some("5473001"), status, None, None, Some("0.005"))
    assertEquals(Morphosis.outcome(cb("ac"), c), Verdict.Settle(Outcome.Captured(), "ac"))
    assert(Morphosis.outcome(cb("de"), c).isInstanceOf[Verdict.Settle])
    for s <- Vector("pe", "", "unknown") do assertEquals(Morphosis.outcome(cb(s), c), Verdict.Open(s))
  }

  test("TestInitiate_Success: insert_deposit, the token in notification_url, the quote kept for the webhook") {
    val script = Script(_ => answered("""{"result":"ok","status":"pe","id":5473123,"address":"1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf6a","coin_amount":0.00123}"""))
    val result = Morphosis.initiate(charge(None), credential, Customer(), base, script.call)
    assertEquals(result, Initiation.Quoted(Destination.Crypto("1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf6a", "0.00123", "BTC", None),
      Some("5473123"), Map("wpbId" -> "5473123", "expectedCoinAmount" -> "0.00123")))
    val Vector(o) = script.calls
    assertEquals(o.url, "https://wpb.test/?action=insert_deposit")
    assertEquals(o.contentType, Some("application/x-www-form-urlencoded"))
    val f = form(o)
    assertEquals(f.get("notification_url"), Some(s"https://psp.example.com/v1/webhooks/morphosis/deposit/$depositToken"))
    assertEquals(f.get("amount"), Some("50.00"))
    assertEquals(f.get("currency"), Some("USD"))
    assertEquals(f.get("method"), Some("bitcoin"))
    assertEquals(f.get("trace_id"), Some("pay-1"))
    assertEquals(f.get("user"), Some("qvei-user"))
    assertEquals(f.get("account"), Some("qvei-user"), "the user is the account when none is configured")
  }

  test("TestInitiate_APIError") {
    val script = Script(_ => answered("""{"result":"error","detail":"invalid method"}"""))
    assertEquals(Morphosis.initiate(charge(None), credential, Customer(), base, script.call),
      Initiation.Unavailable("morphosis: API error: invalid method"))
  }

  test("initiate is unavailable with no id, no address, an unreadable body or a failed call") {
    for answer <- Vector(answered("""{"result":"ok","id":0,"address":"a"}"""), answered("""{"result":"ok","id":5}"""),
        answered("<html>"), Left(CallFailure.TimedOut)) do
      assert(Morphosis.initiate(charge(None), credential, Customer(), base, Script(_ => answer).call).isInstanceOf[Initiation.Unavailable], answer)
  }

  test("TestInitiate_MissingCredentials: no call is made") {
    val script = Script(_ => answered("{}"))
    assertEquals(Morphosis.initiate(charge(None), Credential.empty, Customer(), base, script.call),
      Initiation.Unavailable("morphosis: missing required credentials (user, pass)"))
    assert(script.calls.isEmpty)
  }

  test("TestPayout_Success: insert_payout, the token's notification address, no currency and no request id") {
    val script = Script(_ => answered("""{"result":"ok","status":"pe","id":5473456}"""))
    assertEquals(Morphosis.payout(withdrawal(None, amount = 10000), credential, base, script.call),
      PayoutAnswer.Initiated(Some("5473456")))
    val f = form(script.calls.head)
    assertEquals(script.calls.head.url, "https://wpb.test/?action=insert_payout")
    assertEquals(f.get("notification_url"), Some(s"https://psp.example.com/v1/webhooks/morphosis/withdrawal/$payoutToken"))
    assertEquals(f.get("amount"), Some("100.00"))
    assertEquals(f.get("method"), Some("tether"))
    assertEquals(f.get("currency"), None, "insert_payout takes no currency field")
    assert(!script.calls.head.body.get.contains("wr-100"), "QVEI is not sent the request id")
  }

  test("TestPayout_RequiresNotifyToken: in doubt, as the original reports it, and nothing is called") {
    val script = Script(_ => answered("{}"))
    assert(Morphosis.payout(withdrawal().copy(generated = Map.empty), credential, base, script.call).isInstanceOf[PayoutAnswer.InDoubt])
    assert(script.calls.isEmpty)
  }

  test("TestPayout_Error: result=error is a rejection with QVEI's detail") {
    val script = Script(_ => answered("""{"result":"error","detail":"insufficient balance"}"""))
    assertEquals(Morphosis.payout(withdrawal(), credential, base, script.call), PayoutAnswer.Rejected("insufficient balance"))
  }

  test("a payout answered 401 or 403 is rejected unauthorised, whatever the body (the 2026-10-19 decision)") {
    assertEquals(Morphosis.payoutAnswer(Answered(401, Map.empty, "")), PayoutAnswer.Rejected("unauthorised"))
    assertEquals(Morphosis.payoutAnswer(Answered(403, Map.empty, """{"result":"error","detail":"forbidden"}""")),
      PayoutAnswer.Rejected("unauthorised"))
  }

  test("TestUnderpaid: the received amount against the quote, exactly") {
    def verdict(expected: String, received: Option[String]) =
      Morphosis.outcome(Morphosis.Callback(Some("5473001"), "ac", None, None, received), charge(expected = expected))
    val cases = Vector(("0.00500", "0.00500", false), ("0.00500", "0.005", false), ("0.00500", "0.00600", false),
      ("0.00500", "0.00499999", true), ("0.00500", "0.003", true), ("0.00500", "", true), ("", "0.005", true),
      ("0", "0.005", true))
    for (expected, received, short) <- cases do
      val got = verdict(expected, Option(received).filter(_.nonEmpty))
      assertEquals(got.isInstanceOf[Verdict.Settle] && got.asInstanceOf[Verdict.Settle].outcome.word == "failed", short,
        s"$expected / $received")
  }

  // ── Deposit webhook (webhook_morphosis_test.go) ─────────────────────────────

  test("TestMorphosisWebhook_CompletedTriggersCallback") {
    val h = deposit("""{"id":5473001,"status":"ac","tx_hash":"0xabc","coin_amount":0.00500}""")
    assertEquals(h, Handled(Verdict.Settle(Outcome.Captured(), "ac"), ok))
  }

  test("TestMorphosisWebhook_OverpaymentCaptures") {
    val h = deposit("""{"id":5473002,"status":"ac","coin_amount":0.006}""", c = charge(Some("5473002")))
    assertEquals(h, Handled(Verdict.Settle(Outcome.Captured(), "ac"), ok))
  }

  test("TestMorphosisWebhook_UnderpaymentFails: failed underpaid, with the variance, never captured") {
    val h = deposit("""{"id":5473010,"status":"ac","coin_amount":0.003}""", c = charge(Some("5473010")))
    assertEquals(h, Handled(Verdict.Settle(Outcome.Failed(Some("underpaid"), "Underpaid: received 0.003 of 0.00500 expected",
      Some(Variance("UNDERPAYMENT", Some("0.00500"), Some("0.003")))), "ac"), ok))
  }

  test("TestMorphosisWebhook_MissingCoinAmountFails") {
    val h = deposit("""{"id":5473011,"status":"ac"}""", c = charge(Some("5473011")))
    assert(h.verdict match
      case Verdict.Settle(Outcome.Failed(Some("underpaid"), _, _), _) => true
      case _                                                         => false, h.verdict)
    assertEquals(h.reply, ok)
  }

  test("TestMorphosisWebhook_DeniedTriggersFailure: de fails with QVEI's message") {
    val h = deposit("""{"id":5473003,"status":"de","message":"expired"}""", c = charge(Some("5473003")))
    assertEquals(h, Handled(Verdict.Settle(Outcome.Failed(Some("de"), "expired"), "de"), ok))
    assertEquals(deposit("""{"id":5473003,"status":"de"}""", c = charge(Some("5473003"))).verdict,
      Verdict.Settle(Outcome.Failed(Some("de"), "Payment declined"), "de"))
  }

  test("TestMorphosisWebhook_PendingNoAction") {
    assertEquals(deposit("""{"id":5473004,"status":"pe"}""", c = charge(Some("5473004"))), Handled(Verdict.Open("pe"), ok))
  }

  test("TestMorphosisWebhook_IdMismatchIgnored: the right token with another payment's id moves nothing") {
    val h = deposit("""{"id":5473006,"status":"ac","coin_amount":0.005}""", c = charge(Some("5473005")))
    assertEquals(h, Handled(Verdict.Reject("id does not match the session's QVEI id"), ok))
  }

  test("TestMorphosisWebhook_UnknownTokenIgnored: the right id on a token never issued for the session") {
    val raw = json(otherToken, """{"id":5473007,"status":"ac","coin_amount":0.005}""")
    assertEquals(Morphosis.locate(raw), Right(Located.Indexed(otherToken)))
    assertEquals(Morphosis.unknown(raw), ok)
    // Were the index ever to return this session for another token, the session's own token still refuses it.
    assertEquals(Morphosis.webhook(raw, charge(Some("5473007")), credential, now),
      Handled(Verdict.Reject("notify token is not the session's"), ok))
  }

  test("TestMorphosisWebhook_MalformedTokenIgnored: answered OK before any lookup") {
    for token <- Vector("5473008", "", "3f2b8c1e6d4a4e9b8a7c1d2e3f4a5b6c0000", depositToken.toUpperCase + "x") do
      assertEquals(Morphosis.locate(json(token, "{}")), Left(ok), token)
    assertEquals(Morphosis.locate(Raw("{}", Some("application/json"))), Left(ok))
  }

  test("TestMorphosisWebhook_SessionRecoveredFromSubstrate: the session's recorded id and quote verify the callback") {
    // The port has no in-memory store to miss: the session is always read from its entity.
    val h = deposit("""{"id":5473009,"status":"ac","coin_amount":0.005}""", c = charge(Some("5473009"), "0.005"))
    assertEquals(h.verdict, Verdict.Settle(Outcome.Captured(), "ac"))
  }

  test("TestMorphosisWebhook_CallbackFailureKeepsSession: the verdict does not depend on delivery") {
    // Delivery and redelivery are the deliverer's (FR-015); the webhook's verdict is the same either way.
    val c = charge(Some("5473012"))
    val body = """{"id":5473012,"status":"ac","coin_amount":0.005}"""
    assertEquals(deposit(body, c = c), deposit(body, c = c))
  }

  test("TestMorphosisWebhook_MalformedBodyReturnsOK") {
    assertEquals(deposit("{not json"), Handled(Verdict.Reject("unreadable body"), ok))
  }

  test("TestMorphosisWebhook_FormEncodedBody") {
    val raw = Raw(Form.of("id" -> "5473013", "status" -> "ac", "coin_amount" -> "0.005").encode,
      Some("application/x-www-form-urlencoded"), query = Map("token" -> depositToken))
    assertEquals(Morphosis.webhook(raw, charge(Some("5473013")), credential, now), Handled(Verdict.Settle(Outcome.Captured(), "ac"), ok))
  }

  test("a claimed deposit with no QVEI id yet is acknowledged and moves nothing (data-model.md)") {
    assertEquals(deposit("""{"id":5473001,"status":"ac","coin_amount":0.005}""", c = charge(None)),
      Handled(Verdict.Ignore("session has no QVEI id yet"), ok))
  }

  test("generate makes a fresh 36-character v4 token") {
    val r = SecureRandom()
    val t = Morphosis.generate(r)("token")
    assertEquals(t.length, 36)
    assert(Morphosis.validToken(t))
    assertEquals(t.charAt(14), '4')
    assertNotEquals(Morphosis.generate(r)("token"), t)
  }

  // ── Withdrawal webhook ──────────────────────────────────────────────────────

  def payout(body: String, token: String = payoutToken, w: Withdrawal = withdrawal()): Handled =
    val raw = json(token, body)
    Morphosis.payoutWebhook(raw, w, credential, now)

  test("TestMorphosisWithdrawalWebhook_AcceptedCompletes") {
    assertEquals(Morphosis.locatePayout(json(payoutToken, "{}")), Right(Located.Indexed(payoutToken)))
    assertEquals(payout("""{"id":880001,"status":"ac","tx_hash":"0xfeed"}"""), Handled(Verdict.Settle(Outcome.Completed, "ac"), ok))
  }

  test("TestMorphosisWithdrawalWebhook_DeniedFails") {
    assertEquals(payout("""{"id":880002,"status":"de"}""", w = withdrawal(Some("880002"))),
      Handled(Verdict.Settle(Outcome.PayoutFailed(Some("de"), "Payment declined"), "de"), ok))
  }

  test("TestMorphosisWithdrawalWebhook_PendingNoAction") {
    assertEquals(payout("""{"id":880003,"status":"pe"}""", w = withdrawal(Some("880003"))), Handled(Verdict.Open("pe"), ok))
  }

  test("TestMorphosisWithdrawalWebhook_ForgeriesIgnored: a guessed token, a mismatched id, a malformed token") {
    val w = withdrawal(Some("880004"))
    assert(payout("""{"id":880004,"status":"de"}""", token = otherToken, w = w).verdict.isInstanceOf[Verdict.Reject])
    assert(payout("""{"id":880005,"status":"de"}""", w = w).verdict.isInstanceOf[Verdict.Reject])
    assertEquals(Morphosis.locatePayout(json("880004", "{}")), Left(ok))
    assertEquals(Morphosis.unknownPayout(json(otherToken, "{}")), ok)
  }

  test("TestMorphosisWithdrawalWebhook_ResolvesInDoubtPayout: no QVEI id known, the token alone admits it") {
    assertEquals(payout("""{"id":880006,"status":"ac","tx_hash":"0x6"}""", w = withdrawal(None)),
      Handled(Verdict.Settle(Outcome.Completed, "ac"), ok))
  }

  test("TestMorphosisWithdrawalWebhook_SessionRecoveredFromSubstrate") {
    assertEquals(payout("""{"id":880007,"status":"ac","tx_hash":"0x7"}""", w = withdrawal(Some("880007"))).verdict,
      Verdict.Settle(Outcome.Completed, "ac"))
  }

  // ── Payout initiation (webhook_morphosis_test.go) ───────────────────────────

  test("TestMorphosisPayout_Initiated: 1,999 minor units are 19.99 exactly") {
    val script = Script(_ => answered("""{"result":"ok","status":"pe","id":880100,"coin_amount":19.98}"""))
    assertEquals(Morphosis.payout(withdrawal(None), credential, base, script.call), PayoutAnswer.Initiated(Some("880100")))
    val f = form(script.calls.head)
    assertEquals(f.get("amount"), Some("19.99"))
    assertEquals(f.get("method"), Some("tether"))
    assertEquals(f.get("address"), Some("0xA1B2"))
    assertEquals(f.get("details"), None)
  }

  test("a memo or tag is sent as details") {
    val script = Script(_ => answered("""{"result":"ok","id":1}"""))
    val w = withdrawal(None).copy(destination = Map("coin" -> "XRP", "address" -> "r9", "memo" -> "12345"))
    Morphosis.payout(w, credential, base, script.call)
    assertEquals(form(script.calls.head).get("details"), Some("12345"))
    assertEquals(form(script.calls.head).get("method"), Some("ripple"))
  }

  test("TestMorphosisPayout_NonUsdRejectedWithoutCallingQvei") {
    val script = Script(_ => answered("""{"result":"ok","status":"pe","id":1}"""))
    assertEquals(Morphosis.payout(withdrawal(None, "ZAR"), credential, base, script.call), PayoutAnswer.Rejected("unsupported_currency"))
    assert(script.calls.isEmpty)
  }

  test("TestMorphosisPayout_UpstreamRejection") {
    val script = Script(_ => answered("""{"result":"error","detail":"insufficient balance"}"""))
    assertEquals(Morphosis.payout(withdrawal(None), credential, base, script.call), PayoutAnswer.Rejected("insufficient balance"))
    assertEquals(script.calls.size, 1)
  }

  test("TestMorphosisPayout_TransportFailureIsInDoubtAndKeepsSession") {
    for failure <- Vector(CallFailure.TimedOut, CallFailure.Unreachable("connection reset")) do
      assert(Morphosis.payout(withdrawal(None), credential, base, Script(_ => Left(failure)).call).isInstanceOf[PayoutAnswer.InDoubt])
    assert(Morphosis.payout(withdrawal(None), credential, base, Script(_ => answered("""{"result":"ok"}""")).call)
      .isInstanceOf[PayoutAnswer.InDoubt], "no id in the answer")
    assert(Morphosis.payout(withdrawal(None), credential, base, Script(_ => answered("<html>")).call)
      .isInstanceOf[PayoutAnswer.InDoubt], "unreadable")
  }

  test("TestMorphosisPayout_MissingFieldsRejected: no credential is missing_credentials, and QVEI is not called") {
    val script = Script(_ => answered("""{"result":"ok","id":1}"""))
    assertEquals(Morphosis.payout(withdrawal(None), Credential(Map("user" -> "u")), base, script.call),
      PayoutAnswer.Rejected("missing_credentials"))
    assert(script.calls.isEmpty)
  }

  test("no credential value appears in a verdict, a reply or an answer") {
    val texts = Vector(
      deposit("{not json").toString,
      Morphosis.initiate(charge(None), credential, Customer(), base, Script(_ => answered("""{"result":"error"}""")).call).toString,
      Morphosis.payout(withdrawal(None), credential, base, Script(_ => answered("<html>")).call).toString)
    for t <- texts; s <- credential.secretValues do assert(!t.contains(s), t)
  }
