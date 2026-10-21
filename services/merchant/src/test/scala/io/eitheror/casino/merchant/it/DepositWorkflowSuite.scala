package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.deposits.DepositFlow
import io.eitheror.casino.merchant.payments.{CreditState, Status}

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/** The deposit workflow on the whole service (FR-020, FR-021, research R7), US1's cases. */
class DepositWorkflowSuite extends SharedKitSuite:

  test("a submit initiates at the gateway with the payment id as the key, and the payment awaits the redirect") {
    val r = kit.subBrand()
    val s = kit.session(r)
    assertEquals(s.status, 200, s.text)
    val p = kit.submit(s.str("session_token"))
    assertEquals(p.status, 200, p.text)
    val id = p.str("paymentId")
    assertEquals((p.str("status"), kit.paymentState(id).opened.map(_.redirectUrl)), (Status.AwaitingRedirect, Some(s"https://gateway.example/pay/$id")))
    val sent = kit.gateway.initiates.head.json.get
    assertEquals((sent.textOr("paymentId", ""), sent.long("amount"), sent.textOr("psp", ""), sent.textOr("customerEmail", "")),
      (id, Some(5000L), "DREAMZPAY", "player@example.com"))
    assertEquals(kit.flow(id).status, DepositFlow.Awaiting)
  }

  test("a capture credits the brand's wallet once under the payment id with the provider's reference, and is acknowledged after") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.delayMs = 300
    val c = kit.capture(id)
    assertEquals(c.status, 200, c.text)
    assertEquals(c.json.get("ok").asBoolean, true)
    val deposit = kit.wallet.depositsOf(id)
    assertEquals(deposit.size, 1)
    assertEquals(deposit.head.json.get.textOr("paymentReference", ""), s"psp-$id")
    assert(deposit.head.path.matches("/v1/casino/brands/acme/wallets/p-[^/]+/EUR/deposits"), deposit.head.path)
    assertEquals(kit.paymentState(id).status, Status.Captured)
    assertEquals(kit.paymentState(id).credit.map(_.status), Some(CreditState.Credited))
  }

  test("the same capture again is answered from the workflow and calls the wallet nothing") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    assertEquals(kit.capture(id).status, 200)
    assertEquals(kit.capture(id).status, 200)
    assertEquals(kit.wallet.depositsOf(id).size, 1)
  }

  test("a capture for a sub-brand of type payments that names no brand is captured with no wallet called") {
    val r  = kit.subBrand(brand = None)
    val id = kit.payment(r)
    val c  = kit.capture(id)
    assertEquals(c.status, 200, c.text)
    assertEquals(c.json.get("ok").asBoolean, true)
    assertEquals(kit.flow(id).status, DepositFlow.CapturedNoBrand)
    assertEquals(kit.wallet.deposits.size, 0)
  }

  test("a second start of a payment's workflow is refused and the first stands") {
    val r     = kit.subBrand()
    val s     = kit.session(r)
    val token = s.str("session_token")
    val first = kit.submit(token)
    val again = kit.submit(token)
    assertEquals(again.str("paymentId"), first.str("paymentId"), "a session submitted twice makes one payment")
    assertEquals(kit.gateway.initiates.size, 1)
  }

  // ── US2: a deposit that fails, expires or is abandoned ──────────────────────────────────────────

  private def shortDeadline(d: FiniteDuration = 1500.millis): Unit =
    kit.rewire(kit.settings.copy(paymentDeadline = JDuration.ofMillis(d.toMillis)))

  test("a failure records the provider's code and reason, and the default reason when it names none") {
    val r  = kit.subBrand()
    val a  = kit.payment(r)
    assertEquals(kit.fail(a, "failureCode" -> "05", "failureReason" -> "Do not honour").status, 200)
    assertEquals((kit.paymentState(a).status, kit.paymentState(a).failureCode, kit.paymentState(a).failureReason),
      (Status.Failed, Some("05"), Some("Do not honour")))
    val b = kit.payment(r)
    assertEquals(kit.fail(b).status, 200)
    assertEquals(kit.paymentState(b).failureReason, Some("Payment could not be processed"))
  }

  test("a payment past its deadline whose provider session is resolved or unknown expires with its reason") {
    shortDeadline()
    val r = kit.subBrand()
    val a = kit.payment(r)
    val b = kit.payment(r)
    kit.gateway.session(a, io.eitheror.casino.merchant.it.FakeGateway.Session.Resolved)
    kit.gateway.session(b, io.eitheror.casino.merchant.it.FakeGateway.Session.Unknown)
    for id <- Vector(a, b) do
      kit.eventually(s"$id expired")(Option(kit.paymentState(id)).filter(_.status == Status.Expired))
      assertEquals(kit.paymentState(id).failureReason, Some("Payment not completed"))
  }

  test("a payment past its deadline whose outcome the gateway holds undelivered waits again, says why, and is captured when the outcome arrives") {
    shortDeadline()
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.gateway.session(id, io.eitheror.casino.merchant.it.FakeGateway.Session.Undelivered)
    kit.eventually("waiting")(Option(kit.paymentState(id)).filter(_.waitingReason.isDefined))
    assertEquals(kit.paymentState(id).status, Status.AwaitingRedirect)
    assertEquals(kit.flow(id).status, DepositFlow.Waiting)
    val c = kit.capture(id)
    assertEquals(c.status, 200, c.text)
    assertEquals(kit.paymentState(id).status, Status.Captured)
    assertEquals(kit.wallet.depositsOf(id).size, 1)
  }

  test("a gateway that cannot say whether it holds an outcome is not read as none: the payment waits (the original fails closed)") {
    shortDeadline()
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.gateway.session(id, io.eitheror.casino.merchant.it.FakeGateway.Session.Down)
    kit.eventually("waiting")(Option(kit.paymentState(id)).filter(_.waitingReason.isDefined))
    assertNotEquals(kit.paymentState(id).status, Status.Expired)
  }

  test("a player abandoning with the cashier ticket fails the payment as abandoned") {
    val r     = kit.subBrand()
    val s     = kit.session(r)
    val token = s.str("session_token")
    val id    = kit.submit(token).str("paymentId")
    val a     = kit.request("POST", s"/v1/cashier/sessions/$token/abandon", None, kit.asIngest)
    assertEquals(a.status, 200, a.text)
    assertEquals(a.str("status"), "FAILED")
    assertEquals((kit.paymentState(id).failureCode, kit.paymentState(id).failureReason), (Some("player_abandoned"), Some("Payment not completed")))
  }

  for arrival <- Vector("a failure", "an expiry", "an abandon") do
    test(s"$arrival arriving for a captured payment changes nothing and is acknowledged") {
      if arrival == "an expiry" then shortDeadline()
      val r     = kit.subBrand()
      val s     = kit.session(r)
      val token = s.str("session_token")
      val id    = kit.submit(token).str("paymentId")
      assertEquals(kit.capture(id).status, 200)
      arrival match
        case "a failure"  => assertEquals(kit.fail(id, "failureCode" -> "late").status, 200)
        case "an expiry"  => Thread.sleep(2500)
        case _            => assertEquals(kit.request("POST", s"/v1/cashier/sessions/$token/abandon", None, kit.asIngest).status, 200)
      assertEquals(kit.paymentState(id).status, Status.Captured)
      assertEquals(kit.paymentState(id).failureCode, None)
    }

  test("a capture and a failure sent together leave exactly one recorded") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    val both = Vector(
      java.util.concurrent.CompletableFuture.supplyAsync(() => kit.capture(id)),
      java.util.concurrent.CompletableFuture.supplyAsync(() => kit.fail(id, "failureCode" -> "race"))
    ).map(_.get())
    assert(both.forall(_.status == 200), both.toString)
    val st = kit.paymentState(id)
    assert(st.status == Status.Captured && st.failureCode.isEmpty || st.status == Status.Failed && st.capturedAt.isEmpty, st.toString)
    assertEquals(kit.wallet.depositsOf(id).size, if st.status == Status.Captured then 1 else 0)
  }

  test("a payment in flight whose merchant key is disabled still captures and credits (Edge cases)") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    import io.eitheror.casino.merchant.hierarchy.*
    val e = kit.components.forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(r.subBrandId))
    val k = e.call(SubBrand.generateKey).invoke(GenerateKey("k-1", "frontend", "d", Actor.operator("kit"), kit.now()))
    e.call(SubBrand.disableKey).invoke(DisableKey(k.keyId, Actor.operator("kit"), kit.now())): Unit
    assertEquals(kit.capture(id).status, 200)
    assertEquals(kit.paymentState(id).credit.map(_.status), Some(CreditState.Credited))
  }

  // ── US3: the credit, exactly once, however the wallet answers ───────────────────────────────────

  test("a capture with the wallet down is OUTCOME_PENDING, waits with backoff, and credits once when the wallet returns") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.down = true
    val c = kit.capture(id)
    assertEquals((c.status, c.str("error")), (503, "OUTCOME_PENDING"))
    kit.eventually("several attempts")(Option(kit.flow(id)).filter(f => f.status == DepositFlow.CreditWaiting && f.attempts >= 3))
    kit.wallet.down = false
    kit.eventually("credited")(Option(kit.flow(id)).filter(_.status == DepositFlow.Credited))
    assertEquals(kit.wallet.balance("acme", kit.paymentState(id).opened.get.playerId, "EUR"), 5000L)
    assertEquals(kit.capture(id).status, 200, "the redelivered capture is acknowledged once the wallet has answered")
    assertEquals(kit.wallet.depositCount("acme", kit.paymentState(id).opened.get.playerId, "EUR"), 1)
  }

  test("a capture whose wallet answer was lost in flight is asked again under the same payment id and credited once") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.loseAnswer(id)
    kit.capture(id): Unit
    kit.eventually("credited")(Option(kit.flow(id)).filter(_.status == DepositFlow.Credited))
    val player = kit.paymentState(id).opened.get.playerId
    assertEquals((kit.wallet.balance("acme", player, "EUR"), kit.wallet.depositCount("acme", player, "EUR")), (5000L, 1))
    assert(kit.wallet.depositsOf(id).size >= 2)
  }

  test("a final refusal stops the retries, leaves the payment captured and not credited, and an operator's retry credits it") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.refuse(id, "OVER_LIMIT", 409, "remaining" -> 1500L)
    val c = kit.capture(id)
    assertEquals(c.status, 200, "a final refusal settles the capture for the gateway")
    assertEquals(c.json.get("ok").asBoolean, true)
    assertEquals(kit.flow(id).status, DepositFlow.CreditRefused)
    val st = kit.paymentState(id)
    assertEquals((st.status, st.credit.map(_.status), st.credit.flatMap(_.details.get("remaining"))), (Status.Captured, Some(CreditState.Refused), Some("1500")))
    Thread.sleep(500)
    assertEquals(kit.wallet.depositsOf(id).size, 1, "no retry after a final refusal")
    kit.wallet.reset()
    val retried = kit.request("POST", s"/v1/payments/cashier/$id/retry-credit", None, kit.asOperator())
    assertEquals(retried.status, 200, retried.text)
    kit.eventually("credited")(Option(kit.flow(id)).filter(_.status == DepositFlow.Credited))
  }

  test("RG_UNAVAILABLE is retried as a 5xx is") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.refuse(id, "RG_UNAVAILABLE", 503)
    assertEquals(kit.capture(id).status, 503)
    kit.eventually("retrying")(Option(kit.flow(id)).filter(f => f.status == DepositFlow.CreditWaiting && f.attempts >= 2))
  }

