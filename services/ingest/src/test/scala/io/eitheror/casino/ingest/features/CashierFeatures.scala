package io.eitheror.casino.ingest.features

import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.Json.*
import io.eitheror.casino.ingest.it.{Answer, FakeMerchant, RawKey}
import io.eitheror.casino.ingest.requests.{LostAnswer, Outcome}
import io.eitheror.casino.ingest.shape.Digests

import java.time.Duration
import scala.concurrent.duration.*

/**
 * features/payments/cashier.feature through the edge (spec 006: US1 to US4). The scenarios about the
 * cashier's own decisions are bound here at the edge too, `FakeMerchant` answering as `merchant` does; the
 * two that observe only `merchant`'s inside (the gateway asked, the deadline set) are bound in merchant's suite.
 */
class CashierFeatures extends IngestFeatures("cashier.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "submitting a session naming an offered terminal initiates the payment and answers the payment id and the status" -> CashierFeatures.Merchant
  )

  private var ticket    = ""
  private var paymentId = ""
  private var first: io.eitheror.casino.ingest.it.Reply = null
  private var other: RawKey = null
  private var extra: Seq[(String, Any)] = Nil
  private val subBrands = scala.collection.mutable.Map.empty[String, RawKey]

  private def creates = kit.merchant.received(FakeMerchant.Create).size
  private def session(): Unit =
    key = kit.issueKey()
    first = kit.create(key, "transactionId" -> id("t"))
    assertEquals(first.status, 200, first.text)
    ticket = first.str("session_token")

  // ── a session (US1) ────────────────────────────────────────────────────────────────────────────

  Given("a sub-brand with an active cashier key and a deployed routing tree whose root offers terminal {string} for amounts under {string} minor units in {string}") {
    (terminal: String, _: String, _: String) =>
      key = kit.issueKey()
      kit.merchant.terminals = Vector(terminal)
  }
  When("the sub-brand creates a cashier session for {string} minor units of {string} for player {string} in {string} under its key") {
    (amount: String, currency: String, player: String, country: String) =>
      last = kit.create(key, "amountCents" -> amount.toLong, "currency" -> currency, "customerId" -> player, "country" -> country)
  }
  Then("it is answered with a cashier ticket and {string} as the one available terminal") { (terminal: String) =>
    assertEquals(last.status, 200, last.text)
    assert(last.str("session_token").nonEmpty)
    val offered = last.json.get("available_terminals")
    assertEquals((offered.size, Json.text(offered.get(0))("terminalEntityId")), (1, Some(terminal)))
  }

  // ── the read ───────────────────────────────────────────────────────────────────────────────────

  Given("a payment awaiting redirect") { () =>
    session()
    paymentId = kit.submit(ticket).str("paymentId")
  }
  When("the player's frontend reads the payment") { () => last = kit.readPayment(paymentId, ticket = Some(ticket)) }
  Then("it is told the status as {string}, {string}, {string} or the awaiting status, the amount as a decimal and in minor units, the currency, the payment provider, the scheme, the terminal and the redirect address") {
    (_: String, _: String, _: String) =>
      assertEquals(last.status, 200, last.text)
      val j = last.json
      assertEquals((Json.text(j)("status"), Json.text(j)("amount"), Json.long(j)("amountCents"), Json.text(j)("currency")),
        (Some("AWAITING_REDIRECT"), Some("50.00"), Some(5000L), Some("EUR")))
      Seq("psp", "scheme", "terminalId", "redirectUrl").foreach(f => assert(Json.text(j)(f).exists(_.nonEmpty), s"$f: ${last.text}"))
  }

  // ── routing refusals ───────────────────────────────────────────────────────────────────────────

  Given("a sub-brand with no routing tree deployed at any level") { () =>
    key = kit.issueKey(); kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_TREE")
  }
  Given("a sub-brand whose routing tree has no branch that matches") { () =>
    key = kit.issueKey(); kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_MATCH")
  }
  When("the sub-brand creates a cashier session") { () => last = kit.create(key, "transactionId" -> id("t")) }
  Then("it is refused as {string}") { (reason: String) =>
    reason match
      case "invalid cashier key" => assertEquals((last.status, last.str("error")), (401, "INVALID_MERCHANT_KEY"), last.text)
      case _ => assertEquals((last.status, last.str("error")), (400, reason.toUpperCase.replace(' ', '_')), last.text)
  }

  Given("a sub-brand whose routing tree matches nothing for the player's country") { () =>
    key = kit.issueKey(); kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_MATCH")
  }
  When("the sub-brand creates a cashier session for that player") { () => last = kit.create(key, "transactionId" -> id("t")) }
  Then("it is refused naming the routing reason") { () => assertEquals((last.status, last.str("error")), (400, "NO_ROUTING_MATCH")) }
  Then("the refusal is recorded against the sub-brand") { () =>
    val r = kit.recordWhere(key.subBrand)(_.requestId.contains(id("t")))
    assertEquals((r.outcome, r.refusal), (Outcome.Refused, Some("NO_ROUTING_MATCH")))
  }

  // ── the key ────────────────────────────────────────────────────────────────────────────────────

  Given("a request presenting no cashier key") { () => key = null }
  When("it creates a cashier session") { () => last = kit.create(Option(key), kit.body()) }
  Then("it is refused as unauthenticated") { () => assertEquals((last.status, last.str("error")), (401, "UNAUTHENTICATED")) }
  Then("no cashier session exists") { () => assertEquals(creates, 0) }

  Given("a request presenting a cashier key no sub-brand holds") { () => key = RawKey(s"mk_${id("nobody")}", "k", "sb-none") }
  Given("a request presenting a cashier key a sub-brand disabled") { () =>
    key = kit.issueKey(); kit.deactivateKey(key)
  }
  Then("the refusal does not say which of the two it was") { () =>
    val unknown = kit.create(Some(RawKey(s"mk_${id("compare")}", "k", "sb-none")), kit.body())
    assertEquals((last.status, last.text), (unknown.status, unknown.text))
  }

  Given("a sub-brand whose active cashier key opened a cashier session") { () => session() }
  When("the sub-brand disables that key") { () => kit.deactivateKey(key, await = false) }
  Then("a create under that key is refused as {string} once {string} seconds have passed") { (_: String, seconds: String) =>
    kit.eventually("the key refused", seconds.toInt.seconds)(Some(kit.create(key)).filter(_.status == 401)): Unit
  }

  // ── audit ──────────────────────────────────────────────────────────────────────────────────────

  Given("a create under a cashier key no sub-brand holds and a sub-brand refused as too many") { () =>
    kit.create(Some(RawKey(s"mk_${id("audit")}", "k", "sb-none")), kit.body()): Unit
    key = kit.issueKey()
    kit.request("PUT", s"/v1/ingest/sub-brands/${key.subBrand}/rate", Some("""{"perMinute":1}"""), kit.asOperator()): Unit
    kit.create(key): Unit
    assertEquals(kit.create(key).status, 429)
  }
  When("the shared audit is read") { () => () }
  Then("each refusal is an audit record naming the sub-brand where known") { () =>
    kit.eventually("the rate refusal audited")(kit.audit().find(a => a.actorId == key.subBrand && a.reasonCode.contains(Outcome.Rate))): Unit
    kit.eventually("the unknown key audited")(kit.audit().find(a => a.actorKind == "unknown" && a.reasonCode.contains(Outcome.KeyUnknown))): Unit
  }
  Then("no audit record holds a cashier key") { () =>
    val raw = s"mk_${id("audit")}"
    assert(!kit.auditTexts().exists(t => t.contains(raw) || t.contains(Digests.ofKey(raw))))
  }

  // ── the body bound ─────────────────────────────────────────────────────────────────────────────

  Given("a request presenting an active cashier key") { () => key = kit.issueKey() }
  When("it creates a cashier session with a body larger than the installation's bound") { () =>
    kit.deactivateKey(key) // were the key checked, the answer would be 401
    last = kit.create(Some(key), kit.body("customerAddress" -> "x" * kit.settings.bodyBound))
  }
  Then("it is refused as larger than the bound") { () => assertEquals((last.status, last.str("error")), (413, "body_too_large")) }
  Then("the key is not checked") { () => assertNotEquals(last.status, 401) }
  Then("the refusal is recorded under no sub-brand") { () =>
    kit.eventually("the record")(kit.unbranded().find(_.text("outcome").contains(Outcome.BodyTooLarge))): Unit
    assert(!kit.records(key.subBrand).exists(_.outcome == Outcome.BodyTooLarge))
  }

  // ── fields ─────────────────────────────────────────────────────────────────────────────────────

  private val fieldRows: Map[String, (String, Any)] = Map(
    "an amount that is not a whole number of minor units" -> ("amountCents" -> 50.5),
    "no amount"                          -> ("amountCents" -> None),
    "no currency"                        -> ("currency" -> None),
    "no player id"                       -> ("customerId" -> None),
    "a player id without the brand part" -> ("customerId" -> "p1"),
    "no country"                         -> ("country" -> None),
    "no return address"                  -> ("redirectUrl" -> None)
  )
  private var field = ""

  Given("a sub-brand with an active cashier key") { () => key = kit.issueKey() }
  When("it creates a cashier session with an amount that is not a whole number of minor units") { () => sendRow("an amount that is not a whole number of minor units") }
  When("it creates a cashier session with no amount") { () => sendRow("no amount") }
  When("it creates a cashier session with no currency") { () => sendRow("no currency") }
  When("it creates a cashier session with no player id") { () => sendRow("no player id") }
  When("it creates a cashier session with a player id without the brand part") { () => sendRow("a player id without the brand part") }
  When("it creates a cashier session with no country") { () => sendRow("no country") }
  When("it creates a cashier session with no return address") { () => sendRow("no return address") }
  private def sendRow(row: String): Unit =
    val change = fieldRows(row)
    field = change._1
    last = kit.create(key, change)
  Then("it is refused naming the field") { () => assertEquals((last.status, last.str("error"), last.str("field")), (400, "INVALID_REQUEST", field)) }
  Then("the cashier is not asked") { () => assertEquals(creates, 0) }

  // ── request ids (US2) ──────────────────────────────────────────────────────────────────────────

  Given("a cashier session created under request id {string}") { (t: String) =>
    key = kit.issueKey()
    first = kit.create(key, "transactionId" -> id(t))
    assertEquals(first.status, 200)
  }
  When("the same sub-brand creates one under request id {string} again with the same content") { (t: String) =>
    last = kit.create(key, "transactionId" -> id(t))
  }
  Then("the first session's cashier ticket and terminals are returned") { () => assertEquals(last.text, first.text) }
  Then("the cashier is not asked again") { () => assertEquals(creates, 1) }

  When("the same sub-brand creates one under request id {string} with a different amount") { (t: String) =>
    last = kit.create(key, "transactionId" -> id(t), "amountCents" -> 6000L)
  }
  Then("it is refused as a request id already used with a different request, naming {string}") { (t: String) =>
    assertEquals((last.status, last.str("error"), last.str("transactionId")), (409, "REQUEST_ID_REUSED", id(t)))
  }

  Given("two sub-brands") { () => key = kit.issueKey(); other = kit.issueKey() }
  When("each creates a cashier session under request id {string}") { (t: String) =>
    replies = Vector(kit.create(key, "transactionId" -> id(t)), kit.create(other, "transactionId" -> id(t)))
  }
  Then("each has its own session") { () =>
    assertEquals(replies.map(_.status), Vector(200, 200))
    assertNotEquals(replies(0).str("session_token"), replies(1).str("session_token"))
  }

  When("it creates a cashier session with no request id") { () => last = kit.create(key, "transactionId" -> None) }
  Then("it is served under a request id the shared payments mints and returns") { () =>
    assertEquals(last.status, 200)
    assert(last.str("transactionId").nonEmpty)
  }
  Then("the same content sent again creates a second session") { () =>
    val again = kit.create(key, "transactionId" -> None)
    assertNotEquals(again.str("session_token"), last.str("session_token"))
    assertEquals(creates, 2)
  }

  Given("a create under request id {string} the cashier did not answer in time") { (t: String) =>
    key = kit.issueKey()
    kit.merchant.hold(id(t))
    assertEquals(kit.create(key, "transactionId" -> id(t)).status, 503)
  }
  When("the sub-brand creates one under request id {string} again") { (t: String) =>
    last = kit.create(key, "transactionId" -> id(t))
    kit.merchant.release(id(t))
  }
  Then("the cashier is asked again") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(creates, 2)
  }
  Then("a late answer to the first create is a lost answer and is not returned to the sub-brand") { () =>
    val lost = kit.eventually("the lost answer")(kit.records(key.subBrand).find(_.outcome == Outcome.LostAnswer))
    assert(lost.lostAnswer.flatMap(_.ticket).exists(_ != last.str("session_token")))
    assertEquals(kit.create(key, "transactionId" -> lost.requestId.get).text, last.text)
  }

  // ── the submit (US3) ───────────────────────────────────────────────────────────────────────────

  Given("a cashier session already submitted") { () =>
    session()
    first = kit.submit(ticket)
  }
  When("the sub-brand submits it again") { () => last = kit.submit(ticket) }
  Then("the first submit's answer is returned") { () => assertEquals((last.status, last.text), (first.status, first.text)) }
  Then("no second payment exists") { () => assertEquals(kit.merchant.payment(first.str("paymentId")).isDefined, true) }

  Given("a cashier session offering terminal {string} only") { (terminal: String) =>
    kit.merchant.terminals = Vector(terminal)
    session()
  }
  When("the sub-brand submits it naming terminal {string}") { (terminal: String) => last = kit.submit(ticket, terminal) }
  Then("it is refused naming {string}") { (terminal: String) =>
    assertEquals((last.status, last.str("error")), (400, "terminal_not_found"))
    assert(last.text.contains(terminal), last.text)
  }

  Given("a cashier session whose submit the payment provider refuses for a reason the player can act on") { () =>
    session()
    kit.merchant.refuseSubmit = Some(Answer(400, """{"error":"PAYMENT_REJECTED","message":"The minimum deposit is 10.00 EUR"}"""))
  }
  When("the answer comes back") { () => last = kit.submit(ticket) }
  Then("the sub-brand is told the submit was rejected, with the payment provider's words, so that the cashier page shows them") { () =>
    assertEquals((last.status, last.str("error"), last.str("message")), (400, "PAYMENT_REJECTED", "The minimum deposit is 10.00 EUR"))
  }

  Given("a cashier ticket no session has") { () => ticket = s"no-such-${id("ticket")}" }
  When("it is submitted") { () => last = kit.submit(ticket) }
  Then("it is refused as not found") { () => assertEquals(last.status, 404, last.text) }

  // ── the read by sub-brand ──────────────────────────────────────────────────────────────────────

  Given("a payment created by sub-brand {string}") { (name: String) =>
    val k = subBrands.getOrElseUpdate(name, kit.issueKey())
    key = k
    ticket = kit.create(k).str("session_token")
    paymentId = kit.submit(ticket).str("paymentId")
  }
  When("sub-brand {string} reads it by its id") { (name: String) =>
    last = kit.readPayment(paymentId, key = Some(subBrands.getOrElseUpdate(name, kit.issueKey())))
  }
  Then("its state is returned") { () =>
    assertEquals((last.status, last.str("status")), (200, "AWAITING_REDIRECT"), last.text)
  }

  // ── the rate (US4) ─────────────────────────────────────────────────────────────────────────────

  Given("the installation's rate limit of {string} requests a minute") { (n: String) =>
    assertEquals(kit.settings.rateLimit, n.toInt)
    kit.holdClockInAMinute(15)
    key = kit.issueKey()
  }
  When("a sub-brand sends {string} creates in a minute") { (n: String) => replies = (1 to n.toInt).toVector.map(_ => kit.create(key)) }
  When("it sends {string} creates in a minute") { (n: String) => replies = (1 to n.toInt).toVector.map(_ => kit.create(key)) }
  Then("{string} are served") { (n: String) => assertEquals(replies.count(_.status == 200), n.toInt) }
  Then("{string} are refused as too many, with the time to wait") { (n: String) =>
    val refused = replies.filter(_.status == 429)
    assertEquals(refused.size, n.toInt)
    assert(refused.forall(r => r.str("error") == "TOO_MANY_REQUESTS" && r.header("Retry-After").contains("45")))
  }
  Then("the cashier receives {string}") { (n: String) => assertEquals(creates, n.toInt) }

  Given("a sub-brand refused as too many") { () =>
    kit.holdClockInAMinute(15)
    key = kit.issueKey()
    kit.request("PUT", s"/v1/ingest/sub-brands/${key.subBrand}/rate", Some("""{"perMinute":1}"""), kit.asOperator()): Unit
    kit.create(key): Unit
    assertEquals(kit.create(key).status, 429)
  }
  When("another sub-brand sends a create in the same minute") { () => last = kit.create(kit.issueKey()) }
  Then("it is served") { () => assertEquals(last.status, 200, last.text) }

  Given("a sub-brand whose rate limit an operator raised to {string}") { (n: String) =>
    kit.holdClockInAMinute(15)
    key = kit.issueKey()
    assertEquals(kit.request("PUT", s"/v1/ingest/sub-brands/${key.subBrand}/rate", Some(s"""{"perMinute":$n}"""), kit.asOperator()).status, 204)
  }
  Then("all {string} are served") { (n: String) => assertEquals(replies.count(_.status == 200), n.toInt) }

object CashierFeatures:
  // Here, not in the class: GherkinSuite reads ranElsewhere while it is constructed, before the class's own vals.
  private val Merchant = "spec 004's merchant suite: the gateway asked and the deadline set are merchant's inside"
