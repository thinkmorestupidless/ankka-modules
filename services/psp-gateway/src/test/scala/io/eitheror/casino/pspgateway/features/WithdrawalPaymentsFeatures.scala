package io.eitheror.casino.pspgateway.features

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.it.Reply
import io.eitheror.casino.pspgateway.sessions.Provider

import java.util.UUID

/**
 * features/payments/withdrawal-payments.feature (US3). The brand is `FakePayouts`; Unity Finance is the
 * provider, since it has every outcome and a status to read. A request id the feature names is this
 * scenario's own, since the database outlives scenarios.
 */
class WithdrawalPaymentsFeatures extends GatewayFeatures("withdrawal-payments.feature"):

  private val brand   = "goldvibe"
  private var ids     = Map.empty[String, String]
  private var first: Reply = null
  private var calls   = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    ids = Map.empty; first = null; calls = 0
    provider = Provider.UnityFinance
    kit.payoutTerminals(brand)

  private def id(named: String) =
    ids.getOrElse(named, { val fresh = s"$named-${UUID.randomUUID()}"; ids += named -> fresh; fresh })

  private def initiate(named: String): Reply =
    calls = kit.psp.received(provider).size
    kit.withdraw(brand, id(named), provider)

  private def told(requestId: String) =
    kit.eventually(s"$requestId at the brand")(kit.payouts.delivered(brand).find(_.key.startsWith(s"$requestId/")))

  Given("a brand that allows the gateway to pay its withdrawals") { () => kit.psp.control(provider, "pending") }
  When("the brand initiates a withdrawal payment with request id {string}, an amount, a currency and the method's details") { (w: String) =>
    last = initiate(w)
  }
  Then("the payment provider is called once under {string}") { (w: String) =>
    val made = kit.psp.received(provider).drop(calls)
    assertEquals(made.size, 1, made.mkString("\n"))
    assert(made.head.toString.contains(id(w)), made.head.toString)
  }
  Then("the answer carries the provider's reference") { () =>
    assertEquals((last.status, last.str("status")), (200, "initiated"), last.text)
    assert(last.str("providerReference").nonEmpty, last.text)
  }

  Given("a withdrawal payment initiated under request id {string}") { (w: String) =>
    kit.psp.control(provider, "pending")
    first = initiate(w)
    assertEquals(first.status, 200, first.text)
  }
  When("the brand initiates it under {string} a second time") { (w: String) => last = initiate(w) }
  Then("the payment provider is not called again") { () => assertEquals(kit.psp.received(provider).size, calls) }
  Then("the first answer is returned") { () => assertEquals(Json.parse(last.text), Json.parse(first.text)) }

  private val stubOutcome = Map("completed" -> "success", "failed" -> "failure", "reversed" -> "reversed")
  When("the payment provider's webhook reports it completed") { () => report("completed") }
  When("the payment provider's webhook reports it failed") { () => report("failed") }
  When("the payment provider's webhook reports it reversed") { () => report("reversed") }

  private def report(outcome: String): Unit =
    val requestId = id("w1")
    if outcome == "reversed" then
      kit.psp.webhook(provider, requestId, "outcome" -> "success"): Unit
      told(requestId): Unit
    kit.psp.webhook(provider, requestId, "outcome" -> stubOutcome(outcome)): Unit

  Then("the brand is told the withdrawal payment is completed, with the provider's reference, once") { () => toldOnce("completed") }
  Then("the brand is told the withdrawal payment is failed, with the provider's reference, once") { () => toldOnce("failed") }
  Then("the brand is told the withdrawal payment is reversed, with the provider's reference, once") { () => toldOnce("reversed") }

  private def toldOnce(outcome: String): Unit =
    val requestId = id("w1")
    val d = kit.eventually(s"$requestId $outcome")(kit.payouts.delivered(brand).find(_.key == s"$requestId/$outcome"))
    assert(Json.parse(d.body).flatMap(Json.text(_)("providerReference")).exists(_.nonEmpty), d.body)
    Thread.sleep(300)
    assertEquals(kit.payouts.delivered(brand).count(_.key == s"$requestId/$outcome"), 1)

  Given("a withdrawal payment initiated under request id {string} and no webhook for it") { (w: String) =>
    kit.psp.control(provider, "pending")
    first = initiate(w)
    assertEquals(first.status, 200, first.text)
  }
  When("the brand asks for its status") { () =>
    calls = kit.psp.received(provider).size
    last = kit.withdrawal(brand, id("w1"))
  }
  Then("the gateway asks the payment provider") { () => assert(kit.psp.received(provider).size > calls, "no status read was made") }
  Then("it answers transient, completed, failed, reversed, or that the provider has no record of it") { () =>
    assertEquals(last.status, 200, last.text)
    assert(Set("pending", "completed", "failed", "reversed", "no_record").contains(last.str("status")), last.text)
  }

  Given("a payment provider that refuses a withdrawal payment as a duplicate reference") { () => kit.psp.control(provider, "duplicate") }
  When("the brand initiates it") { () => last = initiate("dup") }
  Then("the brand is told it was already initiated, the provider holding it") { () =>
    assertEquals((last.status, last.str("status")), (200, "already_initiated"), last.text)
  }

  Given("a withdrawal payment initiated with an empty credential") { () =>
    kit.credential("sb-1", provider, Map("unused" -> ""))
  }
  When("the payment provider refuses it") { () => last = initiate("empty") }
  Then("the refusal is reported as a credential failure") { () =>
    assertEquals((last.str("status"), last.str("reason")), ("rejected", "missing_credentials"), last.text)
  }
  Then("never as the provider having no record of it") { () =>
    assertNotEquals(last.str("status"), "no_record")
    val status = kit.withdrawal(brand, id("empty"))
    assertNotEquals(status.str("status"), "no_record", status.text)
  }
