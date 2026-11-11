package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.money.Currency

import java.time.Instant

/** FR-008, FR-016, FR-022, FR-024, SC-002 at the deposit session. */
class DepositSessionSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-18T10:00:00Z")
  private val claim = DepositClaim(Provider.Cozeni, "USDT", "sb-1", Some("term-1"), "acme:p1", 1500, Currency.unsafe("EUR"),
    "https://gw/v1/psp/checkout/cozeni/pay-1/return", "https://gw/v1/webhooks/cozeni", Map("salt" -> "s4lt"))
  private val initiation = Initiation(Some("cz-1"), Destination.Redirect("https://cz/pay"), Map("cozeniTxnId" -> "cz-1"), at)
  private val captured = Outcome.Captured()

  private def claimed =
    val kit = EventSourcedTestKit.of(DepositSession, "pay-1")
    kit.call(DepositSession.claim)(Claim(claim, at)): Unit
    kit

  private def settled =
    val kit = claimed
    kit.call(DepositSession.initiated)(RecordInitiation(initiation)): Unit
    kit.call(DepositSession.settle)(Settle(captured, "COMPLETED", at)): Unit
    kit

  test("claim on an empty session persists Claimed with the generated salt and the addresses") {
    val kit = claimed
    assertEquals(kit.allEvents, Vector(DepositEvent.Claimed(claim, at)))
    assertEquals(kit.currentState.claim.map(_.generated), Some(Map("salt" -> "s4lt")))
    assertEquals(kit.currentState.stage, Stage.Claimed)
  }

  test("a second claim persists nothing and replies the current state (FR-008)") {
    val kit = claimed
    val r   = kit.call(DepositSession.claim)(Claim(claim.copy(amount = 9999), at.plusSeconds(1)))
    assert(!r.persisted)
    assertEquals(r.replyValue.claim.map(_.amount), Some(1500L))
  }

  test("initiated only from claimed") {
    val kit = claimed
    assert(kit.call(DepositSession.initiated)(RecordInitiation(initiation)).persisted)
    assert(!kit.call(DepositSession.initiated)(RecordInitiation(initiation)).persisted)
    assert(EventSourcedTestKit.of(DepositSession, "none").call(DepositSession.initiated)(RecordInitiation(initiation)).isError)
  }

  test("initiationFailed only from claimed, and then settle is refused (FR-024)") {
    val kit = claimed
    assert(kit.call(DepositSession.initiationFailed)(FailInitiation(Refusal(502, "provider_unavailable", provider = Some("COZENI")), at)).persisted)
    assertEquals(kit.currentState.stage, Stage.InitiationFailed)
    assert(kit.call(DepositSession.settle)(Settle(captured, "COMPLETED", at)).isError)
    val other = claimed
    other.call(DepositSession.initiated)(RecordInitiation(initiation)): Unit
    assert(!other.call(DepositSession.initiationFailed)(FailInitiation(Refusal(502, "x"), at)).persisted)
  }

  test("settle from claimed or initiated persists one Settled; a second replies AlreadySettled (SC-002)") {
    val fromClaimed = claimed
    assert(fromClaimed.call(DepositSession.settle)(Settle(captured, "COMPLETED", at)).replyValue.settled)
    val kit = settled
    val again = kit.call(DepositSession.settle)(Settle(Outcome.Failed(None, "late"), "FAILED", at))
    assert(!again.persisted)
    assert(!again.replyValue.settled)
    assertEquals(again.replyValue.state.outcome, Some(captured))
  }

  test("recordDelivery only after Settled, and ignored once Delivered or Refused") {
    val kit = claimed
    assert(!kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)).persisted)
    val s = settled
    assert(s.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Undelivered(at, 4, "503"), 4, at)).persisted)
    assert(s.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)).persisted)
    assertEquals(s.currentState.attempts, 5)
    assert(!s.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Undelivered(at, 1, "no answer"), 1, at)).persisted)
    val r = settled
    r.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Refused(at, 403, "invalid_token"), 1, at)): Unit
    assert(!r.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)).persisted)
  }

  test("redrive by the timer only while Undelivered; by an operator while Undelivered or Refused (FR-016)") {
    val pending = settled
    val notYet  = pending.call(DepositSession.redrive)(Redrive(DepositSession.Timer, at))
    assert(!notYet.persisted)
    assertEquals(notYet.replyValue, Redriving(false, Delivery.NotYet))

    val u = settled
    u.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Undelivered(at, 4, "503"), 4, at)): Unit
    assert(u.call(DepositSession.redrive)(Redrive(DepositSession.Timer, at)).replyValue.redriven)

    val r = settled
    r.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Refused(at, 403, "invalid_token"), 1, at)): Unit
    assert(!r.call(DepositSession.redrive)(Redrive(DepositSession.Timer, at)).persisted, "the timer leaves a refusal alone")
    assert(r.call(DepositSession.redrive)(Redrive("op-1", at)).replyValue.redriven)
    assert(r.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)).persisted,
      "an operator's re-drive opens a refused delivery to be recorded again")
    assertEquals(r.currentState.redrives, 1)

    val d = settled
    d.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)): Unit
    val done = d.call(DepositSession.redrive)(Redrive("op-1", at))
    assert(!done.persisted)
    assertEquals(done.replyValue, Redriving(false, Delivery.Delivered(at, 200)))
  }

  test("status answers 004's state, outcome, delivered, undelivered, refused and updatedAt") {
    val c = claimed
    assertEquals(c.call(DepositSession.status).replyValue, SessionStatus("pending", None, false, false, false, Some(at)))
    val s = settled
    assertEquals(s.call(DepositSession.status).replyValue, SessionStatus("resolved", Some("captured"), false, true, false, Some(at)),
      "an outcome not yet delivered reads undelivered, so merchant waits rather than expiring")
    s.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at.plusSeconds(1))): Unit
    assertEquals(s.call(DepositSession.status).replyValue, SessionStatus("resolved", Some("captured"), true, false, false, Some(at.plusSeconds(1))))
    val r = settled
    r.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Refused(at, 403, "invalid_token"), 1, at)): Unit
    assert(r.call(DepositSession.status).replyValue.refused)
    assert(EventSourcedTestKit.of(DepositSession, "none").call(DepositSession.status).isError)
  }

  // Disputes (spec 015, contracts/facts.md "Disputes, end to end").

  private val eur     = Currency.unsafe("EUR")
  private val opened  = Dispute.Opened(1500, eur)
  private val lost    = Dispute.Closed(Disputes.Lost, Some(1500), Some(eur))
  private def report(kit: EventSourcedTestKit[DepositSession, DepositState, DepositEvent], d: Dispute) =
    kit.call(DepositSession.reportDispute)(ReportDispute(d, d.kind, at))

  test("a dispute is recorded only on a captured deposit: an opening once, a closing once, never an opening after a closing") {
    assert(!report(claimed, opened).persisted, "nothing was captured")
    val failed = claimed
    failed.call(DepositSession.settle)(Settle(Outcome.Failed(None, "declined"), "FAILED", at)): Unit
    assert(!report(failed, opened).replyValue.reported, "a failed deposit took no money")

    val kit = settled
    assert(report(kit, opened).replyValue.reported)
    assert(!report(kit, opened).persisted, "a repeated opening")
    assert(report(kit, lost).replyValue.reported)
    assert(!report(kit, lost).persisted, "a repeated closing")
    assertEquals(kit.currentState.disputes.map(_.dispute), Vector(opened, lost))

    val closedFirst = settled
    assert(report(closedFirst, lost).replyValue.reported, "a closing the provider reports with no opening is recorded")
    assert(!report(closedFirst, opened).persisted, "an opening after the closing is out of order")
  }

  test("the outcome is delivered first, then each dispute in turn; a delivery out of turn is ignored") {
    val kit = settled
    report(kit, opened): Unit
    report(kit, lost): Unit
    assertEquals(kit.currentState.current, Some(None -> Delivery.NotYet))
    assert(!kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at, Some(Disputes.Opened))).persisted,
      "the capture is not delivered yet")
    kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)): Unit
    assertEquals(kit.currentState.current, Some(Some(Disputes.Opened) -> Delivery.NotYet))
    assert(kit.currentState.deliverable)
    assert(!kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at, Some(Disputes.Closed))).persisted)
    kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Undelivered(at, 4, "503"), 4, at, Some(Disputes.Opened))): Unit
    assertEquals(kit.currentState.currentDelivery, Delivery.Undelivered(at, 4, "503"))
    assert(kit.call(DepositSession.redrive)(Redrive(DepositSession.Timer, at)).replyValue.redriven, "an undelivered dispute is re-driven")
    kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at, Some(Disputes.Opened))): Unit
    kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at, Some(Disputes.Closed))): Unit
    assertEquals(kit.currentState.current, None)
    assert(!kit.currentState.deliverable)
    assertEquals(kit.currentState.disputes.map(r => (r.dispute.kind, r.attempts)), Vector(Disputes.Opened -> 5, Disputes.Closed -> 1))
    assertEquals(kit.currentState.attempts, 1, "the outcome's attempts are its own")
    assertEquals(kit.call(DepositSession.status).replyValue.outcome, Some("captured"), "004's status read is the outcome's")
  }

  test("no event holds a credential or a customer field (FR-022)") {
    val kit = settled
    kit.call(DepositSession.recordDelivery)(RecordDelivery(Delivery.Delivered(at, 200), 1, at)): Unit
    val journal = kit.allEvents.map(_.toString).mkString
    Vector("secureKey", "apiKey", "customerEmail", "@", "firstName").foreach(w => assert(!journal.contains(w), w))
  }
