package io.eitheror.casino.merchant.features

import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.deposits.DepositFlow
import io.eitheror.casino.merchant.it.FakeGateway
import io.eitheror.casino.merchant.payments.{CreditState, Status}
import io.eitheror.casino.merchant.security.Secrets

import java.time.Duration as JDuration

/** features/payments/deposits.feature (US1, US2, US3, US7, FR-018 to FR-026). */
class DepositsFeatures extends MerchantFeatures("deposits.feature"):

  private val Secret = "whsec-features"
  private var body   = """{"outcome":"success"}"""
  private var before = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    body = """{"outcome":"success"}"""
    before = 0

  Given("a sub-brand {string} naming brand {string}") { (_: String, b: String) => ready = kit.subBrand(brand = Some(b)) }

  // ── captures (US1) ─────────────────────────────────────────────────────────────────────────────

  Given("a payment awaiting redirect") { () => paymentAwaiting() }
  Given("a captured payment") { () =>
    paymentAwaiting()
    assertEquals(kit.capture(paymentId).status, 200)
    before = kit.wallet.depositsOf(paymentId).size
  }

  When("the gateway reports the payment provider's capture") { () =>
    kit.wallet.delayMs = 200
    last = kit.capture(paymentId)
  }
  Then("the payment is captured") { () => assertEquals(kit.paymentState(paymentId).status, Status.Captured, last.toString) }
  Then("the brand's wallet is asked to confirm the deposit once, keyed by the payment id, with the payment provider's reference as the payment reference") { () =>
    val calls = kit.wallet.depositsOf(paymentId)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.json.get.textOr("paymentReference", ""), kit.paymentState(paymentId).opened.get.pspTransactionId)
  }
  Then("the capture is acknowledged to the gateway only after the wallet has answered") { () =>
    assertEquals((last.status, last.text), (200, """{"ok":true}"""))
    assertEquals(kit.paymentState(paymentId).credit.map(_.status), Some(CreditState.Credited), "the wallet had answered when the capture was")
  }
  Then("a {string} outcome reaches the brand for the payment") { (outcome: String) =>
    assertEquals(outcome, "payment.captured")
    assertEquals(kit.wallet.depositsOf(paymentId).size, 1, "the brand's wallet makes payment.captured from the deposit it confirmed")
  }

  When("the gateway reports the same capture again") { () => last = kit.capture(paymentId) }
  Then("the payment stays captured") { () => assertEquals(kit.paymentState(paymentId).status, Status.Captured) }
  Then("the wallet is not called again") { () => assertEquals(kit.wallet.depositsOf(paymentId).size, before) }
  Then("the player's deposit count rises by nothing") { () => assertEquals(kit.wallet.depositCount(brand, player, "EUR"), 1) }
  Then("the gateway is acknowledged") { () => assertEquals(last.status, 200, last.text) }

  Given("a sub-brand of type {string} that names no brand") { (_: String) => ready = kit.subBrand(brand = None) }
  When("the gateway reports the capture of its payment") { () =>
    paymentAwaiting()
    last = kit.capture(paymentId)
  }
  Then("no wallet is called") { () => assertEquals(kit.wallet.deposits.size, 0) }
  Then("the capture is acknowledged") { () => assertEquals(last.status, 200, last.text) }

  // ── failures, expiries and abandons (US2) ──────────────────────────────────────────────────────

  When("the gateway reports a failure with code {string} and reason {string}") { (code: String, reason: String) =>
    last = kit.fail(paymentId, "failureCode" -> code, "failureReason" -> reason)
  }
  Then("the payment is failed with that code and reason") { () =>
    val s = kit.paymentState(paymentId)
    assertEquals((s.status, s.failureCode, s.failureReason), (Status.Failed, Some("declined"), Some("Payment declined")))
  }
  Then("a {string} outcome reaches the brand carrying both") { (outcome: String) =>
    assertEquals(outcome, "payment.failed")
    val r = kit.eventually("reported")(kit.wallet.reported.find(r => r.paymentId == paymentId && r.outcome == "failed"))
    assertEquals((r.code, r.reason, r.paymentProvider), (Some("declined"), Some("Payment declined"), "DREAMZPAY"))
  }

  Given("a payment awaiting redirect whose deadline has passed and whose provider session the gateway reports as resolved or unknown") { () =>
    shortDeadline()
    paymentAwaiting()
    kit.gateway.session(paymentId, FakeGateway.Session.Resolved)
  }
  When("the deadline fires") { () => Thread.sleep(1500) }
  Then("the payment is expired with the reason {string}") { (reason: String) =>
    val s = kit.eventually("expired")(Option(kit.paymentState(paymentId)).filter(_.status == Status.Expired))
    assertEquals(s.failureReason, Some(reason))
  }
  Then("a {string} outcome reaches the brand") { (outcome: String) =>
    val kind = outcome.stripPrefix("payment.")
    kit.eventually(s"$kind reported")(kit.wallet.reported.find(r => r.paymentId == paymentId && r.outcome == kind))
  }

  Given("a payment awaiting redirect whose deadline has passed and whose outcome the gateway still holds undelivered") { () =>
    shortDeadline()
    paymentAwaiting()
    kit.gateway.session(paymentId, FakeGateway.Session.Undelivered)
  }
  Then("the payment is not expired") { () =>
    kit.eventually("waiting")(Option(kit.paymentState(paymentId)).filter(_.waitingReason.isDefined))
    assertNotEquals(kit.paymentState(paymentId).status, Status.Expired)
  }
  Then("the cashier waits again") { () => assertEquals(kit.flow(paymentId).status, DepositFlow.Waiting) }
  Then("the payment's status says why") { () =>
    assert(kit.read(paymentId).str("waitingReason").contains("undelivered"), kit.read(paymentId).text)
  }

  When("the player's frontend abandons the session with its cashier ticket") { () =>
    last = kit.request("POST", s"/v1/cashier/sessions/$token/abandon", None, kit.asIngest)
  }
  Then("the payment is failed with code {string} and reason {string}") { (code: String, reason: String) =>
    val s = kit.paymentState(paymentId)
    assertEquals((s.status, s.failureCode, s.failureReason), (Status.Failed, Some(code), Some(reason)))
  }

  When("a failure arrives for it") { () => last = kit.fail(paymentId, "failureCode" -> "late") }
  When("an expiry arrives for it") { () =>
    kit.clockOffset = JDuration.ofMinutes(31)
    last = kit.read(paymentId)
  }
  When("an abandon arrives for it") { () => last = kit.request("POST", s"/v1/cashier/sessions/$token/abandon", None, kit.asIngest) }
  Then("nothing changes") { () =>
    val s = kit.paymentState(paymentId)
    assertEquals((s.status, s.failureCode), (Status.Captured, None))
    assertEquals(kit.read(paymentId).str("status"), "COMPLETED")
  }
  Then("the caller is acknowledged") { () => assertEquals(last.status, 200, last.text) }

  Given("a payment past its deadline that nothing has yet touched") { () =>
    paymentAwaiting()
    kit.clockOffset = JDuration.ofMinutes(31)
  }
  When("the player's frontend reads it") { () => last = kit.read(paymentId) }
  Then("it is told {string} with the reason {string}") { (status: String, reason: String) =>
    assertEquals((last.str("status"), last.str("failureReason")), (status, reason))
  }

  // ── the credit (US3) ───────────────────────────────────────────────────────────────────────────

  Given("a payment captured while the brand's wallet is unreachable") { () =>
    paymentAwaiting()
    kit.wallet.down = true
    last = kit.capture(paymentId)
    kit.eventually("waiting")(Option(kit.flow(paymentId)).filter(_.status == DepositFlow.CreditWaiting))
  }
  When("the wallet comes back") { () => kit.wallet.down = false }
  Then("the deposit is credited once") { () =>
    kit.eventually("credited")(Option(kit.flow(paymentId)).filter(_.status == DepositFlow.Credited))
    assertEquals(kit.wallet.depositCount(brand, player, "EUR"), 1)
  }
  Then("the capture was not acknowledged to the gateway until it was") { () =>
    assertEquals((last.status, last.str("error")), (503, "OUTCOME_PENDING"))
    assertEquals(kit.capture(paymentId).status, 200)
  }

  Given("a payment captured whose wallet answer was lost in flight") { () =>
    paymentAwaiting()
    kit.wallet.loseAnswer(paymentId)
    last = kit.capture(paymentId)
  }
  When("the cashier asks the wallet again under the same payment id") { () =>
    kit.eventually("credited")(Option(kit.flow(paymentId)).filter(_.status == DepositFlow.Credited))
  }
  Then("the wallet returns the first outcome") { () => assert(kit.wallet.depositsOf(paymentId).size >= 2) }
  Then("the balance has risen once") { () => assertEquals(kit.wallet.balance(brand, player, "EUR"), 5000L) }

  Given("a payment the wallet refuses because the player holds no wallet in its currency") { () =>
    paymentAwaiting()
    kit.wallet.refuse(paymentId, "WALLET_NOT_FOUND", 404)
  }
  When("the refusal arrives") { () => last = kit.capture(paymentId) }
  Then("the cashier records the refusal and stops retrying") { () =>
    assertEquals(kit.flow(paymentId).status, DepositFlow.CreditRefused)
    assertEquals(kit.paymentState(paymentId).credit.flatMap(_.code), Some("WALLET_NOT_FOUND"))
    Thread.sleep(300)
    assertEquals(kit.wallet.depositsOf(paymentId).size, 1)
  }
  Then("the payment is listed among the captured deposits not credited, with the wallet's reason") { () =>
    val listed = kit.eventually("listed")(Option(kit.request("GET", "/v1/payments/cashier/uncredited", None, kit.asOperator()))
      .filter(_.text.contains(paymentId)))
    assert(listed.text.contains("WALLET_NOT_FOUND"), listed.text)
  }
  Then("an operator can retry it once the cause is fixed") { () =>
    kit.wallet.reset()
    assertEquals(kit.request("POST", s"/v1/payments/cashier/$paymentId/retry-credit", None, kit.asOperator()).status, 200)
    kit.eventually("credited")(Option(kit.flow(paymentId)).filter(_.status == DepositFlow.Credited))
  }

  Given("the captured deposits not credited") { () =>
    kit.rewire(kit.settings.copy(uncreditedBound = JDuration.ofMillis(500)))
  }
  When("a payment has been captured and unacknowledged for longer than the installation's bound") { () =>
    paymentAwaiting()
    kit.wallet.down = true
    kit.capture(paymentId): Unit
    Thread.sleep(800)
  }
  Then("the shared payments' status counts it") { () =>
    val r = kit.eventually("counted")(Option(kit.request("GET", "/status", None, kit.asService("local", "any")))
      .filter(r => r.status == 200 && r.json.get("uncreditedCaptures").asLong >= 1))
    assertEquals(r.str("bound"), "500ms")
  }

  // ── settlement variance (US7) ──────────────────────────────────────────────────────────────────

  Given("a payment of {string} minor units of {string} awaiting crypto") { (amount: String, _: String) =>
    kit.gateway.crypto = true
    paymentAwaiting(amount = amount.toLong)
  }
  Given("a payment awaiting crypto") { () =>
    kit.gateway.crypto = true
    paymentAwaiting(amount = 10000)
  }
  private def variance(kind: String, amount: Option[String], currency: Option[String]) =
    Json.render(Json.obj("outcome" -> "success", "variance" -> kind, "receivedCryptoAmount" -> "0.0012",
      "receivedConvertAmount" -> amount, "receivedConvertCurrency" -> currency))

  When("the gateway reports an underpayment valued at {string} {string}") { (amount: String, currency: String) =>
    body = variance("UNDERPAYMENT", Some(amount), Some(currency))
    last = kit.capture(paymentId, body)
  }
  Then("the wallet is credited {string}") { (amount: String) =>
    assertEquals(kit.wallet.depositsOf(paymentId).head.json.get.long("amount"), Some(amount.toLong))
  }
  Then("the payment records the variance, the amount credited and the invoiced {string}") { (invoice: String) =>
    val r = kit.read(paymentId)
    assertEquals((r.str("variance"), r.json.get("amountCents").asLong, r.json.get("originalAmountCents").asLong), ("UNDERPAYMENT", 8000L, invoice.toLong))
  }
  Then("the {string} outcome carries {string}") { (_: String, amount: String) =>
    assertEquals(kit.wallet.balance(brand, player, "EUR"), amount.toLong)
  }

  private def untrusted(fault: String) =
    fault match
      case "is \"0\""                 => variance("UNDERPAYMENT", Some("0"), Some("EUR"))
      case "exceeds \"100.00\""       => variance("UNDERPAYMENT", Some("100.01"), Some("EUR"))
      case "carries no currency"      => variance("UNDERPAYMENT", Some("80.00"), None)
      case _                          => variance("UNDERPAYMENT", Some("80.00"), Some("USD"))
  When("the gateway reports a capture whose valuation is {string}") { (v: String) => last = kit.capture(paymentId, untrusted(s"is \"$v\"")) }
  When("the gateway reports a capture whose valuation exceeds {string}") { (v: String) => last = kit.capture(paymentId, untrusted(s"exceeds \"$v\"")) }
  When("the gateway reports a capture whose valuation carries no currency") { () => last = kit.capture(paymentId, untrusted("carries no currency")) }
  When("the gateway reports a capture whose valuation carries another currency") { () => last = kit.capture(paymentId, untrusted("another")) }
  Then("the capture is refused") { () =>
    assertEquals(last.status, 503, last.text)
    assert(last.str("error").contains("valuation"), s"the original's answer names the reason: ${last.text}")
  }
  Then("nothing is credited") { () => assertEquals(kit.wallet.depositsOf(paymentId).size, 0) }
  Then("the gateway is not acknowledged, so that it keeps the outcome undelivered") { () =>
    assert(last.status / 100 != 2)
    assertEquals(kit.paymentState(paymentId).status, Status.AwaitingCrypto)
  }

  When("the gateway reports an overpayment") { () => last = kit.capture(paymentId, variance("OVERPAYMENT", Some("130.00"), Some("EUR"))) }
  Then("the invoiced amount is credited") { () => assertEquals(kit.wallet.depositsOf(paymentId).head.json.get.long("amount"), Some(10000L)) }
  Then("the surplus is recorded on the payment") { () =>
    val r = kit.read(paymentId)
    assertEquals((r.str("variance"), r.str("receivedConvertAmount")), ("OVERPAYMENT", "130.00"))
  }

  Given("a captured underpayment") { () =>
    kit.gateway.crypto = true
    paymentAwaiting(amount = 10000)
    body = variance("UNDERPAYMENT", Some("80.00"), Some("EUR"))
    assertEquals(kit.capture(paymentId, body).status, 200)
  }
  When("its capture is delivered again") { () => last = kit.capture(paymentId, body) }
  Then("the invoiced amount on record is not overwritten") { () =>
    assertEquals(kit.read(paymentId).json.get("originalAmountCents").asLong, 10000L)
  }

  // ── the provider's signature (FR-018) ──────────────────────────────────────────────────────────

  Given("a payment awaiting redirect at a payment provider whose credential holds a webhook secret") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"), webhookSecret = Some(Secret))
    paymentAwaiting()
  }
  Given("a payment awaiting redirect at a payment provider whose credential holds no webhook secret") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"))
    paymentAwaiting()
  }
  When("the gateway reports the capture signed under that secret and stamped with a time within 300 seconds") { () =>
    last = kit.capture(paymentId, body, kit.signed(body, Secret, age = 120))
  }
  When("the gateway reports the capture with no signature and no time stamped on it") { () => last = kit.capture(paymentId, body) }
  When("the gateway reports the capture with the signature and without the stamped time") { () =>
    last = kit.capture(paymentId, body, kit.signed(body, Secret).take(1))
  }
  When("the gateway reports the capture with the stamped time and without the signature") { () =>
    last = kit.capture(paymentId, body, kit.signed(body, Secret).drop(1))
  }
  Then("the capture is refused as missing its signature") { () => assertEquals((last.status, last.str("error")), (401, "missing_token")) }
  Then("the payment stays awaiting redirect") { () => assertEquals(kit.paymentState(paymentId).status, Status.AwaitingRedirect) }
  When("the gateway reports the capture stamped with a time older than 300 seconds") { () =>
    last = kit.capture(paymentId, body, kit.signed(body, Secret, age = 301))
  }
  When("the gateway reports the capture signed under another secret") { () => last = kit.capture(paymentId, body, kit.signed(body, "another")) }
  When("the gateway reports the capture signed over a body other than the one it carries") { () =>
    last = kit.capture(paymentId, body, kit.signed(body + " ", Secret))
  }
  Then("the capture is refused as not verified") { () => assertEquals((last.status, last.str("error")), (403, "invalid_token")) }
  Given("the secret store cannot be read") { () =>
    kit.subBrand(): Unit
    Secrets.outage = Some("the database cannot be reached")
  }
  When("the gateway reports the capture signed under that secret") { () => last = kit.capture(paymentId, body, kit.signed(body, Secret)) }
  Then("the capture is answered as unavailable for now") { () => assertEquals(last.status, 503, last.text) }
