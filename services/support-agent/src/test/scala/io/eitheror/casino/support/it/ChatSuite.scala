package io.eitheror.casino.support.it

import io.eitheror.casino.support.Json

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
