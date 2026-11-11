package io.eitheror.casino.support.reads

import io.eitheror.casino.support.conversation.{ConversationEvent, EndReason, Outcome, Side}
import io.eitheror.casino.support.conversation.ConversationEvent.*

import java.time.Instant

/** The conversations view's row (T050; data-model.md `ConversationsByPlayer`), moved as the conversation's state is. */
class ConversationRowSuite extends munit.FunSuite:
  private val t0 = Instant.parse("2026-11-11T10:00:00Z")
  private def at(s: Int) = t0.plusSeconds(s)
  private def fold(es: ConversationEvent*) = es.foldLeft(ConversationRow("s-1"))(ConversationRow.next)

  test("a conversation's turns, hand-offs and refusals are counted, and it is open, then awaiting, then open"):
    val started = fold(Started("p-1", 2, at(0)), TurnTaken(at(1), 0, 10), HandedOff(at(1)),
      GuardrailRejected("other-player", Side.Input, retried = false, at(2)))
    assertEquals(started, ConversationRow("s-1", "p-1", ConversationRow.Open, Some(at(0)), at(2), turns = 1, handedOff = 1, rejections = 1))
    val awaiting = ConversationRow.next(started, ApprovalRequested("a-1", "apply_cool_off", Map.empty, "", at(9), at(3)))
    assertEquals(awaiting.status, ConversationRow.Awaiting)
    assertEquals(ConversationRow.next(awaiting, Decided("a-1", "apply_cool_off", approved = true, "player:p-1", expired = false, at(4))).status,
      ConversationRow.Open)

  test("an ended conversation stays ended when forgotten; an open one is forgotten and its next start is a new row"):
    val excluded = fold(Started("p-1", 1, at(0)), Ended(EndReason.SelfExcluded, at(1)), Forgotten(at(2)))
    assertEquals(excluded.status, ConversationRow.Excluded)
    assertEquals(fold(Started("p-1", 1, at(0)), Ended(EndReason.OnBreak(at(99)), at(1))).status, ConversationRow.OnBreak)
    val forgotten = fold(Started("p-1", 1, at(0)), TurnTaken(at(1), 0, 5), Forgotten(at(2)))
    assertEquals(forgotten.status, ConversationRow.Forgotten)
    assertEquals(ConversationRow.next(forgotten, Started("p-1", 3, at(5))), ConversationRow("s-1", "p-1", ConversationRow.Open, Some(at(5)), at(5)))

  test("a tool's call moves only the activity"):
    val row = fold(Started("p-1", 1, at(0)))
    assertEquals(ConversationRow.next(row, ToolCalled("c-1", "get_wallet_balance", Map.empty, Outcome.Ok, None, None, at(7))),
      row.copy(lastActivity = at(7)))
