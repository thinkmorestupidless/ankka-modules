package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.providers.PayoutAnswer

import java.time.Instant

/** FR-004, FR-009, FR-022 at the payout session; the deposit's delivery rules hold as they are. */
class PayoutSessionSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-18T10:00:00Z")
  private val claim = PayoutClaim("goldvibe", "sb-1", Provider.UnityFinance, "term-1", "OPEN_BANKING", 5000,
    Currency.unsafe("EUR"), "https://gw/v1/webhooks/unityfinance/withdrawal")

  private def claimed =
    val kit = EventSourcedTestKit.of(PayoutSession, "wd-1")
    kit.call(PayoutSession.claim)(ClaimPayout(claim, at)): Unit
    kit

  test("claim once; a repeat replies the recorded state and persists nothing") {
    val kit = claimed
    kit.call(PayoutSession.answered)(RecordAnswer(PayoutAnswer.Initiated(Some("uf-1")), at)): Unit
    val r = kit.call(PayoutSession.claim)(ClaimPayout(claim, at))
    assert(!r.persisted)
    assertEquals(r.replyValue.answer, Some(PayoutAnswer.Initiated(Some("uf-1"))))
  }

  test("claim naming another brand than the session's is refused") {
    val kit = claimed
    val r   = kit.call(PayoutSession.claim)(ClaimPayout(claim.copy(brand = "spinvibe"), at))
    assert(r.isError)
    assert(!r.persisted)
  }

  test("answered(Completed) persists Answered then Settled(Completed) in one command") {
    val kit = claimed
    val r   = kit.call(PayoutSession.answered)(RecordAnswer(PayoutAnswer.Completed(Some("cz-9")), at))
    assertEquals(r.events, Vector(PayoutEvent.Answered(PayoutAnswer.Completed(Some("cz-9")), at),
      PayoutEvent.Settled(Outcome.Completed, Some("cz-9"), "completed", at)))
    assertEquals(kit.currentState.outcome, Some(Outcome.Completed))
    assert(kit.currentState.deliverable)
  }

  test("answered(AlreadyInitiated) is recorded and a repeat claim answers it") {
    val kit = claimed
    assert(kit.call(PayoutSession.answered)(RecordAnswer(PayoutAnswer.AlreadyInitiated, at)).persisted)
    assertEquals(kit.call(PayoutSession.claim)(ClaimPayout(claim, at)).replyValue.answer, Some(PayoutAnswer.AlreadyInitiated))
    assert(!kit.call(PayoutSession.answered)(RecordAnswer(PayoutAnswer.Rejected("x"), at)).persisted, "answered once")
  }

  test("Reversed follows Completed; nothing else follows a final outcome") {
    val kit = claimed
    kit.call(PayoutSession.answered)(RecordAnswer(PayoutAnswer.Initiated(Some("uf-1")), at)): Unit
    assert(kit.call(PayoutSession.settle)(SettlePayout(Outcome.Completed, None, "8", at)).replyValue.settled)
    assert(!kit.call(PayoutSession.settle)(SettlePayout(Outcome.PayoutFailed(None, "x"), None, "9", at)).persisted)
    assert(!kit.call(PayoutSession.settle)(SettlePayout(Outcome.Completed, None, "8", at)).persisted)
    assert(kit.call(PayoutSession.settle)(SettlePayout(Outcome.Reversed, None, "11", at)).replyValue.settled)
    assert(!kit.call(PayoutSession.settle)(SettlePayout(Outcome.Reversed, None, "11", at)).persisted)

    val failed = claimed
    failed.call(PayoutSession.settle)(SettlePayout(Outcome.PayoutFailed(None, "Payout cancelled"), None, "canceled", at)): Unit
    assert(!failed.call(PayoutSession.settle)(SettlePayout(Outcome.Reversed, None, "11", at)).persisted)
  }

  test("a reversal after a delivered completion is delivered again") {
    val kit = claimed
    kit.call(PayoutSession.settle)(SettlePayout(Outcome.Completed, None, "8", at)): Unit
    kit.call(PayoutSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)): Unit
    assert(!kit.currentState.deliverable)
    kit.call(PayoutSession.settle)(SettlePayout(Outcome.Reversed, None, "11", at)): Unit
    assert(kit.currentState.deliverable)
    assertEquals(kit.currentState.receiver, Some(Receiver.Payouts("goldvibe", "wd-1")))
  }

  test("the delivery and re-drive rules are the deposit's") {
    val kit = claimed
    kit.call(PayoutSession.settle)(SettlePayout(Outcome.Completed, None, "8", at)): Unit
    kit.call(PayoutSession.recordDelivery)(RecordDelivery(Delivery.Refused(at, 403, "revoked"), 1, at)): Unit
    assert(!kit.call(PayoutSession.redrive)(Redrive(DepositSession.Timer, at)).persisted)
    assert(kit.call(PayoutSession.redrive)(Redrive("op-1", at)).replyValue.redriven)
  }

  test("the destination is in no event (FR-022)") {
    val kit = claimed
    kit.call(PayoutSession.answered)(RecordAnswer(PayoutAnswer.Initiated(Some("uf-1")), at)): Unit
    val journal = kit.allEvents.map(_.toString).mkString
    Vector("iban", "IBAN", "DE89", "firstName").foreach(w => assert(!journal.contains(w), w))
  }
