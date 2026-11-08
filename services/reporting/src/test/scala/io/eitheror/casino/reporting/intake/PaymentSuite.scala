package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{Recorded, Settings}
import io.eitheror.casino.reporting.Facts.*

/** T019: `Payment` over every family, a repeat, the dispute's amount rules; `payments-facts`. */
class PaymentSuite extends munit.FunSuite:
  private def kit = EventSourcedTestKit.of(Payment, Payment.id(1, "pay-1"))
  private def record(k: EventSourcedTestKit[Payment, PaymentState, PaymentEvent], fact: f.PaymentsPaymentsFact) =
    k.call(Payment.record)(PaymentFact(FactId.of(f.PaymentsPayments, fact), fact))

  private val initiated = f.PaymentInitiated("pay-1", "p-1", "psp-a", "t-1", m(1000), eur, at)
  private val captured  = f.PaymentCaptured("pay-1", "p-1", "psp-a", m(1000), eur, at.plusSeconds(60))
  private val opened    = f.PaymentDisputeOpened("pay-1", "p-1", "psp-a", m(900), eur, "fraud", at.plusSeconds(3600))
  private def closedAs(outcome: String, amount: Option[Long] = None) =
    f.PaymentDisputeClosed("pay-1", "p-1", "psp-a", outcome, at.plusSeconds(7200), amount.map(m), amount.map(_ => eur))

  test("initiated then captured: the payment, its provider and its outcome") {
    val k = kit
    assertEquals(record(k, initiated).events, Vector(Initiated(FactId.of(f.PaymentsPayments, initiated), "pay-1", "p-1", "psp-a", "t-1", 1000, "EUR", at)))
    record(k, captured): Unit
    val s = k.currentState
    assertEquals((s.player, s.provider, s.amount, s.currency, s.outcome, s.endedAt), ("p-1", "psp-a", 1000L, "EUR", Some("captured"), Some(captured.occurredAt)))
  }

  test("failed, expired and abandoned end the payment") {
    Seq(f.PaymentFailed("pay-1", "p-1", "psp-a", "05", "declined", at) -> "failed", f.PaymentExpired("pay-1", "p-1", "psp-a", at) -> "expired",
      f.PaymentAbandoned("pay-1", "p-1", "psp-a", at) -> "abandoned").foreach { (fact, outcome) =>
      val k = kit
      record(k, initiated): Unit
      assertEquals(record(k, fact).events.collect { case e: Ended => e.outcome }, Vector(outcome))
      assertEquals(k.currentState.outcome, Some(outcome))
    }
  }

  test("a refund adds up; a repeat persists nothing") {
    val k = kit
    val refund = f.PaymentRefunded("pay-1", "p-1", "psp-a", m(300), eur, at)
    record(k, refund): Unit
    assert(!record(k, refund).persisted)
    record(k, refund.copy(occurredAt = at.plusSeconds(1))): Unit
    assertEquals(k.currentState.refunded, 600L)
  }

  test("a lost dispute is a chargeback of the close's amount, else the opened dispute's") {
    val k = kit
    record(k, initiated): Unit
    record(k, opened): Unit
    val withOwn = record(k, closedAs("lost", Some(800))).events.collect { case c: DisputeClosed => c }.head
    assertEquals((withOwn.chargeback, withOwn.amount, withOwn.currency), (true, Some(800L), Some("EUR")))
    val k2 = kit
    record(k2, opened): Unit
    val fromOpened = record(k2, closedAs("lost")).events.collect { case c: DisputeClosed => c }.head
    assertEquals(fromOpened.amount, Some(900L))
    assertEquals(k2.currentState.dispute.flatMap(_.outcome), Some("lost"))
  }

  test("a lost dispute with no amount and none opened has no amount; a won one is no chargeback") {
    val k = kit
    record(k, initiated): Unit
    val bare = record(k, closedAs("lost")).events.collect { case c: DisputeClosed => c }.head
    assertEquals((bare.chargeback, bare.amount, bare.currency), (true, None, Some("EUR")))
    assert(!record(kit, closedAs("won", Some(100))).events.collect { case c: DisputeClosed => c.chargeback }.head)
  }

  test("payments-facts sends each fact to its generation's payment") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(PaymentsFacts(Settings("goldvibe", generation = 2)), client = r.client)
    assertEquals(kit.onMessage(Some(captured)).effect, ConsumerEffect.Done)
    assertEquals(r.calls.map(c => (c.component, c.entity, c.method)), Vector(("payment", "g2/pay-1", "record")))
    assertEquals(r.calls.head.as[PaymentFact](using Payment.given_Serializer_PaymentFact).fact, captured)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }
