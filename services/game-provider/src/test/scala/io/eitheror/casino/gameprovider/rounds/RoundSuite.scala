package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import com.thinkmorestupidless.ankka.core.Serializers.given
import io.eitheror.casino.contract.facts.RoundSettled.Outcome
import io.eitheror.casino.gameprovider.rounds.RoundCodecs.given
import io.eitheror.casino.gameprovider.rounds.RoundEvent.*

import java.time.Instant

/** The round's rules (FR-011, FR-013 to FR-015, FR-019, FR-020) through the entity alone. */
class RoundSuite extends munit.FunSuite:

  private val at      = Instant.parse("2026-10-09T10:00:00Z")
  private val opening = RoundOpening("st8", "r-1", "p-1", "EUR", Some("book-of-ra"), Some("gs-1"), at)

  private def kit = EventSourcedTestKit.of(Round, "st8/r-1")

  private def ok(balance: Long) = StoredAnswer("ok", Some(balance), Some("EUR"))

  private def debit(id: String, amount: Long, balance: Long) =
    RecordTransaction(opening, ProviderTransaction(id, "debit", amount, None,
      Vector(Movement(Movement.Bet, id, Some(id), amount, s"w-$id")), ok(balance), at), opened = Some(OpenBet(id, id, amount, id)))

  private def credit(id: String, amount: Long, closed: Vector[String], movements: Vector[Movement], balance: Long) =
    RecordTransaction(opening, ProviderTransaction(id, "credit", amount, None, movements, ok(balance), at), closed = closed)

  test("a debit opens the round with an open bet and adds to the stake") {
    val k = kit
    val r = k.call(Round.record)(debit("t-1", 100, 900))
    assert(r.replyValue.first)
    assertEquals(r.events.map(_.getClass.getSimpleName), Vector("RoundOpened", "DebitRecorded"))
    assertEquals(k.currentState.openBets.map(_.walletKey), Vector("t-1"))
    assertEquals(k.currentState.totalStake, 100L)
    assertEquals(k.currentState.status, RoundState.Open)
  }

  test("a credit to a round with two debits settles both and publishes the win") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    k.call(Round.record)(debit("t-2", 200, 700))
    val r = k.call(Round.record)(credit("c-1", 500, Vector("t-1", "t-2"),
      Vector(Movement(Movement.Win, "c-1/t-1", Some("t-1"), 500, "w-c1"), Movement(Movement.Loss, "c-1/t-2", Some("t-2"), 0, "w-c2")), 1200))
    assertEquals(k.currentState.openBets, Vector.empty, "no bet of the round is left open")
    assertEquals(k.currentState.status, RoundState.Settled)
    val settled = r.eventOfType[RoundEvent.RoundSettled].summary
    assertEquals(settled.outcome, Outcome.Win)
    assertEquals(settled.stake, 300L)
    assertEquals(settled.win, 500L)
    assertEquals(settled.providerTransactionIds, Vector("t-1", "t-2", "c-1"))
    assertEquals(settled.walletTransactionIds, Vector("w-t-1", "w-t-2", "w-c1", "w-c2"))
  }

  test("a zero credit settles every open bet as a loss and publishes outcome loss") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    val r = k.call(Round.record)(credit("c-1", 0, Vector("t-1"), Vector(Movement(Movement.Loss, "c-1/t-1", Some("t-1"), 0, "w-c1")), 900))
    assertEquals(r.eventOfType[RoundEvent.RoundSettled].summary.outcome, Outcome.Loss)
  }

  test("a credit with no open bet settles as a provider credit, outcome credit") {
    val k = kit
    val r = k.call(Round.record)(credit("c-1", 500, Vector.empty, Vector(Movement(Movement.ProviderCredit, "c-1", None, 500, "w-c1")), 1500))
    assertEquals(r.eventOfType[RoundEvent.RoundSettled].summary.outcome, Outcome.Credit)
  }

  test("a transaction recorded twice (its workflow step run again) replies the first answer and records nothing") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    val again = k.call(Round.record)(debit("t-1", 100, 123))
    assert(!again.replyValue.first)
    assertEquals(again.replyValue.answer, ok(900))
    assert(!again.persisted)
    assertEquals(k.currentState.transaction("t-1").map(_.answer), Some(ok(900)))
  }

  test("a conflicting repeat is recorded on the round") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    k.call(Round.recordConflict)(ConflictingRepeat("t-1", "amount 100 then 999", at))
    assertEquals(k.currentState.conflictingRepeats.map(_.transactionId), Vector("t-1"))
  }

  test("a free debit tags the round, and its credit settles with the win and the grant, moving nothing") {
    val k = kit
    k.call(Round.tagFreeRounds)(TagFreeRounds(opening, "grant-1"))
    k.call(Round.record)(RecordTransaction(opening, ProviderTransaction("t-1", "free_debit", 0, None, Vector.empty, ok(1000), at)))
    val r = k.call(Round.record)(credit("c-1", 250, Vector.empty, Vector.empty, 1000))
    val s = r.eventOfType[RoundEvent.RoundSettled].summary
    assertEquals(s.freeRoundsGrant, Some("grant-1"))
    assertEquals((s.outcome, s.win, s.stake), (Outcome.Win, 250L, 0L))
    assertEquals(s.walletTransactionIds, Vector.empty)
  }

  test("an unacknowledged callback records no transaction, and is cleared when its retry is recorded") {
    val k     = kit
    val entry = UnacknowledgedEntry("c-1", "credit", "credit", 500, "wallet unreachable", "{}", at)
    k.call(Round.record)(debit("t-1", 100, 900))
    assert(k.call(Round.recordUnacknowledged)(RecordUnacknowledged(opening, entry)).replyValue)
    assertEquals((k.currentState.transaction("c-1"), k.currentState.openUnacknowledged("c-1").isDefined), (None, true))
    k.call(Round.record)(credit("c-1", 500, Vector("t-1"), Vector(Movement(Movement.Win, "c-1/t-1", Some("t-1"), 500, "w")), 1400)
      .copy(clearedBy = Some(Cleared(Cleared.ProviderRetry, None, None, at))))
    assertEquals(k.currentState.unacknowledged.head.cleared.map(_.how), Some(Cleared.ProviderRetry))
    assert(k.currentState.openUnacknowledged("c-1").isEmpty)
  }

  test("an operator's resolve clears an entry with its reason and operator and moves nothing") {
    val k = kit
    k.call(Round.recordUnacknowledged)(RecordUnacknowledged(opening, UnacknowledgedEntry("c-1", "credit", "credit", 500, "down", "{}", at)))
    assert(k.call(Round.clear)(ClearUnacknowledged("c-1", Cleared(Cleared.OperatorResolve, Some("paid by hand"), Some("op-1"), at))).replyValue)
    val cleared = k.currentState.unacknowledged.head.cleared.get
    assertEquals((cleared.reason, cleared.operator), (Some("paid by hand"), Some("op-1")))
    assert(!k.call(Round.clear)(ClearUnacknowledged("c-1", cleared)).replyValue, "nothing open to clear twice")
    assertEquals(k.currentState.transactions, Vector.empty)
  }

  test("an answered key is never reopened as unacknowledged") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    assert(!k.call(Round.recordUnacknowledged)(RecordUnacknowledged(opening, UnacknowledgedEntry("t-1", "debit", "debit", 100, "x", "{}", at))).replyValue)
  }

  test("a refused debit is answered and remembered, and opens no bet") {
    val k = kit
    k.call(Round.record)(RecordTransaction(opening, ProviderTransaction("t-1", "debit", 100, None, Vector.empty, StoredAnswer("not_enough_money", Some(50), Some("EUR")), at)))
    assertEquals(k.currentState.openBets, Vector.empty)
    assertEquals(k.currentState.totalStake, 0L)
    assertEquals(k.currentState.transaction("t-1").map(_.answer.status), Some("not_enough_money"))
  }

  // ── Cancels (US3) ────────────────────────────────────────────────────────────────────────────

  private def cancel(id: String, refs: String, closed: Vector[String], stake: Long, win: Long, movements: Vector[Movement]) =
    RecordTransaction(opening, ProviderTransaction(id, "cancel", 0, Some(refs), movements, ok(1000), at),
      closed = closed, stakeReturned = stake, winReturned = win)

  test("cancelling the last open bet closes the round as cancelled and publishes it") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    val r = k.call(Round.record)(cancel("x-1", "t-1", Vector("t-1"), 100, 0, Vector(Movement(Movement.Rollback, "x-1", Some("t-1"), 100, "w-x1"))))
    assertEquals(k.currentState.status, RoundState.Cancelled)
    val s = r.eventOfType[RoundEvent.RoundCancelled].summary
    assertEquals((s.outcome, s.stake, s.win), (Outcome.Cancelled, 0L, 0L))
  }

  test("cancelling one of two debits leaves the round open") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    k.call(Round.record)(debit("t-2", 100, 800))
    val r = k.call(Round.record)(cancel("x-1", "t-1", Vector("t-1"), 100, 0, Vector.empty))
    assertEquals(k.currentState.status, RoundState.Open)
    assert(!r.events.exists(_.isInstanceOf[RoundEvent.RoundCancelled]))
  }

  test("cancelling a credit on a cancelled round lowers the win and leaves the round cancelled") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    k.call(Round.record)(credit("c-1", 500, Vector("t-1"), Vector(Movement(Movement.Win, "c-1/t-1", Some("t-1"), 500, "w")), 1400))
    k.call(Round.record)(cancel("x-1", "t-1", Vector.empty, 100, 0, Vector(Movement(Movement.ProviderCredit, "x-1", None, 100, "w2"))))
    assertEquals(k.currentState.status, RoundState.Cancelled)
    val r = k.call(Round.record)(cancel("x-2", "c-1", Vector.empty, 0, 500, Vector(Movement(Movement.Bet, "x-2", Some("x-2"), 500, "w3"))))
    assertEquals(k.currentState.status, RoundState.Cancelled)
    assertEquals(k.currentState.win, 0L)
    assert(!r.events.exists(e => e.isInstanceOf[RoundEvent.RoundCancelled] || e.isInstanceOf[RoundEvent.RoundSettled]))
  }

  test("a transaction already cancelled is known as such") {
    val k = kit
    k.call(Round.record)(debit("t-1", 100, 900))
    k.call(Round.record)(cancel("x-1", "t-1", Vector("t-1"), 100, 0, Vector.empty))
    assert(k.currentState.cancelled("t-1"))
    assert(!k.currentState.cancelled("t-2"))
  }
