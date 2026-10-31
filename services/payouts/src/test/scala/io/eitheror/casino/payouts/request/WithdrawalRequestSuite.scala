package io.eitheror.casino.payouts.request

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/** data-model.md's `WithdrawalRequest` table, row by row, R1's decisions, repeats, and erasure. */
class WithdrawalRequestSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-15T12:00:00Z")
  private type Kit = EventSourcedTestKit[WithdrawalRequest, RequestState, RequestEvent]
  private val terminal = TerminalRef("t-1", "sb-1", "Bank", "unityfinance", "EUBANKING", true, hasStatus = true)
  private val payee = Payee(destinationId = Some("d-1"), holder = Some(Holder(Personal("Ana"), Personal("Silva"))),
    iban = Some(Personal("DE89370400440532013000")))
  private val opening = Open("r-1", "p-1", 5000L, 4800L, "EUR", terminal, payee, Forfeit(true, 200L, 0L), "verified", at)

  private def fresh: Kit = EventSourcedTestKit.of(WithdrawalRequest, "r-1")
  private def opened: Kit =
    val kit = fresh
    kit.call(WithdrawalRequest.open)(opening): Unit
    kit
  private def approved: Kit =
    val kit = opened
    kit.call(WithdrawalRequest.approve)(Approve("op-1", at)): Unit
    kit
  private def status(kit: Kit) = (kit.currentState.status, kit.currentState.payoutStatus)

  test("Open records the request pending review; a repeat writes nothing"):
    val kit = opened
    val s   = kit.currentState
    assertEquals((s.opened, s.playerId, s.amount, s.paidAmount, s.terminal, s.forfeit.forfeitedAmount, s.createdAt),
      (true, "p-1", 5000L, 4800L, Some(terminal), 200L, at))
    assertEquals(status(kit), (Status.PendingReview, PayoutStatus.None))
    assertEquals(kit.call(WithdrawalRequest.open)(opening).events.toVector, Vector.empty)

  test("a request not opened is not found"):
    assertEquals(fresh.call(WithdrawalRequest.approve)(Approve("op-1", at)).replyValue.refusal, Some("REQUEST_NOT_FOUND"))

  test("Cancel from pending review; a repeat writes nothing; once approved, not cancellable"):
    val kit = opened
    kit.call(WithdrawalRequest.cancel)(Cancel(Actor.Player, None, at)): Unit
    assertEquals((kit.currentState.status, kit.currentState.settledAt), (Status.Cancelled, Some(at)))
    assertEquals(kit.call(WithdrawalRequest.cancel)(Cancel(Actor.Player, None, at)).events.toVector, Vector.empty)
    assertEquals(approved.call(WithdrawalRequest.cancel)(Cancel(Actor.Player, None, at)).replyValue.refusal,
      Some("WITHDRAWAL_REQUEST_NOT_CANCELLABLE"))

  test("R1: a second Approve or Reject, or a Reject after approval, is REQUEST_NOT_PENDING"):
    val kit = approved
    assertEquals(kit.currentState.decisions.map(d => (d.approved, d.operatorId)), Vector((true, "op-1")))
    assertEquals(kit.call(WithdrawalRequest.approve)(Approve("op-2", at)).replyValue.refusal, Some("REQUEST_NOT_PENDING"))
    assertEquals(kit.call(WithdrawalRequest.reject)(Reject("op-2", None, at)).replyValue.refusal, Some("REQUEST_NOT_PENDING"))
    val rejected = opened
    rejected.call(WithdrawalRequest.reject)(Reject("op-1", Some(Personal("duplicate account")), at)): Unit
    assertEquals(rejected.currentState.status, Status.Rejected)
    assertEquals(rejected.call(WithdrawalRequest.reject)(Reject("op-1", None, at)).replyValue.refusal, Some("REQUEST_NOT_PENDING"))

  test("AwaitManual moves an approved request to awaiting manual payout"):
    val kit = approved
    kit.call(WithdrawalRequest.awaitManual)(AwaitManual("no_automated_payout", at)): Unit
    assertEquals(status(kit), (Status.AwaitingManualPayout, PayoutStatus.None))
    assertEquals(kit.currentState.awaitingSettlement, true)

  test("Initiated records the reference and the time; NeedsReview marks it; a repeat writes nothing"):
    val kit = approved
    kit.call(WithdrawalRequest.initiated)(Initiated("initiated", Some("uf-1"), at)): Unit
    assertEquals((status(kit), kit.currentState.providerReference, kit.currentState.initiatedAt),
      ((Status.Approved, PayoutStatus.Initiated), Some("uf-1"), Some(at)))
    assertEquals(kit.call(WithdrawalRequest.initiated)(Initiated("initiated", Some("uf-1"), at)).events.toVector, Vector.empty)
    kit.call(WithdrawalRequest.needsReview)(NeedsReview("no_record", at)): Unit
    assertEquals(status(kit), (Status.Approved, PayoutStatus.NeedsReview))
    assertEquals(kit.currentState.awaitingSettlement, true)
    assertEquals(kit.call(WithdrawalRequest.needsReview)(NeedsReview("no_record", at)).events.toVector, Vector.empty)

  test("Record keeps every answer"):
    val kit = approved
    kit.call(WithdrawalRequest.record)(Record(OutcomeRecord(Source.Gateway, "in_doubt", Some("timeout"), at = at))): Unit
    assertEquals(kit.currentState.outcomes.map(_.answer), Vector("in_doubt"))

  test("Complete from approved or awaiting manual payout; a repeat writes nothing; otherwise not awaiting payout"):
    val kit = approved
    kit.call(WithdrawalRequest.complete)(Complete(Source.Outcome, Some("uf-1"), None, at)): Unit
    assertEquals(status(kit), (Status.Completed, PayoutStatus.Resolved))
    assertEquals(kit.call(WithdrawalRequest.complete)(Complete(Source.Outcome, Some("uf-1"), None, at)).events.toVector, Vector.empty)
    assertEquals(opened.call(WithdrawalRequest.complete)(Complete(Source.Operator, None, None, at)).replyValue.refusal,
      Some("WITHDRAWAL_NOT_AWAITING_PAYOUT"))
    val manual = approved
    manual.call(WithdrawalRequest.awaitManual)(AwaitManual("x", at)): Unit
    manual.call(WithdrawalRequest.complete)(Complete(Source.Operator, None, Some(Personal("paid by hand")), at)): Unit
    assertEquals(manual.currentState.status, Status.Completed)

  test("Fail from approved or awaiting manual payout; a repeat writes nothing; once completed, not awaiting payout"):
    val kit = approved
    kit.call(WithdrawalRequest.fail)(Fail(Source.Gateway, "provider_rejected", None, at)): Unit
    assertEquals(status(kit), (Status.Failed, PayoutStatus.Resolved))
    assertEquals(kit.call(WithdrawalRequest.fail)(Fail(Source.Gateway, "provider_rejected", None, at)).events.toVector, Vector.empty)
    val done = approved
    done.call(WithdrawalRequest.complete)(Complete(Source.Outcome, None, None, at)): Unit
    assertEquals(done.call(WithdrawalRequest.fail)(Fail(Source.Outcome, "payout_failed", None, at)).replyValue.refusal,
      Some("WITHDRAWAL_NOT_AWAITING_PAYOUT"))

  test("Reverse only a completed request; a repeat writes nothing"):
    val kit = approved
    assertEquals(kit.call(WithdrawalRequest.reverse)(Reverse("chargeback", None, at)).replyValue.refusal,
      Some("WITHDRAWAL_NOT_IN_COMPLETED_STATE"))
    kit.call(WithdrawalRequest.complete)(Complete(Source.Outcome, Some("uf-1"), None, at)): Unit
    kit.call(WithdrawalRequest.reverse)(Reverse("chargeback", Some("uf-1"), at)): Unit
    assertEquals(kit.currentState.status, Status.Reversed)
    assertEquals(kit.call(WithdrawalRequest.reverse)(Reverse("chargeback", Some("uf-1"), at)).events.toVector, Vector.empty)

  test("ReferenceMismatch is recorded and moves nothing"):
    val kit = approved
    val r   = kit.call(WithdrawalRequest.referenceMismatch)(ReferenceMismatch("other", at))
    assertEquals(r.events.toVector, Vector[RequestEvent](RequestEvent.ReferenceMismatched("other", at)))
    assertEquals(status(kit), (Status.Approved, PayoutStatus.None))

  test("Erase blanks the payee, the reasons and the notes, and keeps the rest"):
    val kit = opened
    kit.call(WithdrawalRequest.reject)(Reject("op-1", Some(Personal("looks like fraud")), at)): Unit
    kit.call(WithdrawalRequest.record)(Record(OutcomeRecord(Source.Operator, "noted", note = Some(Personal("called them")), at = at))): Unit
    kit.call(WithdrawalRequest.erase)(Erase(at)): Unit
    val s = kit.currentState
    assertEquals((s.payee, s.decisions.map(_.reason), s.outcomes.map(_.note)), (Payee(destinationId = Some("d-1")), Vector(None), Vector(None)))
    assertEquals((s.status, s.amount, s.erased), (Status.Rejected, 5000L, true))
    assertEquals(kit.call(WithdrawalRequest.erase)(Erase(at)).events.toVector, Vector.empty)
