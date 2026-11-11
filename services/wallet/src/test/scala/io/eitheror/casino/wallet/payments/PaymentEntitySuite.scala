package io.eitheror.casino.wallet.payments

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import java.time.Instant

class PaymentEntitySuite extends munit.FunSuite with LogCapturing:

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

  private def closed(result: String, amount: Option[Long] = None, currency: Option[String] = None) =
    PaymentOutcome("dispute_closed", "p1", "unityfinance", amount = amount, currency = currency, disputeOutcome = Some(result), at = t0)

  test("spec 015: a closed dispute with no amount of its own carries the amount and currency of the dispute as opened") {
    val kit = EventSourcedTestKit.of(PaymentEntity, "pay-4")
    kit.call(PaymentEntity.record)(outcome("dispute_opened").copy(amount = Some(2500), currency = Some("EUR"), reason = Some("chargeback"))): Unit
    val reply = kit.call(PaymentEntity.record)(closed("lost")).replyValue
    assertEquals((reply.first.amount, reply.first.currency, reply.first.disputeOutcome), (Some(2500L), Some("EUR"), Some("lost")))
    assertEquals(kit.currentState.outcomes.last.amount, Some(2500L))
  }

  test("spec 015: a closed dispute that states its amount keeps it; one with no dispute opened and no amount carries none") {
    val kit = EventSourcedTestKit.of(PaymentEntity, "pay-5")
    kit.call(PaymentEntity.record)(outcome("dispute_opened").copy(amount = Some(2500), currency = Some("EUR"))): Unit
    assertEquals(kit.call(PaymentEntity.record)(closed("lost", Some(1000), Some("EUR"))).replyValue.first.amount, Some(1000L))
    val bare = EventSourcedTestKit.of(PaymentEntity, "pay-6").call(PaymentEntity.record)(closed("won")).replyValue.first
    assertEquals((bare.amount, bare.currency), (None, None))
  }

  test("spec 015: a closed dispute's outcome outside won and lost is refused and nothing is recorded") {
    val kit = EventSourcedTestKit.of(PaymentEntity, "pay-7")
    assert(kit.call(PaymentEntity.record)(closed("reversed")).isError)
    assert(kit.call(PaymentEntity.record)(closed("lost").copy(disputeOutcome = None)).isError)
    assertEquals(kit.currentState.outcomes, Vector.empty)
  }

  test("an outcome without the player or the provider is refused") {
    val kit = EventSourcedTestKit.of(PaymentEntity, "pay-3")
    assert(kit.call(PaymentEntity.record)(outcome("failed").copy(paymentProvider = "")).isError)
  }
