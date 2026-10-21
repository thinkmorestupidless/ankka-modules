package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.providers.unityfinance.UnityFinance
import io.eitheror.casino.pspgateway.sessions.Outcome

import java.time.Instant

/**
 * Unity Finance, ported from `unityfinance/signature_test.go`, `adapter_test.go`, `payout_test.go` and
 * `sessions_test.go` (whose in-memory store is the session's `providerData` here), with the webhook
 * cases of `handler/webhook.go` and `handler/withdrawal_webhook.go`.
 */
class UnityFinanceSuite extends munit.FunSuite:
  private val EUR = Currency.unsafe("EUR")
  private val now = Instant.parse("2026-10-18T12:00:00Z")

  // signature_test.go:5-23
  private val testSignKey = "1QDhQe8kTYzGZ3efeSAoZJ9gmeHkJHZV"
  private val testVector  = "ZzJMPiIpUyRGB+pP8BboV2+xdRNM8jU/R054oHQYUIc="
  private val testParams = Map(
    "ik_act" -> "process", "ik_am" -> "100.00", "ik_co_id" -> "test_checkout_id", "ik_cur" -> "EUR",
    "ik_desc" -> "payment for the order 1234", "ik_payment_currency" -> "EUR", "ik_payment_method" -> "apaym",
    "ik_pm_no" -> "payment1"
  )
  private def signedForm(params: Map[String, String], key: String = testSignKey): Form =
    Form((params + ("ik_sign" -> UnityFinance.sign(params, key))).toVector)

  // ---- signature_test.go

  test("TestSign_KnownVector"):
    assertEquals(UnityFinance.sign(testParams, testSignKey), testVector)

  test("TestVerify_ValidSignature"):
    assert(UnityFinance.verify(Form((testParams + ("ik_sign" -> testVector)).toVector), testSignKey))

  test("TestVerify_InvalidSignature"):
    assert(!UnityFinance.verify(Form((testParams + ("ik_sign" -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")).toVector), testSignKey))

  test("TestVerify_ExcludesIkSign: ik_sign is left out of its own hash"):
    val form = Form((testParams + ("ik_sign" -> testVector)).toVector)
    assert(UnityFinance.verify(form, testSignKey))
    assert(!UnityFinance.verify(form.without("ik_sign"), testSignKey))

  test("TestSign_WrongKey"):
    assertNotEquals(UnityFinance.sign(testParams, "wrong-key"), testVector)

  test("TestSign_EmptyParams"):
    assert(UnityFinance.sign(Map.empty, testSignKey).nonEmpty)

  test("verify: a repeated field signs its first value, as Go's r.Form"):
    val form = Form(signedForm(testParams).entries :+ ("ik_am" -> "999.00"))
    assert(UnityFinance.verify(form, testSignKey))

  test("verify: a tampered field fails"):
    val form = Form(signedForm(testParams).entries.map { case ("ik_am", _) => "ik_am" -> "1000.00"; case e => e })
    assert(!UnityFinance.verify(form, testSignKey))

  // ---- adapter_test.go

  test("TestMethodCodeAcceptsCurrentAndLegacySchemeNames"):
    val cases = Vector("VISA" -> "visa", "MASTERCARD" -> "mastercard", "APPLE_PAY" -> "apay", "GOOGLE_PAY" -> "gpay",
      "OPEN_BANKING" -> "eubanking", "CARD_VISA" -> "visa", "CARD_MASTERCARD" -> "mastercard", "CARD_TEST" -> "card")
    cases.foreach((method, want) => assertEquals(UnityFinance.methodCode(method), Some(want), method))

  test("TestMethodCodeRejectsGatewayPrefixedSchemeIds"):
    Vector("UNITYFINANCE_CARD", "UNITYFINANCE_WALLET", "UNITYFINANCE_EUBANKING", "").foreach { m =>
      assertEquals(UnityFinance.methodCode(m), None, m)
    }

  test("TestIsCardMethodCoversCurrentAndLegacyNames"):
    Vector("VISA", "MASTERCARD", "CARD_VISA", "CARD_MASTERCARD", "CARD_TEST").foreach(m => assert(UnityFinance.isCardMethod(m), m))
    Vector("APPLE_PAY", "GOOGLE_PAY", "OPEN_BANKING").foreach(m => assert(!UnityFinance.isCardMethod(m), m))

  private val paymentId = "7d0c7a3e-2f1b-4a52-9c8e-0f4a1b2c3d4e"
  private def charge(method: String, amount: Long = 10000, providerData: Map[String, String] = Map.empty) = Charge(
    paymentId, amount, EUR, method, Some("term-1"),
    returnUrl = s"https://psp.example.test/v1/psp/checkout/$paymentId/return",
    webhookUrl = "https://psp.example.test/v1/webhooks/unityfinance",
    providerData = providerData
  )
  private val fullCustomer = Customer(
    email = Some("player@example.test"), firstName = Some("John"), lastName = Some("Smith"), phone = Some("+44100"),
    ip = Some("1.2.3.4"), country = Some("GB"), city = Some("London"), address = Some("1 Test St"), postCode = Some("E1"),
    state = Some("LDN")
  )
  private val payUrl = "https://pay.uf.example/"

  private def scripted(answer: Either[CallFailure, Answered]): (Call, () => Vector[Outbound]) =
    var seen = Vector.empty[Outbound]
    ((o: Outbound) => { seen :+= o; answer }, () => seen)

  private val okInvoice = Answered(200, Map.empty, """{"resultCode":0,"resultData":{"paymentForm":{"action":"https://uf.example/pay/1"}}}""")

  test("TestInitiateOpenBankingSendsPayerNameButNotCardFields"):
    val (call, seen) = scripted(Right(okInvoice))
    val result = UnityFinance.initiate(charge("OPEN_BANKING"), Credential(Map("signKey" -> "sk", "checkoutId.OPEN_BANKING" -> "co-ob")),
      fullCustomer, payUrl, call)
    assertEquals(result, Initiation.Redirected("https://uf.example/pay/1", None, Map("checkoutId" -> "co-ob")))
    val out = seen().head
    assertEquals(out.method, "POST")
    assertEquals(out.url, payUrl)
    assertEquals(out.contentType, Some("application/x-www-form-urlencoded"))
    val form = Form.decode(out.body.get)
    assertEquals(form.get("ik_payment_method"), Some("eubanking"))
    assertEquals(form.get("ik_co_id"), Some("co-ob"))
    assertEquals(form.get("ik_customer_first_name"), Some("John"))
    assertEquals(form.get("ik_customer_last_name"), Some("Smith"))
    assertEquals(form.get("ik_customer_email"), Some("player@example.test"))
    assert(!form.get("ik_suc_u").get.contains("unityfinance"))
    assertEquals(form.get("ik_suc_u"), form.get("ik_fal_u"))
    Vector("ik_customer_phone", "ik_customer_ip", "ik_customer_country", "ik_customer_city", "ik_customer_address",
      "ik_customer_post_code", "ik_customer_state").foreach(k => assertEquals(form.get(k), None, k))

  test("invoice: the payment id is ik_pm_no, the amount %.2f, ik_int json, signed over every field"):
    val form = UnityFinance.invoice(charge("APPLE_PAY", amount = 1500), Credential(Map("signKey" -> "sk", "checkoutId.APPLE_PAY" -> "co-ap")),
      Customer(email = Some("p@example.test"), firstName = Some("John"))).toOption.get
    assertEquals(form.get("ik_pm_no"), Some(paymentId))
    assertEquals(form.get("ik_desc"), Some(s"payment $paymentId"))
    assertEquals(form.get("ik_am"), Some("15.00"))
    assertEquals(form.get("ik_cur"), Some("EUR"))
    assertEquals(form.get("ik_payment_currency"), Some("EUR"))
    assertEquals(form.get("ik_act"), Some("process"))
    assertEquals(form.get("ik_int"), Some("json"))
    assertEquals(form.get("ik_mode"), None)
    assertEquals(form.get("ik_ia_u"), Some("https://psp.example.test/v1/webhooks/unityfinance"))
    // A wallet carries only the email.
    assertEquals(form.get("ik_customer_first_name"), None)
    assert(UnityFinance.verify(form, "sk"))

  test("invoice: a card carries the full identity; a missing email is sent empty and signed"):
    val form = UnityFinance.invoice(charge("VISA"), Credential(Map("signKey" -> "sk", "checkoutId.VISA" -> "co-v")),
      fullCustomer.copy(email = None, city = None)).toOption.get
    assertEquals(form.get("ik_customer_email"), Some(""))
    assertEquals(form.get("ik_customer_post_code"), Some("E1"))
    assertEquals(form.get("ik_customer_city"), None)
    assert(UnityFinance.verify(form, "sk"))

  test("invoice: a wallet needing identity carries name and country only"):
    val form = UnityFinance.invoice(charge("SKRILL"), Credential(Map("signKey" -> "sk", "checkoutId.SKRILL" -> "co-s")),
      fullCustomer).toOption.get
    assertEquals(form.get("ik_customer_country"), Some("GB"))
    assertEquals(form.get("ik_customer_phone"), None)

  private def initiateWith(method: String, creds: Map[String, String]): Initiation =
    UnityFinance.initiate(charge(method), Credential(creds), Customer(), payUrl, _ => fail("no call expected"))

  test("TestInitiateOpenBankingRequiresItsOwnCheckoutId"):
    initiateWith("OPEN_BANKING", Map("signKey" -> "sk", "withdrawalApiKey" -> "not-a-checkout-id")) match
      case Initiation.Refused(_, message) => assert(message.contains("missing credential checkoutId.OPEN_BANKING"), message)
      case other                          => fail(s"refused expected: $other")

  test("TestInitiateRequiresCheckoutIdMatchingTheScheme"):
    initiateWith("VISA", Map("signKey" -> "sk", "checkoutId.CARD_VISA" -> "co-legacy", "checkoutId" -> "co-plain")) match
      case Initiation.Refused(_, message) => assert(message.contains("missing credential checkoutId.VISA"), message)
      case other                          => fail(s"refused expected: $other")

  test("TestInitiateRejectsBlankCheckoutId"):
    initiateWith("MASTERCARD", Map("signKey" -> "sk", "checkoutId.MASTERCARD" -> "")) match
      case Initiation.Refused(_, message) => assert(message.contains("missing credential checkoutId.MASTERCARD"), message)
      case other                          => fail(s"refused expected: $other")

  test("TestInitiateRequiresSignKey"):
    initiateWith("VISA", Map("checkoutId.VISA" -> "co-1")) match
      case Initiation.Refused(_, message) => assert(message.contains("missing credential signKey"), message)
      case other                          => fail(s"refused expected: $other")

  test("TestInitiateRejectsAnUnsupportedMethodBeforeCallingUnityFinance"):
    assertEquals(initiateWith("UNITYFINANCE_CARD", Map("signKey" -> "sk", "checkoutId.UNITYFINANCE_CARD" -> "co-1")),
      Initiation.Refused("unsupported_method", "unityfinance: unsupported method \"UNITYFINANCE_CARD\""))

  private val visaCreds = Credential(Map("signKey" -> "sk-secret", "checkoutId.VISA" -> "co-v"))

  test("initiate: a get payment form appends its parameters as the query"):
    val (call, _) = scripted(Right(Answered(200, Map.empty,
      """{"resultCode":0,"resultData":{"paymentForm":{"action":"https://uf.example/pay","method":"get","parameters":{"b":"2 x","a":"1"}}}}""")))
    assertEquals(UnityFinance.initiate(charge("VISA"), visaCreds, fullCustomer, payUrl, call),
      Initiation.Redirected("https://uf.example/pay?a=1&b=2+x", None, Map("checkoutId" -> "co-v")))

  test("initiate: a non-zero resultCode, a missing action, a non-2xx, an unreadable body or a failed call is unavailable"):
    val answers = Vector(
      Right(Answered(200, Map.empty, """{"resultCode":115,"resultMsg":"error 115"}""")),
      Right(Answered(200, Map.empty, """{"resultCode":0,"resultData":{}}""")),
      Right(Answered(502, Map.empty, "<html>bad gateway</html>")),
      Right(Answered(200, Map.empty, "<html>")),
      Left(CallFailure.TimedOut),
      Left(CallFailure.Unreachable("connection refused"))
    )
    answers.foreach { a =>
      val (call, _) = scripted(a)
      UnityFinance.initiate(charge("VISA"), visaCreds, fullCustomer, payUrl, call) match
        case Initiation.Unavailable(reason) => assert(!reason.contains("sk-secret"), reason)
        case other                          => fail(s"unavailable expected for $a: $other")
    }

  // ---- the deposit webhook (handler/webhook.go:256-415)

  private def depositWebhook(fields: Map[String, String], key: String = "sk-secret"): Raw =
    Raw(signedForm(fields, key).encode, Some("application/x-www-form-urlencoded"))
  private val notice = Map("ik_co_id" -> "co-v", "ik_pm_no" -> paymentId, "ik_am" -> "100.00", "ik_cur" -> "EUR", "ik_inv_st" -> "success")
  private val initiated = charge("VISA", providerData = Map("checkoutId" -> "co-v"))

  test("locate: ik_pm_no is the payment id; none is acknowledged"):
    assertEquals(UnityFinance.locate(depositWebhook(notice)), Right(Located.Deposit(paymentId)))
    assertEquals(UnityFinance.locate(Raw("ik_inv_st=success")), Left(Reply.ok))
    assertEquals(UnityFinance.unknown(Raw("")), Reply.ok)

  test("webhook: success is captured, answered 200 empty"):
    assertEquals(UnityFinance.webhook(depositWebhook(notice), initiated, visaCreds, now),
      Handled(Verdict.Settle(Outcome.Captured(), "success"), Reply.ok))

  test("webhook: canceled is failed with the cancel code and UF's own description"):
    val h = UnityFinance.webhook(depositWebhook(notice ++ Map("ik_inv_st" -> "canceled", "ik_inv_cancel_code" -> "3",
      "ik_error_desc" -> "Card declined")), initiated, visaCreds, now)
    assertEquals(h.verdict, Verdict.Settle(Outcome.Failed(Some("3"), "Card declined"), "canceled"))

  test("webhook: canceled and expired without a code or description fall back to the state and the original's reasons"):
    assertEquals(UnityFinance.webhook(depositWebhook(notice + ("ik_inv_st" -> "canceled")), initiated, visaCreds, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("canceled"), "Payment cancelled"), "canceled"))
    assertEquals(UnityFinance.webhook(depositWebhook(notice + ("ik_inv_st" -> "expired")), initiated, visaCreds, now).verdict,
      Verdict.Settle(Outcome.Failed(Some("expired"), "Payment session expired"), "expired"))

  test("webhook: the non-final states are open; refunds and chargebacks are logged; an unknown state ignored"):
    UnityFinance.openStates.foreach { s =>
      assertEquals(UnityFinance.webhook(depositWebhook(notice + ("ik_inv_st" -> s)), initiated, visaCreds, now).verdict, Verdict.Open(s))
    }
    Vector("refunded", "partial_refunded", "chargebacked", "mystery").foreach { s =>
      UnityFinance.webhook(depositWebhook(notice + ("ik_inv_st" -> s)), initiated, visaCreds, now).verdict match
        case Verdict.Ignore(_) => ()
        case other             => fail(s"ignore expected for $s: $other")
    }

  test("webhook: a bad signature, a missing sign key, or another checkout id, amount or currency is answered 400 (FR-013)"):
    val cases = Vector(
      UnityFinance.webhook(depositWebhook(notice, key = "other-key"), initiated, visaCreds, now),
      UnityFinance.webhook(depositWebhook(notice), initiated, Credential(Map("checkoutId.VISA" -> "co-v")), now),
      UnityFinance.webhook(depositWebhook(notice + ("ik_co_id" -> "co-other")), initiated, visaCreds, now),
      UnityFinance.webhook(depositWebhook(notice + ("ik_am" -> "100.01")), initiated, visaCreds, now),
      UnityFinance.webhook(depositWebhook(notice + ("ik_cur" -> "USD")), initiated, visaCreds, now)
    )
    cases.foreach { h =>
      assertEquals(h.reply, Reply.invalidSignature)
      h.verdict match
        case Verdict.Reject(reason) => assert(!reason.contains("sk-secret"), reason)
        case other                  => fail(s"reject expected: $other")
    }

  test("webhook: the amount compares as a number (100 is 100.00)"):
    assertEquals(UnityFinance.webhook(depositWebhook(notice + ("ik_am" -> "100")), initiated, visaCreds, now).verdict,
      Verdict.Settle(Outcome.Captured(), "success"))

  // sessions_test.go: the in-memory store kept the checkout id for the webhook's cross-check; here the
  // initiation's providerData does, and before it is recorded the credential's checkout id stands in.
  test("sessions_test.go: the checkout id is kept in providerData and the webhook checks against it"):
    val (call, _) = scripted(Right(okInvoice))
    val Initiation.Redirected(_, _, data, _) = UnityFinance.initiate(charge("VISA"), visaCreds, fullCustomer, payUrl, call): @unchecked
    assertEquals(data, Map("checkoutId" -> "co-v"))
    val other = charge("VISA", providerData = Map("checkoutId" -> "co-elsewhere"))
    assert(UnityFinance.webhook(depositWebhook(notice), other, visaCreds, now).verdict.isInstanceOf[Verdict.Reject])

  test("sessions_test.go: a claimed session with no providerData checks the credential's checkout id"):
    val claimed = charge("VISA")
    assertEquals(UnityFinance.webhook(depositWebhook(notice), claimed, visaCreds, now).verdict, Verdict.Settle(Outcome.Captured(), "success"))
    assert(UnityFinance.webhook(depositWebhook(notice + ("ik_co_id" -> "co-x")), claimed, visaCreds, now).verdict.isInstanceOf[Verdict.Reject])

  test("the advisory source addresses"):
    assert(UnityFinance.knownSource("34.34.83.66"))
    assert(!UnityFinance.knownSource("10.0.0.1"))

  // ---- payout_test.go

  private def okEnvelope(id: String, state: Int) =
    s"""{"status":"ok","code":0,"message":"Success","data":{"withdraw":{"id":"$id","trnId":"37087556","paymentNo":"wr-1","state":$state,"result":0,"psResultDesc":"Success","details":{"iban":"GB33TRWI23080124752518"}},"transaction":{"id":"37087556","state":0},"@resultCode":0,"@resultMessage":"Success"}}"""
  private def errEnvelope(message: String) = s"""{"status":"error","code":4,"data":{"innerCode":0},"message":"$message"}"""
  private def recoverEnvelope(id: String, state: String) =
    s"""{"status":"ok","code":0,"data":[{"id":"$id","psTrnId":"f9635bef-bcbe-4852-b666-526b4251c85f","purseId":"100592253894","accountId":"69c57104bb03de09bf035413","coId":"69c57c61108c6174bf6a4c34","paymentNo":"wr-1","paywayId":"6949716a4aa39835311a7b6d","state":"$state","result":"0","payerWriteoff":"12.35","payeeReceive":"12.35","details":{"iban":"GB33TRWI23080124752518"},"calc":"payee","psResultDesc":"Success","stateName":"canceled","currencyCodeChar":"EUR"}]}"""

  private val apiUrl = "https://api.uf.example/v1"
  private val payoutCreds = Credential(Map("userId" -> "uf-user", "withdrawalApiKey" -> "uf-key", "accountId" -> "acct-1",
    "purseId" -> "purse-1", "signKey" -> "sk-secret"))
  private def withdrawal(requestId: String = "wr-abc-123", destination: Map[String, String] = Map(
      "firstName" -> "John", "lastName" -> "Smith", "email" -> "john.smith@example.com", "iban" -> "DE89370400440532013000"),
      providerReference: Option[String] = None) =
    Withdrawal(requestId, "goldvibe", 9900, EUR, "OPEN_BANKING", "term-1",
      "https://psp.example.test/v1/webhooks/unityfinance/withdrawal", destination, providerReference = providerReference)

  private def answered(status: Int, body: String) = Right(Answered(status, Map.empty, body))

  test("TestPayoutSendsExpectedFieldsAndAuth"):
    val (call, seen) = scripted(answered(200, okEnvelope("uf-9", 4)))
    assertEquals(UnityFinance.payout(withdrawal(), payoutCreds, apiUrl, call), PayoutAnswer.Initiated(Some("uf-9")))
    val out = seen().head
    assertEquals(out.method, "POST")
    assertEquals(out.url, s"$apiUrl/withdraw")
    assert(out.headers.contains("Authorization" -> "Basic dWYtdXNlcjp1Zi1rZXk="))
    assert(out.headers.contains("Ik-Api-Account-Id" -> "acct-1"))
    val form = Form.decode(out.body.get)
    Vector("method" -> "eubanking", "action" -> "process", "calcKey" -> "psPayeeAmount", "amount" -> "99.00",
      "currency" -> "EUR", "purseId" -> "purse-1", "paymentNo" -> "wr-abc-123", "useShortAlias" -> "true",
      "details[first_name]" -> "John", "details[last_name]" -> "Smith", "details[email]" -> "john.smith@example.com",
      "details[iban]" -> "DE89370400440532013000",
      "interactionUrl" -> "https://psp.example.test/v1/webhooks/unityfinance/withdrawal"
    ).foreach((k, v) => assertEquals(form.get(k), Some(v), k))
    assert(!out.toString.contains("uf-key"))

  test("TestPayoutReportsInitiatedWhateverStateUFReturns"):
    Vector(0, 4, 6, 8).foreach { state =>
      assertEquals(UnityFinance.payoutAnswer(200, okEnvelope("uf-9", state)), PayoutAnswer.Initiated(Some("uf-9")), s"state=$state")
    }

  test("TestPayoutReportsDuplicateAsAlreadyInitiated"):
    Vector("Duplicate paymentNo", "payment already exists").foreach { msg =>
      assertEquals(UnityFinance.payoutAnswer(200, errEnvelope(msg)), PayoutAnswer.AlreadyInitiated, msg)
    }

  test("TestPayoutReportsBusinessRejection"):
    assertEquals(UnityFinance.payoutAnswer(200, errEnvelope("insufficient purse balance")),
      PayoutAnswer.Rejected("insufficient purse balance"))

  test("TestPayoutReturnsErrorOnUnreadableResponse: in doubt"):
    assert(UnityFinance.payoutAnswer(502, "<html>gateway timeout</html>").isInstanceOf[PayoutAnswer.InDoubt])
    assert(UnityFinance.payoutAnswer(200, "<html>").isInstanceOf[PayoutAnswer.InDoubt])
    assert(UnityFinance.payoutAnswer(200, """{"status":"ok","data":{}}""").isInstanceOf[PayoutAnswer.InDoubt])
    Vector(Left(CallFailure.TimedOut), Left(CallFailure.Unreachable("reset"))).foreach { f =>
      val (call, _) = scripted(f)
      assert(UnityFinance.payout(withdrawal(), payoutCreds, apiUrl, call).isInstanceOf[PayoutAnswer.InDoubt])
    }

  test("401 and 403 are rejected unauthorised, a non-JSON body too (research finding 8)"):
    assertEquals(UnityFinance.payoutAnswer(401, errEnvelope("Auth: Invalid login or password provided")), PayoutAnswer.Rejected("unauthorised"))
    assertEquals(UnityFinance.payoutAnswer(403, "<html>forbidden</html>"), PayoutAnswer.Rejected("unauthorised"))

  test("TestPayoutOmitsEmailFieldWhenEmpty"):
    val (call, seen) = scripted(answered(200, okEnvelope("uf-1", 1)))
    UnityFinance.payout(withdrawal(destination = Map("firstName" -> "John", "lastName" -> "Smith", "iban" -> "DE89", "email" -> "")),
      payoutCreds, apiUrl, call)
    assert(!Form.decode(seen().head.body.get).keys.contains("details[email]"))

  test("payout: a missing credential is rejected missing_credentials with no call (payout.go:260-265)"):
    Vector("userId", "withdrawalApiKey", "accountId", "purseId").foreach { key =>
      assertEquals(UnityFinance.payout(withdrawal(), Credential(payoutCreds.values - key), apiUrl, _ => fail("no call expected")),
        PayoutAnswer.Rejected("missing_credentials"), key)
    }

  test("TestRecoverPayoutReturnsNilWhenUFHasNoRecord"):
    assertEquals(UnityFinance.payoutStatus(200, """{"status":"ok","code":0,"data":[]}"""), PayoutStatus.NoRecord)

  test("TestRecoverPayoutResolvesTerminalStatus"):
    val (call, seen) = scripted(answered(200, recoverEnvelope("uf-42", "11")))
    assertEquals(UnityFinance.status(withdrawal("wr abc/123"), payoutCreds, apiUrl, call), Some(PayoutStatus.Final(Outcome.Reversed, "11")))
    val out = seen().head
    assertEquals(out.method, "GET")
    assertEquals(out.url.takeWhile(_ != '?'), s"$apiUrl/withdraw")
    assertEquals(Form.decode(out.url.dropWhile(_ != '?').drop(1)).get("paymentNo"), Some("wr abc/123"))
    assert(out.headers.contains("Authorization" -> "Basic dWYtdXNlcjp1Zi1rZXk="))
    assert(out.headers.contains("Ik-Api-Account-Id" -> "acct-1"))

  test("TestRecoverPayoutParsesQuotedStateInArrayEnvelope"):
    assertEquals(UnityFinance.payoutStatus(200, recoverEnvelope("4937870", "9")),
      PayoutStatus.Final(Outcome.PayoutFailed(Some("9"), "canceled"), "9"))

  test("TestMapWithdrawalStatusOnlyResolvesTerminalCodes"):
    def state(s: String) = UnityFinance.payoutStatus(200, recoverEnvelope("uf-1", s))
    assertEquals(state("8"), PayoutStatus.Final(Outcome.Completed, "8"))
    assert(state("20").isInstanceOf[PayoutStatus.Final])
    assertEquals(state("11"), PayoutStatus.Final(Outcome.Reversed, "11"))
    Vector("1", "", "99").foreach(s => assertEquals(state(s), PayoutStatus.Pending(s), s))

  test("status: never no record from an answer UF did not give (adapter.go:589-598)"):
    Vector(
      UnityFinance.payoutStatus(401, """{"status":"error","message":"Auth"}"""),
      UnityFinance.payoutStatus(200, """{"status":"error","code":4,"message":"Auth"}"""),
      UnityFinance.payoutStatus(200, "<html>"),
      UnityFinance.payoutStatus(200, """{"status":"ok","code":0}""")
    ).foreach(s => assert(s.isInstanceOf[PayoutStatus.Unavailable], s.toString))
    assertEquals(UnityFinance.status(withdrawal(), Credential(payoutCreds.values - "accountId"), apiUrl, _ => fail("no call expected")),
      Some(PayoutStatus.Unavailable("missing credential accountId")))
    val (call, _) = scripted(Left(CallFailure.TimedOut))
    assert(UnityFinance.status(withdrawal(), payoutCreds, apiUrl, call).exists(_.isInstanceOf[PayoutStatus.Unavailable]))

  // ---- the withdrawal webhook (handler/withdrawal_webhook.go)

  // The callback confirmed live on 2026-08-06 (withdrawal_webhook.go:32-34).
  private val callback = Map("id" -> "4944009", "purseId" -> "100592253894", "paymentNo" -> "wr-abc-123", "state" -> "canceled",
    "processed" -> "", "payerWriteoffAmount" -> "12.4000", "payeeReceiveAmount" -> "11.17", "created" -> "2026-08-06 10:00:00",
    "details_first_name" -> "Trevor", "details_iban" -> "GB33TRWI23080124752518", "details_last_name" -> "Burton")
  private def withdrawalWebhook(fields: Map[String, String], key: String = "sk-secret") =
    Raw(signedForm(fields, key).encode, Some("application/x-www-form-urlencoded"),
      query = Map("merchantId" -> "m1", "brandId" -> "b1", "terminalId" -> "term-1"))

  test("locatePayout: paymentNo is the request id; the query is not read"):
    assertEquals(UnityFinance.locatePayout(withdrawalWebhook(callback)), Right(Located.Payout("wr-abc-123")))
    assertEquals(UnityFinance.locatePayout(Raw("state=success", query = Map("paymentNo" -> "x"))), Left(Reply.ok))
    assertEquals(UnityFinance.unknownPayout(Raw("")), Reply.ok)

  test("payoutWebhook: signed over the body only, the query's fields outside it"):
    assertEquals(UnityFinance.payoutWebhook(withdrawalWebhook(callback), withdrawal(), payoutCreds, now),
      Handled(Verdict.Settle(Outcome.PayoutFailed(Some("canceled"), "Payout cancelled"), "canceled"), Reply.ok))

  test("withdrawalOutcome: state by name (MapWithdrawalStateName)"):
    def v(state: String) = UnityFinance.payoutWebhook(withdrawalWebhook(callback + ("state" -> state)), withdrawal(), payoutCreds, now).verdict
    assertEquals(v("success"), Verdict.Settle(Outcome.Completed, "success"))
    assertEquals(v("limitExceeded"), Verdict.Settle(Outcome.PayoutFailed(Some("limitExceeded"), "Payout limit exceeded"), "limitExceeded"))
    assertEquals(v("chargebacked"), Verdict.Settle(Outcome.Reversed, "chargebacked"))
    Vector("hold", "process", "enroll", "8").foreach(s => assertEquals(v(s), Verdict.Open(s), s))

  test("payoutWebhook: a bad signature, a missing sign key or another id is answered 400 (FR-013, the 2026-10-20 decision)"):
    Vector(
      UnityFinance.payoutWebhook(withdrawalWebhook(callback, key = "other"), withdrawal(), payoutCreds, now) -> Reply.invalidSignature,
      UnityFinance.payoutWebhook(withdrawalWebhook(callback), withdrawal(), Credential(payoutCreds.values - "signKey"), now) -> Reply.invalidSignature,
      UnityFinance.payoutWebhook(withdrawalWebhook(callback), withdrawal(providerReference = Some("4944010")), payoutCreds, now) -> Reply.invalidSignature
    ).foreach { (h, reply) =>
      assertEquals(h.reply, reply)
      assert(h.verdict.isInstanceOf[Verdict.Reject], h.verdict.toString)
    }
    assert(UnityFinance.payoutWebhook(withdrawalWebhook(callback), withdrawal(providerReference = Some("4944009")), payoutCreds, now)
      .verdict.isInstanceOf[Verdict.Settle])
