package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.{ModelResponse, TokenUsage}
import io.eitheror.casino.support.Json

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The operator's view of the conversations (T050; contracts/routes.md `GET /status`, `GET /conversations`): the
 * conversations view, read as the status's counts and as a player's list, newest activity first.
 */
class ConversationsSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def get(rest: String) = kit.request("GET", s"/v1/casino/agent-config/${kit.slug}$rest", headers = kit.asOperator())
  private def listed(player: String) =
    Json.parse(get(s"/conversations?player=$player").text).get.get("conversations").elements.asScala.toVector

  test("a player's conversations are listed newest first, with their turns and refusals, and no message text"):
    kit.model.expectText("one")
    assertEquals(kit.chat("p-list", "list-a", "hello").names().last, "done")
    kit.clock.moveBy(1.second)
    kit.model.expectText("two")
    assertEquals(kit.chat("p-list", "list-b", "hello again").names().last, "done")
    kit.chat("p-list", "list-b", "what about x@example.com?").names(): Unit
    val rows = kit.eventually("both conversations listed") {
      Option(listed("p-list")).filter(r => r.size == 2 && r.head.get("rejections").asInt == 1)
    }
    assertEquals(rows.map(_.get("session").asText), Vector("list-b", "list-a"))
    assertEquals(rows.map(r => (r.get("turns").asInt, r.get("status").asText)), Vector((1, "open"), (1, "open")))
    assert(!get("/conversations?player=p-list").text.contains("hello"), "a message's text is listed")
    assertEquals(listed("p-nobody"), Vector.empty)
    assertEquals(get("/conversations?offset=-1").status, 400)

  test("the status names whether the agent is on, the judged guardrails, the day's budget and the conversations"):
    kit.model.expect(ModelResponse("hi", usage = TokenUsage(inputTokens = 40, outputTokens = 2)))
    assertEquals(kit.chat("p-status", "status-a", "hello").names().last, "done")
    val s = kit.eventually("the conversation counted") {
      Json.parse(get("/status").text).filter(_.get("conversations").get("lastDay").asLong >= 1)
    }
    assertEquals((s.get("enabled").asBoolean, s.get("realMoney").asBoolean, s.get("judged").asText, s.get("safeguarding").asText),
      (true, false, "on", "off"))
    assert(s.get("budget").get("used").asLong >= 42, s.toString)
    assertEquals(s.get("budget").get("spent").asBoolean, false)
    assert(s.get("conversations").get("open").asLong >= 1, s.toString)
