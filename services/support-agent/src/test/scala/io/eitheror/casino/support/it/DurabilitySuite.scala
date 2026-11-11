package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.{ModelChunk, ModelProvider, ModelRequest, ModelResponse, SessionMemoryEntity,
  SessionMessage, TestModelProvider, Json as AgentJson, forSessionMemory}
import com.thinkmorestupidless.ankka.core.SessionId
import io.eitheror.casino.support.SupportRuntime
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * A conversation outlives the service and the page (T039; FR-026, FR-005): its history is ankka's session memory, so
 * a restart keeps it and an awaited request; another page on the same session id continues it; past the compaction
 * size its oldest messages become a summary and the last ten stay verbatim; a stream the page drops mid-turn still
 * completes in the session, and the next turn's model is given that reply.
 */
class DurabilitySuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def turn(session: String, message: String, reply: String): Unit =
    kit.model.expectText(reply)
    assertEquals(kit.chat("p-1", session, message).names().last, "done")

  private def history(session: String) =
    kit.components.forSessionMemory(SessionId(session)).call(SessionMemoryEntity.history).invoke()

  /** What the model was given last, as text. */
  private def lastSent: String = kit.model.requests.last.messages.map(_.toString).mkString("\n")

  test("a restart keeps the history: the fourth turn's model is given the three before it"):
    (1 to 3).foreach(i => turn("dur-restart", s"question $i", s"answer $i"))
    kit.restartService()
    turn("dur-restart", "question 4", "answer 4")
    val sent = lastSent
    (1 to 3).foreach(i => assert(sent.contains(s"question $i") && sent.contains(s"answer $i"), sent))

  test("a restart keeps an awaited request, and it can still be decided"):
    kit.model.expectToolCall("request_withdrawal",
      AgentJson.obj("amount" -> AgentJson.num(5000d), "currency" -> AgentJson.str("GBP")), "call-w")
    assertEquals(kit.chat("p-1", "dur-await", "withdraw £50").names(), Vector("confirm_action", "done"))
    kit.restartService()
    assert(kit.conversation("dur-await").awaiting.isDefined)
    kit.model.expectText("Submitted.")
    val r = kit.resume("p-1", "dur-await", "approve")
    assertEquals(r.frames().head.field("success"), "true")
    assertEquals(kit.payouts.calls.count(_.path.endsWith("/withdrawal-requests")), 1)

  test("the same session id from another page continues the same conversation"):
    turn("dur-page", "on the first page", "hello from the first page")
    // A new reader is a new page: nothing is shared with the first but the session id.
    kit.model.expectText("still here")
    val other = SseReader(kit.baseUrl + kit.chatPath("/run/chat_with_player"),
      """{"message":"on another page","session_id":"dur-page"}""", kit.asPlayer("p-1"))
    assertEquals(other.names().last, "done")
    assert(lastSent.contains("on the first page") && lastSent.contains("hello from the first page"), lastSent)
    assertEquals(kit.conversation("dur-page").playerId, "p-1")

  test("past the compaction size the oldest messages become one summary and the last ten stay verbatim (FR-005)"):
    kit.compactor.expectText("The player asked about their balance several times.")
    turn("dur-compact", "the long one", "x" * 100_001)
    // Fourteen messages: four past the ten kept, ankka's least worth summarising (minMessagesToCompact).
    (2 to 7).foreach(i => turn("dur-compact", s"question $i", s"answer $i"))
    val compacted = kit.eventually("the session was compacted") {
      Some(history("dur-compact")).filter(_.messages.headOption.exists(_.isInstanceOf[SessionMessage.SummaryMessage]))
    }
    assertEquals(compacted.messages.size, 11)
    assertEquals(compacted.messages.head.asInstanceOf[SessionMessage.SummaryMessage].text,
      "The player asked about their balance several times.")
    assert(compacted.messages.tail.forall(m => !m.toString.contains("x" * 1000)), "the long reply was folded into the summary")
    assert(compacted.messages.last.toString.contains("answer 7"), compacted.messages.last)

  test("a stream the page drops mid-turn completes in the session, and the next turn is given that reply"):
    val slow = Slow(kit.model, 300.millis)
    SupportRuntime.model = _ => slow
    try
      kit.model.expectText("one two three four five six seven eight nine ten")
      val s = kit.chat("p-1", "dur-cut", "count to ten")
      kit.eventually("the first words arrived")(Option(s.status).filter(_ == 200))
      Thread.sleep(700)
      s.cancel()
      kit.eventually("the turn completed in the session") {
        Some(history("dur-cut")).filter(_.messages.exists(_.toString.contains("nine ten")))
      }
      val calls = kit.model.callCount
      SupportRuntime.model = _ => kit.model
      turn("dur-cut", "did you finish?", "yes")
      assertEquals(kit.model.callCount, calls + 1, "the cut turn was not run again")
      assert(lastSent.contains("one two three four five six seven eight nine ten"), lastSent)
    finally SupportRuntime.model = _ => kit.model

/** The scripted model, its chunks spaced out, so a page can leave mid-turn. */
final class Slow(inner: TestModelProvider, perChunk: FiniteDuration) extends ModelProvider:
  def name: String                                          = inner.name
  def modelName: String                                     = inner.modelName
  def complete(request: ModelRequest): Future[ModelResponse] = inner.complete(request)
  override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] = inner.stream(request).throttle(1, perChunk)
