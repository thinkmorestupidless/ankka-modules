package io.eitheror.casino.merchant.features

import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.it.{Kit, Ready}
import io.eitheror.casino.merchant.payments.Status
import io.eitheror.casino.merchant.routing.*

/**
 * features/payments/cashier.feature, the scenarios spec 004 names (US1, US2: FR-013 to FR-017). The
 * rest are `ingest`'s (spec 006): the merchant key, request ids, rate limits and reads by sub-brand.
 */
class CashierFeatures extends MerchantFeatures("cashier.feature"):

  import CashierFeatures.Ingest
  override protected def ranElsewhere: Map[String, String] = Vector(
    "a routing refusal is recorded against the sub-brand",
    "a request with no cashier key is refused as unauthenticated and no session exists",
    "a request under a key no sub-brand holds active is refused without saying why",
    "a cashier key the sub-brand disabled is refused within a minute",
    "a refusal of a cashier key or for rate is an audit record naming the sub-brand where known and never the key",
    "a body larger than the installation's bound or not readable is refused before the key is checked and recorded under no sub-brand",
    "a create the cashier did not answer in time is told to try again, and a repeat under its request id is forwarded again",
    "a request missing or malforming a field is refused naming the field before the cashier is asked",
    "a repeated create under the same request id with the same content returns the first session and asks the cashier nothing",
    "a repeated create under the same request id with a different amount is refused naming the request id",
    "a request id belongs to the sub-brand and not to the installation",
    "a create with no request id is served under one the shared payments mints, and the same content again makes a second session",
    "a payment read by the sub-brand that created it returns its state",
    "a payment read by another sub-brand is refused as not found",
    "a sub-brand above the rate limit has the excess refused as too many with the time to wait, and the cashier receives only what was served",
    "one sub-brand refused as too many leaves another sub-brand served in the same minute",
    "a sub-brand whose rate limit an operator raised is served in full"
  ).map(_ -> Ingest).toMap

  private var first = ""

  private def tree(terminal: String, under: Option[Long] = None, currency: Option[String] = None) =
    val conditions = under.map(a => Condition("AMOUNT", "LT", a.toString)).toVector ++ currency.map(c => Condition("CURRENCY", "EQ", c)).toVector
    Tree("root", Map("root" -> Node("root", Some(Vector(Branch(conditions, "leaf"))), None), "leaf" -> Node("leaf", None, Some(Vector(terminal)))))

  // ── a session (FR-013, FR-014) ─────────────────────────────────────────────────────────────────

  Given("a sub-brand with an active cashier key and a deployed routing tree whose root offers terminal {string} for amounts under {string} minor units in {string}") {
    (terminal: String, under: String, currency: String) =>
      ready = kit.subBrand(brand = Some("goldvibe"), credentialName = terminal, tree = Some(tree(terminal, Some(under.toLong), Some(currency))))
  }
  When("the sub-brand creates a cashier session for {string} minor units of {string} for player {string} in {string} under its key") {
    (amount: String, currency: String, player: String, _: String) =>
      last = kit.session(ready, amount.toLong, currency, Some(player))
  }
  Then("it is answered with a cashier ticket and {string} as the one available terminal") { (terminal: String) =>
    assertEquals(last.status, 200, last.text)
    assert(last.str("session_token").nonEmpty)
    val offered = last.json.get("available_terminals")
    assertEquals((offered.size, offered.get(0).textOr("terminalEntityId", "")), (1, terminal))
    assertEquals(last.json.get("resolvedFrom").textOr("level", ""), "subBrand")
  }

  Given("a cashier session for {string} minor units of {string} offering terminal {string}") { (amount: String, currency: String, terminal: String) =>
    ready = kit.subBrand(brand = Some("goldvibe"), credentialName = terminal)
    last = kit.session(ready, amount.toLong, currency)
    token = last.str("session_token")
  }
  When("the sub-brand submits it naming terminal {string}") { (terminal: String) => last = kit.submit(token, terminal) }
  Then("the gateway is asked to initiate a hosted page payment with the payment provider") { () =>
    assertEquals(kit.gateway.initiates.size, 1)
    assertEquals(kit.gateway.initiates.head.json.get.textOr("psp", ""), "DREAMZPAY")
  }
  Then("the payment is recorded as awaiting redirect with a deadline {string} minutes away") { (minutes: String) =>
    paymentId = last.str("paymentId")
    val o = kit.paymentState(paymentId).opened.get
    assertEquals(kit.paymentState(paymentId).status, Status.AwaitingRedirect)
    assertEquals(java.time.Duration.between(o.createdAt, o.deadline).toMinutes, minutes.toLong)
  }
  Then("it is answered with the payment id and the status, and not the redirect address") { () =>
    assertEquals((last.status, last.str("status")), (200, Status.AwaitingRedirect))
    assert(last.str("paymentId").nonEmpty)
    assertEquals(last.str("redirectUrl"), "", last.text)
  }

  // ── the read (FR-017) ──────────────────────────────────────────────────────────────────────────

  Given("a payment awaiting redirect") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"))
    paymentAwaiting()
  }
  When("the player's frontend reads the payment") { () => last = kit.read(paymentId) }
  Then("it is told the status as {string}, {string}, {string} or the awaiting status, the amount as a decimal and in minor units, the currency, the payment provider, the scheme, the terminal and the redirect address") {
    (_: String, _: String, _: String) =>
      assertEquals(last.status, 200, last.text)
      val j = last.json
      assertEquals((j.textOr("status", ""), j.textOr("amount", ""), j.long("amountCents"), j.textOr("currency", "")),
        (Status.AwaitingRedirect, "50.00", Some(5000L), "EUR"))
      assertEquals((j.textOr("psp", ""), j.textOr("scheme", ""), j.textOr("terminalId", ""), j.textOr("redirectUrl", "")),
        ("DREAMZPAY", "VISA", "main", s"https://gateway.example/pay/$paymentId"))
  }

  // ── routing refusals (FR-013) ──────────────────────────────────────────────────────────────────

  private var extra: Seq[(String, Any)] = Nil
  Given("a request naming no country or no player id") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"))
    extra = Seq("country" -> "")
  }
  Given("a sub-brand with no routing tree deployed at any level") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"), deploy = false)
    extra = Nil
  }
  Given("a sub-brand whose routing tree has no branch that matches") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"), tree = Some(tree("main", currency = Some("JPY"))))
    extra = Nil
  }
  When("the sub-brand creates a cashier session") { () => last = kit.session(ready, extra = extra) }
  Then("it is refused as {string}") { (reason: String) =>
    val code = Map("no routing context" -> "MISSING_ROUTING_CONTEXT").getOrElse(reason, reason.toUpperCase.replace(' ', '_'))
    assertEquals((last.status, last.str("error")), (400, code), last.text)
  }

  // ── the submit's guards (FR-015) ───────────────────────────────────────────────────────────────

  Given("a cashier session already submitted") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"))
    token = kit.session(ready).str("session_token")
    first = kit.submit(token).text
  }
  When("the sub-brand submits it again") { () => last = kit.submit(token) }
  Then("the first submit's answer is returned") { () =>
    assertEquals(last.status, 200)
    assertEquals(last.json, io.eitheror.casino.merchant.Json.parse(first).get)
  }
  Then("no second payment exists") { () => assertEquals(kit.gateway.initiates.size, 1) }

  Given("a cashier session offering terminal {string} only") { (terminal: String) =>
    ready = kit.subBrand(brand = Some("goldvibe"), credentialName = terminal)
    token = kit.session(ready).str("session_token")
  }
  Then("it is refused naming {string}") { (terminal: String) =>
    assertEquals((last.status, last.str("error")), (400, "terminal_not_found"))
    assert(last.text.contains(terminal), last.text)
  }

  Given("a cashier session whose submit the payment provider refuses for a reason the player can act on") { () =>
    ready = kit.subBrand(brand = Some("goldvibe"))
    token = kit.session(ready).str("session_token")
    kit.gateway.refusePlayer = Some("The minimum deposit is 10.00 EUR")
  }
  When("the answer comes back") { () => last = kit.submit(token) }
  Then("the sub-brand is told the submit was rejected, with the payment provider's words, so that the cashier page shows them") { () =>
    assertEquals((last.status, last.str("error"), last.str("message")), (400, "PAYMENT_REJECTED", "The minimum deposit is 10.00 EUR"))
  }

  Given("a cashier ticket no session has") { () => token = "no-such-ticket" }
  When("it is submitted") { () => last = kit.submit(token) }
  Then("it is refused as not found") { () => assertEquals((last.status, last.str("error")), (404, "SESSION_NOT_FOUND")) }

object CashierFeatures:
  // Here, not in the class: GherkinSuite reads ranElsewhere while it is constructed, before the class's own vals.
  private val Ingest = "spec 006's ingest suite"
