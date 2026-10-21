package io.eitheror.casino.pspgateway.features

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.it.PspStub
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID

/**
 * features/payments/providers.feature (US1; the credential, leak and return scenarios are US5's and
 * US6's). A webhook scenario runs on Unity Finance: it answers every webhook `200`, as the feature
 * says a provider is answered, where DreamzPay answers a bad checksum `400` (research R9).
 */
class ProvidersFeatures extends GatewayFeatures("providers.feature"):

  private def fresh() = UUID.randomUUID().toString

  private def initiated(): Unit =
    provider = Provider.UnityFinance
    kit.psp.control(provider, "pending")
    paymentId = fresh()
    val r = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, r.text)

  private def webhook(outcome: String): Unit =
    val (status, body) = kit.psp.webhook(provider, kit.stubReference(paymentId, provider), "outcome" -> outcome)
    last = io.eitheror.casino.pspgateway.it.Reply(status, body.toString)

  /** Unity Finance's notification as a form, signed with what is not its key. */
  private def forged(reference: String): Unit =
    last = kit.request("POST", "/v1/webhooks/unityfinance", Some(s"ik_pm_no=$reference&ik_inv_st=success&ik_am=25.00&ik_cur=EUR&ik_sign=forged"),
      kit.asProvider, contentType = "application/x-www-form-urlencoded")

  private def nothingMoves(): Unit =
    Thread.sleep(500)
    if paymentId.nonEmpty then assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)
    assert(kit.merchant.delivered.isEmpty, kit.merchant.delivered)

  // ── initiate ───────────────────────────────────────────────────────────────────────────────────

  Given("a sub-brand with a credential for a payment provider") { () => provider = Provider.T365 }
  When("the cashier initiates a deposit naming that provider, an amount in minor units and a currency") { () =>
    paymentId = fresh()
    last = kit.initiate(paymentId, provider, "terminalId" -> "t-1")
  }
  Then("the gateway answers with the provider's reference and where to send the player") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.str("pspTransactionId"), paymentId.replace("-", ""))
    assert(last.str("redirectUrl").startsWith("http"), last.text)
  }
  Then("a provider session exists under that reference holding the sub-brand, where to deliver the outcome, and the terminal") { () =>
    val s = kit.deposit(paymentId)
    assertEquals(s.stage, Stage.Initiated)
    assertEquals(s.claim.map(c => (c.subBrandId, c.receiver(paymentId), c.terminalId)),
      Some(("sb-1", Receiver.Merchant(paymentId), Some("t-1"))))
  }

  private var calls = 0
  Given("a sub-brand with no credential for the payment provider named") { () =>
    provider = Provider.T365
    kit.merchant.removeCredential("sb-1", provider.wire)
    calls = kit.psp.received(provider).size
  }
  When("the cashier initiates a deposit naming that provider") { () =>
    paymentId = fresh()
    last = kit.initiate(paymentId, provider)
  }
  Then("it is refused naming the provider and the sub-brand") { () =>
    assertEquals((last.status, last.str("error")), (404, "no_credentials"), last.text)
    assert(last.str("message").contains("T365") && last.str("message").contains("sb-1"), last.text)
  }
  Then("no payment provider is called") { () => assertEquals(kit.psp.received(provider).size, calls) }

  // ── webhooks ───────────────────────────────────────────────────────────────────────────────────

  Given("an initiated provider session") { () => initiated() }
  When("the payment provider's webhook reports success and verifies") { () => webhook("success") }
  Then("the cashier is told the payment is captured, once") { () =>
    kit.eventually("captured at merchant")(Option(kit.merchant.deliveredTo(paymentId)).filter(_.nonEmpty))
    Thread.sleep(300)
    assertEquals(kit.merchant.deliveredTo(paymentId).map(_.key), Vector(s"$paymentId/capture"))
  }
  Then("the provider session records the outcome delivered") { () =>
    kit.eventually("recorded delivered")(Option(kit.deposit(paymentId).delivery).collect { case d: Delivery.Delivered => d })
  }

  When("the payment provider's webhook reports a failure") { () => webhook("failure") }
  Then("the cashier is told the payment failed with the provider's code and a reason a player can read") { () =>
    val d    = kit.eventually("failed at merchant")(kit.merchant.deliveredTo(paymentId).headOption)
    val body = Json.parse(d.body).get
    assertEquals(d.key, s"$paymentId/fail")
    assert(Json.text(body)("failureCode").nonEmpty && Json.text(body)("failureReason").exists(_.nonEmpty), d.body)
  }

  When("a webhook arrives whose signature does not verify") { () => forged(paymentId) }
  Then("it is refused to the payment provider as a bad request") { () => assertEquals(last.status, 400, last.text) }
  Then("it is logged") { () =>
    assert(logLines.exists(l => l.contains(paymentId) && l.contains("invalid signature")), logLines.mkString("\n"))
  }
  Then("nothing moves") { () => nothingMoves() }

  Given("no provider session under reference {string}") { (ref: String) =>
    assert(!kit.deposit(ref).exists, s"$ref has a session")
  }
  When("a webhook arrives for reference {string}") { (ref: String) => forged(ref) }
  Then("it is acknowledged") { () => assert(last.status / 100 == 2, s"${last.status} ${last.text}") }

  Given("a provider session already captured") { () =>
    initiated()
    webhook("success")
    kit.eventually("captured at merchant")(Option(kit.merchant.deliveredTo(paymentId)).filter(_.nonEmpty))
  }
  When("the same webhook arrives again") { () => webhook("success") }
  Then("the cashier is not told twice") { () =>
    Thread.sleep(500)
    assertEquals(kit.merchant.deliveredTo(paymentId).size, 1)
  }

  When("the payment provider's webhook reports a status that is not final") { () => webhook("pending") }
  Then("the provider session stays open") { () =>
    Thread.sleep(500)
    assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)
  }
  Then("the cashier is not told") { () => assert(kit.merchant.deliveredTo(paymentId).isEmpty) }

  // ── credentials (US5): read from the secret store on every call; nothing logged ──────────────────

  private var reads = 0
  private var sent  = Vector.empty[String]
  private var expected: (String, String) = ("", "")

  private def readsOf(p: Provider) = kit.merchant.credentialReads.count(_ == s"sb-1/${p.wire}")

  /** A deposit under a fresh payment id; what the provider received for it. */
  private def deposit(): Unit =
    val before = kit.psp.received(provider).size
    reads = readsOf(provider)
    paymentId = fresh()
    last = kit.initiate(paymentId, provider)
    assertEquals(last.status, 200, last.text)
    sent = kit.psp.received(provider).drop(before).map(_.toString)

  private def calledWith(value: String): Unit =
    assert(sent.exists(_.contains(value)), s"the provider was not sent $value:\n${sent.mkString("\n")}")
    assert(readsOf(provider) > reads, "the credential was not read for this deposit")

  Given("a credential saved for a sub-brand and a payment provider") { () =>
    provider = Provider.T365
    kit.credential("sb-1", provider, PspStub.credentials(provider).updated("apiKey", "saved-t365-key"))
    expected = "apiKey" -> "saved-t365-key"
  }
  When("a deposit is initiated for them") { () => deposit() }
  Then("the payment provider is called with that credential, read from the secret store at that moment") { () => calledWith(expected._2) }

  private var service: AnyRef = null
  Given("a credential rotated for a sub-brand and a payment provider") { () =>
    provider = Provider.T365
    service = kit.testKit.service
    deposit()
    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider).updated("apiKey", "t365-rotated-value"))
  }
  When("the next deposit is initiated for them") { () => deposit() }
  Then("the payment provider is called with the new values") { () =>
    calledWith("t365-rotated-value")
    assert(!sent.exists(_.contains(PspStub.credentials(provider)("apiKey") + "\"")), "the old key was sent")
  }
  Then("no restart happened") { () => assert(kit.testKit.service eq service, "the service was restarted") }

  Given("a payment provider whose requests are signed with a private signing key") { () =>
    provider = Provider.BounceMoney
    deposit()
    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider) ++
      Map("keyId" -> "rotated-signing-key", "privateKey" -> PspStub.freshEd25519Pem()))
  }
  When("a request to it is signed") { () => deposit() }
  Then("the signing key is read and parsed at request time, never cached across rotations") { () =>
    calledWith("rotated-signing-key")
    kit.merchant.rotate("sb-1", provider.wire, PspStub.credentials(provider).updated("privateKey", "not a key"))
    val before = kit.psp.received(provider).size
    val r      = kit.initiate(fresh(), provider)
    assertEquals((r.status, r.str("error")), (502, "provider_unavailable"), r.text)
    assertEquals(kit.psp.received(provider).size, before, "a key parsed earlier was used")
  }

  Given("any request to a payment provider or any outcome from one") { () =>
    provider = Provider.T365
    kit.psp.control(provider, "pending")
    deposit()
    webhook("success")
    kit.eventually(s"$paymentId delivered")(Option(kit.merchant.deliveredTo(paymentId)).filter(_.nonEmpty)): Unit
  }
  When("it is logged or traced") { () => Thread.sleep(300) }
  Then("no credential value appears") { () =>
    val values = PspStub.credentials(provider).values.filter(_.length >= 6)
    val hits   = logLines.filter(l => values.exists(l.contains))
    assert(logLines.nonEmpty, "nothing was logged: the check would pass on silence")
    assert(hits.isEmpty, hits.mkString("\n"))
  }
  Then("no personal data of a player appears") { () =>
    val fields = Vector("player@example.com", "Lovelace", "203.0.113.7", "+15550100", "1 King St", "M5H 1A1", "Toronto")
    val hits   = logLines.filter(l => fields.exists(l.contains))
    assert(hits.isEmpty, hits.mkString("\n"))
  }

  // ── the player's browser back from the provider (US6) ───────────────────────────────────────────

  Given("a provider session") { () => initiated() }
  When("the player's browser returns from the payment provider") { () =>
    last = kit.request("GET", s"/v1/psp/checkout/$paymentId/return?ik_inv_st=success", None, kit.asBrowser)
  }
  When("a player's browser returns from the payment provider naming reference {string}") { (ref: String) =>
    last = kit.request("GET", s"/v1/psp/checkout/unityfinance/$ref/return", None, kit.asBrowser)
  }
  Then("the player is shown that the payment is being processed") { () =>
    assertEquals(last.status, 200, last.text)
    assert(last.contentType.startsWith("text/html") && last.text.contains("Your payment is being processed."), last.text)
  }
  Then("no outcome is delivered") { () => nothingMoves() }
  Then("nothing else happens") { () =>
    nothingMoves()
    assert(!kit.deposit("unknown-ref").exists, "a session was made for an unknown reference")
  }
