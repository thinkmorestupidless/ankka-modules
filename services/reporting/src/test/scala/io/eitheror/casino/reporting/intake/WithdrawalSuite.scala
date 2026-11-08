package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{Recorded, Settings}
import io.eitheror.casino.reporting.Facts.*
import io.eitheror.casino.reporting.intake.WithdrawalEvent.*

/** T020: `Withdrawal` over every family, a repeat, the gateway and the time to completion; `withdrawals-facts`. */
class WithdrawalSuite extends munit.FunSuite:
  private def kit = EventSourcedTestKit.of(Withdrawal, Withdrawal.id(1, "w-1"))
  private def record(k: EventSourcedTestKit[Withdrawal, WithdrawalState, WithdrawalEvent], fact: f.CasinoWithdrawalsFact) =
    k.call(Withdrawal.record)(WithdrawalFact(FactId.of(f.CasinoWithdrawals, fact), fact))

  private val requested = f.WithdrawalRequested("p-1", "w-1", m(2500), eur, "term-1", at, Some("unity"))
  private val approved  = f.WithdrawalApproved("p-1", "w-1", m(2500), eur, "staff-1", at.plusSeconds(600))
  private val completed = f.WithdrawalCompleted("p-1", "w-1", m(2500), eur, at.plusSeconds(7200))

  test("requested, approved, completed: pending until it ends; the completion carries the request's time and gateway") {
    val k = kit
    record(k, requested): Unit
    assert(k.currentState.pending)
    record(k, approved): Unit
    val done = record(k, completed).events.collect { case c: Completed => c }.head
    assertEquals((done.gateway, done.requestedAt, done.amount, done.currency), ("unity", Some(at), 2500L, "EUR"))
    val s = k.currentState
    assertEquals((s.outcome, s.approvedAt, s.endedAt, s.pending), (Some("completed"), Some(approved.occurredAt), Some(completed.occurredAt), false))
  }

  test("cancelled, failed and reversed end it; a gateway-less request is named by its terminal") {
    Seq(f.WithdrawalCancelled("p-1", "w-1", m(2500), eur, "player", at) -> "cancelled",
      f.WithdrawalFailed("p-1", "w-1", m(2500), eur, "declined", at) -> "failed",
      f.WithdrawalReversed("p-1", "w-1", m(2500), eur, "bounced", at) -> "reversed").foreach { (fact, outcome) =>
      val k = kit
      record(k, requested.copy(gateway = None)): Unit
      assertEquals(record(k, fact).events.map(_.gateway), Vector("term-1"))
      assertEquals(k.currentState.outcome, Some(outcome))
    }
  }

  test("a repeat persists nothing") {
    val k = kit
    record(k, requested): Unit
    assert(!record(k, requested).persisted)
  }

  test("withdrawals-facts sends each fact to its generation's request") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(WithdrawalsFacts(Settings("goldvibe")), client = r.client)
    assertEquals(kit.onMessage(Some(completed)).effect, ConsumerEffect.Done)
    assertEquals(r.calls.map(c => (c.component, c.entity, c.method)), Vector(("withdrawal", "g1/w-1", "record")))
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }
