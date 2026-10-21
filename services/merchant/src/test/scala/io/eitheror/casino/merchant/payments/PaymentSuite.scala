package io.eitheror.casino.merchant.payments

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

/** FR-016, FR-017, FR-019, FR-025, FR-026 at the payment. */
class PaymentSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private val opened = Opened("sb-1", Some("acme"), "acme:p1", "p1", 10000, "EUR", "DREAMZPAY", "VISA", "main", "tx-1", "psp-1",
    "https://gw/pay", None, at, at.plusSeconds(1800))

  private def initiated =
    val kit = EventSourcedTestKit.of(Payment, "pay-1")
    kit.call(Payment.initiate)(Open(opened, Status.AwaitingRedirect)): Unit
    kit

  test("the initiate records the payment once; the step running again records nothing more") {
    val kit = initiated
    assertEquals(kit.currentState.status, Status.AwaitingRedirect)
    assert(!kit.call(Payment.initiate)(Open(opened, Status.AwaitingRedirect)).persisted)
  }

  test("a capture is recorded with what is credited; a command after a terminal status records nothing (FR-019)") {
    val kit = initiated
    kit.call(Payment.capture)(RecordCapture(None, 10000, at)): Unit
    assertEquals(kit.currentState.status, Status.Captured)
    assert(!kit.call(Payment.capture)(RecordCapture(None, 10000, at)).persisted)
    assert(!kit.call(Payment.fail)(RecordFailure(Some("late"), "x", None, at)).persisted)
    assert(!kit.call(Payment.expire)(At(at)).persisted)
  }

  test("an underpayment keeps the invoiced amount on record and reads the valuation as the amount (FR-026)") {
    val kit = initiated
    val v   = Some(Variance(Variance.Underpayment, Some("0.0012"), Some("80.00"), Some("EUR")))
    kit.call(Payment.capture)(RecordCapture(v, 8000, at)): Unit
    assertEquals((kit.currentState.originalAmount, kit.currentState.amount), (Some(10000L), 8000L))
  }

  test("a failure records the provider's code and reason; an expiry the default reason") {
    val kit = initiated
    kit.call(Payment.fail)(RecordFailure(Some("05"), "Do not honour", None, at)): Unit
    assertEquals((kit.currentState.failureCode, kit.currentState.failureReason), (Some("05"), Some("Do not honour")))
    val k2 = EventSourcedTestKit.of(Payment, "pay-2")
    k2.call(Payment.initiate)(Open(opened, Status.AwaitingRedirect)): Unit
    k2.call(Payment.expire)(At(at)): Unit
    assertEquals((k2.currentState.status, k2.currentState.failureReason), (Status.Expired, Some("Payment not completed")))
  }

  test("the credit's attempts, a final refusal and an operator's retry are recorded") {
    val kit = initiated
    kit.call(Payment.capture)(RecordCapture(None, 10000, at)): Unit
    kit.call(Payment.creditAttempted)(RecordAttempt("503 OUTCOME_PENDING", at)): Unit
    kit.call(Payment.creditRefused)(RecordCreditRefused("OVER_LIMIT", "over", Map("remaining" -> "1500"), at)): Unit
    assertEquals(kit.currentState.credit.map(c => (c.status, c.attempts, c.details)), Some((CreditState.Refused, 2, Map("remaining" -> "1500"))))
    kit.call(Payment.creditRetried)(RecordRetry("op-1", at)): Unit
    kit.call(Payment.credited)(RecordCredited(Some("txn"), at)): Unit
    assertEquals(kit.currentState.credit.map(_.status), Some(CreditState.Credited))
  }

  test("a payment past its deadline with no outcome reads as expired before any timer fires (FR-017)") {
    val s = initiated.currentState
    assert(!s.lazilyExpired(at.plusSeconds(1799)))
    assert(s.lazilyExpired(at.plusSeconds(1800)))
    val read = PaymentRead.json(s, at.plusSeconds(1801))
    assertEquals((read.get("status").asText, read.get("failureReason").asText), ("EXPIRED", "Payment not completed"))
    assertEquals((read.get("amount").asText, read.get("amountCents").asLong), ("100.00", 10000L))
  }
