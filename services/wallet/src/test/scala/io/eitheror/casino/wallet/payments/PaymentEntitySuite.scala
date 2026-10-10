package io.eitheror.casino.wallet.payments

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

class PaymentEntitySuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-10-09T10:00:00Z")

  private def outcome(kind: String, at: Instant = t0) =
    PaymentOutcome(kind, "p1", "dreamzpay", terminal = Some("t1"), amount = Some(10000), currency = Some("EUR"), at = at)

  test("each outcome is recorded once; a repeat answers the first and persists nothing (FR-022a)") {
    val kit   = EventSourcedTestKit.of(PaymentEntity, "pay-1")
    val first = kit.call(PaymentEntity.record)(outcome("initiated"))
    assertEquals(first.replyValue.recorded, true)
    val again = kit.call(PaymentEntity.record)(outcome("initiated", t0.plusSeconds(5)))
    assertEquals(again.replyValue, Recorded(recorded = false, outcome("initiated")))
    assert(!again.persisted)
    kit.call(PaymentEntity.record)(outcome("failed").copy(code = Some("DECLINED"), reason = Some("card declined")))
    assertEquals(kit.currentState.outcomes.map(_.outcome), Vector("initiated", "failed"))
  }

  test("captured is the wallet's deposit, never an outcome here, and is refused (research R8)") {
    val kit = EventSourcedTestKit.of(PaymentEntity, "pay-2")
    assert(kit.call(PaymentEntity.record)(outcome("captured")).isError)
    assertEquals(kit.currentState.outcomes, Vector.empty)
  }

  test("an outcome without the player or the provider is refused") {
    val kit = EventSourcedTestKit.of(PaymentEntity, "pay-3")
    assert(kit.call(PaymentEntity.record)(outcome("failed").copy(paymentProvider = "")).isError)
  }
