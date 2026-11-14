package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.{SessionMemoryEntity, forSessionMemory}
import com.thinkmorestupidless.ankka.core.SessionId
import io.eitheror.casino.support.agent.Motoko

import scala.concurrent.duration.*

/**
 * A player's erasure (T041; FR-019, SC-004 at the kit's level), by ankka 042's kit hook: each of their sessions is
 * sealed under their key, so once the key is destroyed every one reads as empty and erased, a turn on any of them is
 * answered with no history and records nothing, and the trail kept beside them (`Conversation`) held no text of
 * theirs to erase.
 */
class ErasureSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def turn(player: String, session: String, message: String, reply: String): Unit =
    kit.model.expectText(reply)
    assertEquals(kit.chat(player, session, message).names().last, "done")

  private def history(session: String) =
    kit.components.forSessionMemory(SessionId(session)).call(SessionMemoryEntity.history).invoke()

  private def erased(session: String) =
    kit.eventually(s"$session reads as erased")(Option(history(session)).filter(h => h.erased && h.messages.isEmpty))

  test("every session of the erased player reads as erased, and another player's is untouched"):
    turn("p-er1", "er-a", "first chat", "hello")
    turn("p-er1", "er-b", "second chat", "hello again")
    turn("p-er2", "er-other", "someone else", "hi")
    kit.testKit.erase(Motoko.subject("p-er1")): Unit
    erased("er-a")
    erased("er-b")
    val other = history("er-other")
    assertEquals((other.erased, other.messages.size), (false, 2))

  test("a turn on an erased session is answered with no history and records nothing"):
    turn("p-er3", "er-turn", "my word is kestrel", "noted")
    kit.testKit.erase(Motoko.subject("p-er3")): Unit
    erased("er-turn")
    turn("p-er3", "er-turn", "what was my word?", "I do not know.")
    val sent = kit.model.requests.last.messages.map(_.toString).mkString("\n")
    assert(!sent.contains("kestrel"), sent)
    assertEquals(history("er-turn").messages, Vector.empty)

  test("the conversation's trail holds no text of the player's, so it has nothing to erase"):
    turn("p-er4", "er-trail", "my word is heronwing-4417", "heronwing-4417 is noted")
    kit.testKit.assertNoPersonalValueIn(kit.broker)("heronwing-4417")
    kit.testKit.erase(Motoko.subject("p-er4")): Unit
    erased("er-trail")
    val c = kit.conversation("er-trail")
    assertEquals((c.playerId, c.turns), ("p-er4", 1))
    kit.testKit.assertNoPersonalValueIn(kit.broker)("heronwing-4417")
