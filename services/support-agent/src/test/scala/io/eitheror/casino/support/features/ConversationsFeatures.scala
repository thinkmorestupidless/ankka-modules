package io.eitheror.casino.support.features

import com.thinkmorestupidless.ankka.agent.{SessionMemoryEntity, SessionMessage, forSessionMemory}
import com.thinkmorestupidless.ankka.core.SessionId
import io.eitheror.casino.support.agent.Motoko
import io.eitheror.casino.support.conversation.Status
import io.eitheror.casino.support.it.SseReader

import scala.concurrent.duration.*

/**
 * features/support-agent/conversations.feature (US4; T042). A conversation is ankka's session memory, sealed under
 * the player's key, beside the service's trail: what the model was given and what the memory holds are asserted,
 * never the model's prose. Retention is moved on the kit's clock; erasure is ankka 042's kit hook.
 */
class ConversationsFeatures extends SupportFeatures("conversations.feature"):

  private val Summary = "The player asked about their balance several times."
  private var other   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    other = ""

  private def turn(on: String, message: String, reply: String): Unit =
    kit.model.expectText(reply)
    assertEquals(kit.chat(player, on, message).names().last, "done")

  private def history(on: String) = kit.components.forSessionMemory(SessionId(on)).call(SessionMemoryEntity.history).invoke()

  private def lastSent: String = kit.model.requests.last.messages.map(_.toString).mkString("\n")

  // ── a restart ─────────────────────────────────────────────────────────────

  Given("a conversation of three turns") { () => (1 to 3).foreach(i => turn(session, s"question $i", s"answer $i")) }
  When("the support agent restarts and the player sends a fourth turn") { () =>
    kit.restartService()
    turn(session, "question 4", "answer 4")
  }
  Then("the model is given the three earlier turns") { () =>
    val sent = lastSent
    (1 to 3).foreach(i => assert(sent.contains(s"question $i") && sent.contains(s"answer $i"), sent))
  }

  // ── compaction ────────────────────────────────────────────────────────────

  Given("a conversation whose text passes the compaction size") { () =>
    kit.compactor.expectText(Summary)
    turn(session, "the long one", "x" * 100_001)
    (2 to 6).foreach(i => turn(session, s"question $i", s"answer $i"))
  }
  When("the next turn ends") { () => turn(session, "question 7", "answer 7") }
  Then("the oldest turns are replaced by a summary") { () =>
    val h = kit.eventually("the conversation was compacted") {
      Some(history(session)).filter(_.messages.headOption.exists(_.isInstanceOf[SessionMessage.SummaryMessage]))
    }
    assertEquals(h.messages.head.asInstanceOf[SessionMessage.SummaryMessage].text, Summary)
  }
  Then("the most recent ten are kept verbatim") { () =>
    val kept = history(session).messages.tail
    assertEquals(kept.size, 10)
    assert(kept.forall(m => !m.toString.contains("x" * 1000)), "the long reply was folded into the summary")
    (3 to 7).foreach(i => assert(kept.exists(_.toString.contains(s"answer $i")), s"answer $i was not kept"))
  }

  // ── retention ─────────────────────────────────────────────────────────────

  Given("a conversation untouched for the brand's retention") { () =>
    kit.configure(_.copy(retentionDays = 90)): Unit
    turn(session, "remember the word heron", "Noted.")
  }
  When("the retention lapses") { () => kit.clock.moveBy(91.days) }
  Then("the conversation is forgotten") { () =>
    kit.eventually("the conversation was forgotten")(Option(kit.conversation(session)).filter(_.status == Status.Forgotten))
    assertEquals(history(session).messages, Vector.empty)
  }
  Then("a new turn starts a new conversation") { () =>
    turn(session, "hello again", "Hello.")
    assert(!lastSent.contains("heron"), lastSent)
    val c = kit.conversation(session)
    assertEquals((c.status, c.turns), (Status.Open, 1))
  }

  // ── erasure ───────────────────────────────────────────────────────────────

  Given("the player is erased") { () =>
    other = s"$session-b"
    turn(session, "my word is kestrel", "noted")
    turn(other, "and here too, kestrel", "noted again")
  }
  When("erasure is applied") { () => kit.testKit.erase(Motoko.subject(player)): Unit }
  Then("every conversation of the player reads as erased") { () =>
    Seq(session, other).foreach { on =>
      kit.eventually(s"$on reads as erased")(Option(history(on)).filter(h => h.erased && h.messages.isEmpty))
    }
  }
  Then("a turn on any of them starts with no history") { () =>
    turn(other, "what was my word?", "I do not know.")
    assert(!lastSent.contains("kestrel"), lastSent)
    assertEquals(history(other).messages, Vector.empty)
  }

  // ── another page ──────────────────────────────────────────────────────────

  Given("a conversation of the player") { () => turn(session, "on the first page", "hello from the first page") }
  When("they open the chat on another page and continue") { () =>
    kit.model.expectText("still here")
    // A new reader is a new page: nothing is shared with the first but the session id.
    stream = SseReader(kit.baseUrl + kit.chatPath("/run/chat_with_player"),
      s"""{"message":"on another page","session_id":"$session"}""", kit.asPlayer(player))
    assertEquals(stream.names().last, "done")
  }
  Then("the same conversation continues under the same session id") { () =>
    assert(lastSent.contains("on the first page") && lastSent.contains("hello from the first page"), lastSent)
    assertEquals(kit.conversation(session).playerId, player)
    assertEquals(history(session).messages.size, 4)
  }
