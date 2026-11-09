package io.eitheror.casino.support

import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.support.config.*
import io.eitheror.casino.support.conversation.*
import io.eitheror.casino.support.limits.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** Every stored type survives its codec, under data-model.md's manifests (T014). */
class CodecsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-09T10:00:00Z")

  private def roundTrip[A](s: Serializer[A], a: A): Unit = assertEquals(s.fromBytes(s.toBytes(a)), a)

  test("every conversation event, under its own name"):
    val events = Vector[ConversationEvent](
      ConversationEvent.Started("p-1", 3, at),
      ConversationEvent.TurnTaken(at, 10L, 250L),
      ConversationEvent.ToolCalled("c-1", "request_withdrawal", Map("amount" -> "5000", "currency" -> "GBP"),
        Outcome.Refused("KEY_REUSED"), Some("KEY_REUSED: the key was used"), Some("ap-1"), at),
      ConversationEvent.ToolCalled("c-2", "get_wallet_balance", Map.empty, Outcome.TimedOut, None, None, at),
      ConversationEvent.ApprovalRequested("ap-1", "apply_cool_off", Map("cool_off_until" -> "2026-12-01T00:00:00Z"),
        "Apply a cool-off period until 2026-12-01T00:00:00Z.", at.plusSeconds(900), at),
      ConversationEvent.Decided("ap-1", "apply_cool_off", approved = true, "player:p-1", expired = false, at),
      ConversationEvent.GuardrailRejected("softening", Side.Output, retried = true, at),
      ConversationEvent.HandedOff(at),
      ConversationEvent.Ended(EndReason.OnBreak(at.plusSeconds(86400)), at),
      ConversationEvent.Ended(EndReason.SelfExcluded, at),
      ConversationEvent.Forgotten(at)
    )
    events.foreach(roundTrip(Stored.conversationEvent, _))
    val names = events.map(e => String(Stored.conversationEvent.toBytes(e), UTF_8)).map(_.split("\"type\":\"")(1).takeWhile(_ != '"'))
    assertEquals(names.distinct, Vector("started", "turn", "tool", "requested", "decided", "rejected", "handed-off", "ended", "forgotten"))
    assertEquals(Stored.conversationEvent.manifest, "support.conversation.event")

  test("the states"):
    roundTrip(Stored.conversation, ConversationState("p-1", 2, Status.Awaiting("ap-1", "apply_self_exclusion"),
      Some(at), at, 1200L, 4, Some("ap-0"), Some(at)))
    roundTrip(Stored.conversation, ConversationState("p-1", status = Status.Ended(EndReason.SelfExcluded)))
    roundTrip(Stored.conversation, ConversationState())
    val body = ConfigBody(enabled = true, safeguardingMessage = Some("Please talk to someone."), helpline = Some("0808 8020 133"),
      personalFields = Set("firstName"), dailyTokens = 5L)
    roundTrip(Stored.configuration, AgentConfigState(4, body, Vector(ConfigNote(4, "op-1", Vector("enabled"), Map("enabled" -> "true"), at))))
    roundTrip(Stored.configVersion, VersionState(Some(ConfigBody())))
    roundTrip(Stored.turnWindow, TurnWindowState(Vector(at, at.plusSeconds(3))))
    roundTrip(Stored.budget, BudgetState(300L, Map("s-1" -> 300L)))

  test("each manifest is named under support."):
    assertEquals(Vector(Stored.conversation, Stored.configuration, Stored.configVersion, Stored.turnWindow, Stored.budget).map(_.manifest),
      Vector("support.conversation", "support.configuration", "support.config-version", "support.turn-window", "support.budget"))
