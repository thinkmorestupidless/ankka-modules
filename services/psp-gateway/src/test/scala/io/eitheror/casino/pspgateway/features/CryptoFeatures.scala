package io.eitheror.casino.pspgateway.features

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.it.Delivered
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID

/**
 * features/payments/crypto.feature (US2). Cozeni is the crypto provider: it reports over- and
 * underpayments and values a short one; the unsigned-webhook scenario is Morphosis's.
 */
class CryptoFeatures extends GatewayFeatures("crypto.feature"):

  private def delivered: Delivered = kit.eventually(s"$paymentId delivered")(kit.merchant.deliveredTo(paymentId).headOption)
  private def field(name: String): Option[String] = Json.parse(delivered.body).flatMap(Json.text(_)(name))

  private def quote(p: Provider): Unit =
    provider = p
    kit.psp.control(p, "pending")
    paymentId = UUID.randomUUID().toString
    last = kit.initiate(paymentId, p)
    assertEquals(last.status, 200, last.text)

  private def confirm(fields: (String, Any)*): Unit =
    kit.psp.webhook(provider, kit.stubReference(paymentId, provider), fields*): Unit

  Given("a sub-brand with a credential for a crypto payment provider") { () => provider = Provider.Cozeni }
  When("the cashier initiates a deposit by a crypto method") { () => quote(provider) }
  Then("the answer carries a payment address, a coin amount, a coin currency and a deadline") { () =>
    for f <- Seq("paymentAddress", "cryptoAmount", "cryptoCurrency", "validUntil") do assert(last.str(f).nonEmpty, s"$f: ${last.text}")
  }
  Then("no redirect") { () => assertEquals(last.str("redirectUrl"), "") }

  Given("a quoted crypto deposit") { () => quote(Provider.Cozeni) }

  When("the payment provider confirms a coin amount equal to or above the quote") { () => confirm("outcome" -> "overpayment") }
  Then("the cashier is told the payment is captured") { () => assertEquals(delivered.key, s"$paymentId/capture") }
  Then("an overpayment is recorded as a variance on the outcome") { () =>
    assertEquals(field("variance"), Some(Variance.Overpayment))
    assert(kit.deposit(paymentId).outcome.exists {
      case Outcome.Captured(Some(v)) => v.kind == Variance.Overpayment
      case _                         => false
    })
  }

  When("the payment provider confirms a coin amount below the quote and gives no value for it in the deposit's currency") { () =>
    confirm("outcome" -> "underpayment", "convertAmount" -> "0")
  }
  Then("the cashier is told the payment failed with an underpayment variance and the amounts quoted and received") { () =>
    assertEquals(delivered.key, s"$paymentId/fail")
    assert(field("receivedCryptoAmount").nonEmpty, delivered.body)
    assert(kit.deposit(paymentId).outcome.exists {
      case Outcome.Failed(_, _, Some(v)) => v.kind == Variance.Underpayment && v.quoted.nonEmpty
      case _                             => false
    }, kit.deposit(paymentId).outcome.toString)
  }
  Then("nothing is credited") { () => assertEquals(delivered.key, s"$paymentId/fail") }

  When("the payment provider confirms a coin amount below the quote and values it in the deposit's currency") { () =>
    confirm("outcome" -> "underpayment", "convertAmount" -> "20.00")
  }
  Then("the cashier is told the payment is captured with an underpayment variance carrying the amounts received and their value") { () =>
    assertEquals(delivered.key, s"$paymentId/capture")
    assertEquals(field("variance"), Some(Variance.Underpayment))
    assertEquals(field("receivedConvertAmount"), Some("20.00"))
    assert(field("receivedCryptoAmount").nonEmpty && field("receivedConvertCurrency").nonEmpty, delivered.body)
  }
  Then("the deposit is not credited in full") { () =>
    // merchant credits a valued underpayment at its value (004 Variance.credited): the variance is what says so.
    assertEquals(field("variance"), Some(Variance.Underpayment))
  }

  When("the payment provider confirms with a received amount that is missing or unreadable") { () =>
    quote(Provider.Morphosis)
    val qvei = kit.deposit(paymentId).initiation.flatMap(_.reference).get
    last = kit.request("POST", s"/v1/webhooks/morphosis/deposit/${kit.stubReference(paymentId, provider)}",
      Some(Json.render(Json.obj("id" -> qvei, "status" -> "ac", "coin_amount" -> "not-a-number"))), kit.asProvider)
  }
  Then("it is treated as short") { () =>
    assertEquals(delivered.key, s"$paymentId/fail")
    assert(kit.deposit(paymentId).outcome.exists {
      case Outcome.Failed(_, _, Some(v)) => v.kind == Variance.Underpayment
      case _                             => false
    })
  }

  private var other = ""
  Given("a crypto payment provider that does not sign its webhooks") { () =>
    quote(Provider.Morphosis)
    other = paymentId
    quote(Provider.Morphosis)
  }
  When("its webhook arrives") { () =>
    val qvei = kit.deposit(paymentId).initiation.flatMap(_.reference).get
    val body = Some(Json.render(Json.obj("id" -> qvei, "status" -> "ac", "coin_amount" -> "1000000")))
    last = kit.request("POST", s"/v1/webhooks/morphosis/deposit/${kit.stubReference(other, provider)}", body, kit.asProvider)
    assertEquals(last.status, 200, last.text)
    Thread.sleep(500)
    assertEquals((kit.deposit(paymentId).stage, kit.deposit(other).stage), (Stage.Initiated, Stage.Initiated), "the other session's token admits nothing")
    last = kit.request("POST", s"/v1/webhooks/morphosis/deposit/${kit.stubReference(paymentId, provider)}", body, kit.asProvider)
  }
  Then("it is admitted only on the secret for that provider session carried in its address") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(delivered.key, s"$paymentId/capture")
    assert(kit.merchant.deliveredTo(other).isEmpty)
  }

