package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.Json as AgentJson
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.config.ConfigBody

import scala.concurrent.duration.*

/**
 * The chat route (contracts/routes.md; T028, T037): the turn streamed as `delta`s then `done`, its first frame within
 * 5 s at the 95th percentile on the scripted provider (SC-002), and the refusals made before the stream opens.
 */
class ChatSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def post(player: String, body: String): SseReader =
    SseReader(kit.baseUrl + kit.chatPath("/run/chat_with_player"), body, kit.asPlayer(player))

  test("the first delta of a turn arrives within 5 s at the 95th percentile (SC-002)"):
    val times = (1 to 20).map { i =>
      kit.model.expectText(s"Your balance is £100.00, turn $i.")
      val s = kit.chat("p-1", s"chat-latency-$i", "what is my balance?")
      assertEquals(s.names().last, "done")
      s.firstFrameMillis.getOrElse(fail("no frame"))
    }.sorted
    val p95 = times((times.size * 95 + 99) / 100 - 1)
    assert(p95 < 5000L, s"p95 $p95 ms over ${times.mkString(",")}")

  test("a missing or empty field is 400 missing_fields, naming them, before any model call"):
    val none = post("p-1", "{}")
    assertEquals((none.status, none.refusal), (400, "missing_fields"))
    assertEquals(Json.parse(none.body).map(_.get("details").get("fields").asText), Some("message,session_id"))
    val empty = post("p-1", """{"message":"  ","session_id":"chat-empty"}""")
    assertEquals((empty.status, empty.refusal), (400, "missing_fields"))
    assertEquals(kit.model.callCount, 0)

  test("another player's session is 403 session_not_yours, and the session stays the first player's"):
    kit.model.expectText("Hello.")
    assertEquals(kit.chat("p-1", "chat-owned", "hello").names().last, "done")
    val other = kit.chat("p-2", "chat-owned", "hello")
    assertEquals((other.status, other.refusal), (403, "session_not_yours"))
    assertEquals(kit.conversation("chat-owned").playerId, "p-1")
    assertEquals(kit.model.callCount, 1)

  private def details(s: SseReader, f: String) = Json.parse(s.body).flatMap(n => Option(n.get("details")).map(_.get(f).asText))

  test("after an approved break, a turn is 409 conversation_ended naming its end, and the break's end lifts it (FR-017)"):
    val until = kit.clock.instant().plusSeconds(3600).toString
    kit.model.expectToolCall("apply_cool_off", AgentJson.obj("cool_off_until" -> AgentJson.str(until)), "call-b")
    assertEquals(kit.chat("p-1", "chat-break", "I need a break").names(), Vector("confirm_action", "done"))
    kit.model.expectText("Your break is set.")
    assertEquals(kit.resume("p-1", "chat-break", "approve").names().last, "done")
    val ended = kit.chat("p-1", "chat-break", "hello?")
    assertEquals((ended.status, ended.refusal), (409, "conversation_ended"))
    assertEquals(details(ended, "message"), Some(s"You are on a break until $until. This conversation is closed until then."))
    kit.clock.moveBy(2.hours)
    kit.model.expectText("Welcome back.")
    val back = kit.chat("p-1", "chat-break", "I'm back")
    assertEquals((back.status, back.names().last), (200, "done"))

  test("an approved self-exclusion closes with the closing message, and every later turn is 409 conversation_ended (FR-017)"):
    kit.model.expectToolCall("apply_self_exclusion", AgentJson.obj(), "call-x")
    assertEquals(kit.chat("p-1", "chat-excluded", "exclude me").names(), Vector("confirm_action", "done"))
    kit.model.expectText("You are now self-excluded.")
    val applied = kit.resume("p-1", "chat-excluded", "approve")
    assertEquals(applied.frames().head.field("action_name"), "apply_self_exclusion")
    assert(applied.text().endsWith(ConfigBody().closingMessage), applied.text())
    kit.clock.moveBy(3650.days)
    val ended = kit.chat("p-1", "chat-excluded", "hello?")
    assertEquals((ended.status, ended.refusal), (409, "conversation_ended"))
    assertEquals(details(ended, "message"), Some(ConfigBody().closingMessage))
