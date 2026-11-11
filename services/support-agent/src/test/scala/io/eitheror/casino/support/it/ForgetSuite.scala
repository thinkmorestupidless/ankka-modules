package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.{SessionMemoryEntity, forSessionMemory}
import com.thinkmorestupidless.ankka.core.{EntityId, SessionId}
import io.eitheror.casino.support.conversation.{At, Conversation, End, EndReason, Status}

import scala.concurrent.duration.*

/**
 * The forget timer (T040; FR-027, data-model.md *Timers*), on the kit's moved clock: a conversation untouched for the
 * brand's retention has its memory cleared and is recorded as forgotten; one touched since is kept and its timer set
 * again; the next turn on a forgotten one starts a new conversation; and an end outlives the forgetting.
 */
class ForgetSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(_.copy(retentionDays = 90)): Unit

  private def turn(session: String, message: String, reply: String): Unit =
    kit.model.expectText(reply)
    assertEquals(kit.chat("p-1", session, message).names().last, "done")

  private def history(session: String) =
    kit.components.forSessionMemory(SessionId(session)).call(SessionMemoryEntity.history).invoke()

  private def forgotten(session: String) =
    kit.eventually(s"$session was forgotten")(Option(kit.conversation(session)).filter(_.status == Status.Forgotten))

  /** Long enough for the timers, polled every 200 ms, to have fired anything due. */
  private def settle(): Unit = Thread.sleep(1200)

  test("a conversation untouched for the retention has its memory cleared and is recorded as forgotten"):
    turn("fg-lapse", "my balance?", "It is 50.00.")
    kit.clock.moveBy(89.days)
    settle()
    assertEquals(kit.conversation("fg-lapse").status, Status.Open)
    kit.clock.moveBy(2.days)
    forgotten("fg-lapse")
    assertEquals(history("fg-lapse").messages, Vector.empty)

  test("each turn sets the timer again from that turn"):
    turn("fg-touched", "first", "one")
    kit.clock.moveBy(60.days)
    turn("fg-touched", "second", "two")
    kit.clock.moveBy(31.days)
    settle()
    assertEquals(kit.conversation("fg-touched").status, Status.Open)
    assertEquals(history("fg-touched").messages.size, 4)
    kit.clock.moveBy(60.days)
    forgotten("fg-touched")

  test("a timer that fires on a conversation touched since sets itself again at the new due"):
    turn("fg-again", "first", "one")
    kit.clock.moveBy(60.days)
    // Touched without a turn's end (a stream cut before it): the timer is still at the first turn's due.
    kit.components.forEventSourcedEntity(EntityId("fg-again")).call(Conversation.handedOff).invoke(At(kit.clock.instant())): Unit
    kit.clock.moveBy(31.days)
    settle()
    assertEquals(kit.conversation("fg-again").status, Status.Open)
    kit.clock.moveBy(60.days)
    forgotten("fg-again")

  test("the next turn on a forgotten conversation starts a new one, given nothing of the old"):
    turn("fg-new", "remember the word heron", "Noted.")
    kit.clock.moveBy(91.days)
    forgotten("fg-new")
    turn("fg-new", "hello again", "Hello.")
    val sent = kit.model.requests.last.messages.map(_.toString).mkString("\n")
    assert(!sent.contains("heron"), sent)
    val c = kit.conversation("fg-new")
    assertEquals((c.status, c.turns, c.startedAt), (Status.Open, 1, Some(c.lastActivity)))

  test("forgetting clears an excluded conversation's memory and it stays closed"):
    turn("fg-ended", "exclude me", "Done.")
    kit.components.forEventSourcedEntity(EntityId("fg-ended")).call(Conversation.end)
      .invoke(End(EndReason.SelfExcluded, kit.clock.instant())): Unit
    kit.clock.moveBy(91.days)
    kit.eventually("the memory was cleared")(Option(history("fg-ended")).filter(_.messages.isEmpty))
    assertEquals(kit.conversation("fg-ended").status, Status.Ended(EndReason.SelfExcluded))
    assertEquals(kit.chat("p-1", "fg-ended", "hello?").refusal, "conversation_ended")
