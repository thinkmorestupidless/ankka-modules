package io.eitheror.casino.support.conversation

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

/** data-model.md's `Conversation` commands: binding, another player, decided twice, ended and forgotten (T019). */
class ConversationSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-09T10:00:00Z")

  private def fresh = EventSourcedTestKit.of(Conversation, "s-1")

  private def started =
    val kit = fresh
    kit.call(Conversation.start)(Start("p-1", 3, at)): Unit
    kit

  test("the first turn binds the session to its player and version; again is the same, and persists nothing"):
    val kit = fresh
    val first = kit.call(Conversation.start)(Start("p-1", 3, at))
    assertEquals(first.events, Vector(ConversationEvent.Started("p-1", 3, at)))
    assertEquals((kit.currentState.playerId, kit.currentState.configVersion), ("p-1", 3))
    val again = kit.call(Conversation.start)(Start("p-1", 4, at.plusSeconds(5)))
    assertEquals((again.events, again.replyValue.refusal, kit.currentState.configVersion), (Vector.empty, None, 3))

  test("another player's session is session_not_yours, and stays bound to the first"):
    val kit = started
    val r   = kit.call(Conversation.start)(Start("p-2", 3, at))
    assertEquals((r.replyValue.refusal, r.events), (Some("session_not_yours"), Vector.empty))
    assertEquals(kit.currentState.playerId, "p-1")

  test("a turn counts its tokens; a request awaits; the decision opens it and names the approval for the tool"):
    val kit = started
    kit.call(Conversation.turned)(Turned(at, 400L)): Unit
    assertEquals((kit.currentState.turns, kit.currentState.tokensCounted), (1, 400L))
    kit.call(Conversation.requested)(Requested("ap-1", "apply_cool_off", Map("cool_off_until" -> "2026-12-01T00:00:00Z"), "…",
      at.plusSeconds(900), at)): Unit
    assertEquals(kit.currentState.awaiting, Some(("ap-1", "apply_cool_off")))
    val d = kit.call(Conversation.decided)(Decide("ap-1", approved = true, "player:p-1", expired = false, at))
    assertEquals(d.events, Vector(ConversationEvent.Decided("ap-1", "apply_cool_off", true, "player:p-1", false, at)))
    assertEquals((kit.currentState.status, kit.currentState.approved), (Status.Open, Some("ap-1")))
    kit.call(Conversation.toolCalled)(ToolCall("c-1", "apply_cool_off", Map.empty, Outcome.Ok, Some("ap-1"), None, at)): Unit
    assertEquals(kit.currentState.approved, None)

  test("decided twice is already_decided; an id not awaited is no_pending_action; a refusal names no approval"):
    val kit = started
    assertEquals(kit.call(Conversation.decided)(Decide("ap-1", true, "player:p-1", false, at)).replyValue.refusal, Some("no_pending_action"))
    kit.call(Conversation.requested)(Requested("ap-1", "apply_self_exclusion", Map.empty, "…", at.plusSeconds(900), at)): Unit
    assertEquals(kit.call(Conversation.decided)(Decide("ap-9", true, "player:p-1", false, at)).replyValue.refusal, Some("no_pending_action"))
    kit.call(Conversation.decided)(Decide("ap-1", false, "player:p-1", false, at)): Unit
    assertEquals(kit.currentState.approved, None)
    val twice = kit.call(Conversation.decided)(Decide("ap-1", true, "player:p-1", false, at))
    assertEquals((twice.replyValue.refusal, twice.events), (Some("already_decided"), Vector.empty))

  test("ended: an exclusion for good, a break until it passes"):
    val excluded = started
    excluded.call(Conversation.end)(End(EndReason.SelfExcluded, at)): Unit
    assert(excluded.currentState.endedAt(at.plusSeconds(86400 * 365)))
    val onBreak = started
    val until   = at.plusSeconds(86400)
    onBreak.call(Conversation.end)(End(EndReason.OnBreak(until), at)): Unit
    assertEquals(onBreak.currentState.breakUntil, Some(until))
    assert(onBreak.currentState.endedAt(at.plusSeconds(60)))
    assert(!onBreak.currentState.endedAt(until))

  test("forgotten once; the trail keeps its binding"):
    val kit = started
    assertEquals(kit.call(Conversation.forget)(At(at)).events, Vector(ConversationEvent.Forgotten(at)))
    assertEquals(kit.call(Conversation.forget)(At(at)).events, Vector.empty)
    assertEquals((kit.currentState.status, kit.currentState.playerId), (Status.Forgotten, "p-1"))

  test("every event moves the last activity"):
    val kit = started
    kit.call(Conversation.rejected)(Rejected("softening", Side.Output, retried = true, at.plusSeconds(7))): Unit
    assertEquals(kit.currentState.lastActivity, at.plusSeconds(7))
    kit.call(Conversation.handedOff)(At(at.plusSeconds(9))): Unit
    assertEquals(kit.currentState.lastActivity, at.plusSeconds(9))
