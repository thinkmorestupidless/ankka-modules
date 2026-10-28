package io.eitheror.casino.pspgateway.providers

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.providers.eupaymentz.EuPaymentz
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.time.Instant

/** `eupaymentz/signature_test.go`, `adapter_test.go` and `handler/webhook_eupaymentz_test.go`, with `adapter.go`'s rules. */
class EuPaymentzSuite extends munit.FunSuite with LogCapturing:

  private val passphrase = "sk-test"
  private val credential = Credential(Map("accountId" -> "sec1_spinvibecasino_ftd", "password" -> "pw-secret",
    "passphrase" -> passphrase))
  private val paymentId  = "7f4878aa-29d7-4b10-a572-b326e23ac03b"
  private val now        = Instant.parse("2026-10-18T10:00:00Z")

  private def charge(amount: Long = 10000, reference: Option[String] = Some("T1987026090110470858")) = Charge(
    paymentId = paymentId, amount = amount, currency = Currency.unsafe("EUR"), method = "CARD", terminalId = None,
    returnUrl = s"https://psp.example.test/v1/psp/checkout/eupaymentz/$paymentId/return",
    webhookUrl = "https://psp.example.test/v1/webhooks/eupaymentz", providerReference = reference
  )

  private val customer = Customer(email = Some("player@example.com"), ip = Some("192.168.1.1"), firstName = Some("Ada"),
    lastName = Some("Lovelace"), country = Some("DE"))

  private def scripted(status: Int, body: String): (Call, () => Vector[Outbound]) =
    var seen = Vector.empty[Outbound]
    val call: Call = out =>
      seen :+= out
      Right(Answered(status, Map("content-type" -> "application/json"), body))
    (call, () => seen)

  // ── signature_test.go ───────────────────────────────────────────────────────

  test("TestOutboundSHA"):
    val got = EuPaymentz.outboundSha("54EEe40e5E4311d3287C559ab523E6afE", "50.00", "sec1_spinvibecasino_ftd",
      "player@example.com", "192.168.1.1")
    assertEquals(got, "be810561f18bc8fc975e2482969e1ef13ba793d028cc56e6ddce204d3f19eb9f")
    assertEquals(got.length, 64)

  test("TestVerifyWebhook"):
    val sha = Digests.sha256Hex("54EEe40e5E4311d3287C559ab523E6afE" + "T000001" + "50.00" + "00000")
    assert(EuPaymentz.verifySha("54EEe40e5E4311d3287C559ab523E6afE", "T000001", "50.00", "00000", sha))
    assert(!EuPaymentz.verifySha("54EEe40e5E4311d3287C559ab523E6afE", "T000001", "50.00", "00000", "badhash"))

  test("TestVerifyWebhookCaseInsensitive"):
    val sha = Digests.sha256Hex("passphrase" + "T000042" + "10.00" + "00000")
    assert(EuPaymentz.verifySha("passphrase", "T000042", "10.00", "00000", sha.toUpperCase))

  // ── adapter_test.go and adapter.go:116-248 ──────────────────────────────────

  private val pendGet = """{"success":true,"data":{"UrlToRedirecPostedParameters":[],"resp_trans_id":"T8335526082011180259","resp_trans_merchant_id":"e1446b19-1c3d-4c95-ae43-5733bceea7de","resp_trans_amount":"10.00","resp_action_type":"payment","resp_trans_status":"PEND","resp_trans_detailled_status":null,"resp_trans_description_status":null,"resp_merchant_data1":"","resp_merchant_data2":"","resp_merchant_data3":"","resp_merchant_data4":"","resp_sha":"711868cc6939bfb95db23d671937d8a64aa0e69b405f441b82f077fdddb3c26a","resp_descriptor":"-","ErrorsDescriptionCodes":null,"isDirectResult":false,"UrlToRedirect":"https://ts.secure1gateway.com/api/v2/ProcessTxGetHosted?transkey=43ba057c581bcc0939547b5262241229","UrlToRedirectMethod":"GET","ExtraData":null}}"""

  test("TestProcessTxEnvelope_Decode: the answer is read under data; a GET redirect goes straight to EuPaymentz"):
    assertEquals(EuPaymentz.redirectFrom(Answered(200, Map.empty, pendGet), charge()),
      Initiation.Redirected("https://ts.secure1gateway.com/api/v2/ProcessTxGetHosted?transkey=43ba057c581bcc0939547b5262241229",
        Some("T8335526082011180259")))

  test("initiate posts processTx: credentials in the body, account_sha, %d.%02d, the payment id, NA state"):
    val (call, seen) = scripted(200, pendGet)
    EuPaymentz.initiate(charge(5000), credential, customer, "https://eup.example/", call)
    val out = seen().head
    assertEquals(out.url, "https://eup.example/api/v2/processTx")
    assertEquals(out.contentType, Some("application/json"))
    val body = Json.parseObject(out.body.get).get
    def f(k: String) = body.get(k).asText
    assertEquals(f("transac_amount"), "50.00")
    assertEquals(f("account_id"), "sec1_spinvibecasino_ftd")
    assertEquals(f("account_password"), "pw-secret")
    assertEquals(f("account_sha"), EuPaymentz.outboundSha(passphrase, "50.00", "sec1_spinvibecasino_ftd", "player@example.com", "192.168.1.1"))
    assertEquals(f("merchant_payment_id"), paymentId)
    assertEquals(f("cust_billing_state"), "NA")
    assertEquals(f("action_type"), "payment")
    assertEquals(f("account_gateway"), "1")
    assertEquals(f("transac_products_name"), "Casino Deposit")
    assertEquals(f("merchant_url_return"), charge().returnUrl)
    assertEquals(f("merchant_url_callback"), charge().webhookUrl)
    assert(!body.has("passphrase") && !out.body.get.contains(passphrase))

  test("a POST redirect becomes the gateway's relay page and EuPaymentz's posted parameters"):
    val post = """{"success":true,"data":{"resp_trans_id":"T1","resp_trans_status":"PEND","UrlToRedirect":"https://eup.example/3ds","UrlToRedirectMethod":"POST","UrlToRedirecPostedParameters":[{"key":"PaReq","value":"abc"},{"key":"MD","value":"m-1"}]}}"""
    assertEquals(EuPaymentz.redirectFrom(Answered(200, Map.empty, post), charge()),
      Initiation.Redirected(s"https://psp.example.test/v1/psp/checkout/eupaymentz/$paymentId", Some("T1"),
        Map("relayAction" -> "https://eup.example/3ds"), Some(Form.of("PaReq" -> "abc", "MD" -> "m-1"))))

  test("not 200, success false, not PEND, or no UrlToRedirect is unavailable"):
    def r(status: Int, body: String) = EuPaymentz.redirectFrom(Answered(status, Map.empty, body), charge())
    assertEquals(r(201, pendGet), Initiation.Unavailable("eupaymentz: processTx returned unexpected HTTP 201"))
    assertEquals(r(200, """{"success":false}"""), Initiation.Unavailable("eupaymentz: processTx returned success=false"))
    assertEquals(r(200, "<html>"), Initiation.Unavailable("eupaymentz: failed to decode processTx response"))
    assertEquals(r(200, """{"success":true,"data":{"resp_trans_status":"PEND"}}"""),
      Initiation.Unavailable("eupaymentz: processTx returned PEND but no UrlToRedirect"))
    assert(r(200, """{"success":true,"data":{"resp_trans_status":"00005","UrlToRedirect":"x"}}""").isInstanceOf[Initiation.Unavailable])
    assertEquals(EuPaymentz.initiate(charge(), credential, customer, "https://e", _ => Left(CallFailure.Unreachable("refused"))),
      Initiation.Unavailable("eupaymentz: processTx request failed: refused"))

  test("missing credentials are the original's error, naming keys only"):
    assertEquals(EuPaymentz.initiate(charge(), Credential(credential.values - "passphrase"), customer, "https://e", scripted(200, pendGet)._1),
      Initiation.Unavailable("eupaymentz: missing required credentials (accountId, password, passphrase)"))

  // ── webhook_eupaymentz_test.go and webhook.go:1144-1250 ─────────────────────

  private def callback(status: String, amount: String = "100.00", transId: String = "T1987026090110470858",
      merchantId: String = paymentId, description: String = "Payment Accepted", key: String = passphrase): Raw =
    Raw(Json.render(Json.obj(
      "UrlToRedirecPostedParameters"  -> Json.arr(),
      "resp_trans_id"                 -> transId,
      "resp_trans_merchant_id"        -> merchantId,
      "resp_trans_amount"             -> amount,
      "resp_action_type"              -> "payment",
      "resp_trans_status"             -> status,
      "resp_trans_detailled_status"   -> "0000",
      "resp_trans_description_status" -> description,
      "resp_merchant_data1"           -> "",
      "resp_sha"                      -> Digests.sha256Hex(key + transId + amount + status),
      "resp_descriptor"               -> "-",
      "ErrorsDescriptionCodes"        -> null,
      "isDirectResult"                -> true,
      "UrlToRedirect"                 -> "",
      "UrlToRedirectMethod"           -> null,
      "ExtraData"                     -> "9865"
    )), Some("application/json"))

  test("TestEuPaymentzWebhookAcceptsFlatPayloadShape: the flat callback is located and captures"):
    val raw = callback("00000")
    assertEquals(EuPaymentz.locate(raw), Right(Located.Deposit(paymentId)))
    assertEquals(EuPaymentz.webhook(raw, charge(), credential, now), Handled(Verdict.Settle(Outcome.Captured(), "00000"), Reply.ok))

  test("PEND is open; any other status fails with the description, or Payment declined"):
    assertEquals(EuPaymentz.webhook(callback("PEND"), charge(), credential, now).verdict, Verdict.Open("PEND"))
    assertEquals(EuPaymentz.webhook(callback("T00051", description = "Insufficient funds"), charge(), credential, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("T00051"), "Insufficient funds"), "T00051"))
    assertEquals(EuPaymentz.webhook(callback("S00052", description = ""), charge(), credential, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("S00052"), "Payment declined"), "S00052"))

  test("a bad resp_sha or a missing passphrase is rejected, answered 400 (the 2026-10-19 decision)"):
    assertEquals(EuPaymentz.webhook(callback("00000", key = "other"), charge(), credential, now),
      Handled(Verdict.Reject("resp_sha does not verify"), Reply.invalidSignature))
    assertEquals(EuPaymentz.webhook(callback("00000"), charge(), Credential(credential.values - "passphrase"), now).verdict,
      Verdict.Reject("missing passphrase"))

  test("an upper-case resp_sha verifies"):
    val raw = callback("00000")
    val upper = raw.copy(body = raw.body.replace(Digests.sha256Hex(passphrase + "T1987026090110470858" + "100.00" + "00000"),
      Digests.sha256Hex(passphrase + "T1987026090110470858" + "100.00" + "00000").toUpperCase))
    assertEquals(EuPaymentz.webhook(upper, charge(), credential, now).verdict, Verdict.Settle(Outcome.Captured(), "00000"))

  test("finding 4: a signed success replayed under another session's reference does not verify, and is answered 400"):
    def handled(r: Raw) = { val h = EuPaymentz.webhook(r, charge(), credential, now); (h.verdict, h.reply) }
    assertEquals(handled(callback("00000", transId = "T0000000000000000001")),
      (Verdict.Reject("resp_trans_id does not match the session"), Reply.invalidSignature))
    assertEquals(handled(callback("00000", amount = "50.00")), (Verdict.Reject("amount does not match the session"), Reply.invalidSignature))
    assertEquals(handled(callback("00000", merchantId = "other")), (Verdict.Reject("reference does not match the session"), Reply.invalidSignature))

  test("an unreadable or unnamed callback, and an unknown one, are answered 200"):
    assertEquals(EuPaymentz.locate(Raw("garbage")), Left(Reply.ok))
    assertEquals(EuPaymentz.locate(Raw("""{"resp_trans_id":"T1"}""")), Left(Reply.ok))
    assertEquals(EuPaymentz.unknown(callback("00000")), Reply.ok)
