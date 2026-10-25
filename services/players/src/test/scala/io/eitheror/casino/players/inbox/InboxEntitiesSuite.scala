package io.eitheror.casino.players.inbox

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/** The message and note entities (T013). */
class InboxEntitiesSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-25T10:00:00Z")

  private def written =
    val kit = EventSourcedTestKit.of(MessageEntity, "m-1")
    kit.call(MessageEntity.write)(CreateMessage("p-1", "bonus", MessageOrigin.Operator, "Hello Ada", "Your bonus", Some("op-1"), at)): Unit
    kit

  test("Created under an id twice answers the first") {
    val kit    = written
    val second = kit.call(MessageEntity.write)(CreateMessage("p-2", "other", MessageOrigin.Crm, "x", "y", None, at.plusSeconds(1)))
    assert(!second.persisted)
    assertEquals((second.replyValue.state.playerId, second.replyValue.state.body.reveal), ("p-1", "Your bonus"))
  }

  test("Read, Deleted and Delivered each once; a deleted message keeps its body") {
    val kit = written
    Seq(MessageEntity.read, MessageEntity.delete, MessageEntity.delivered).foreach { handle =>
      assert(kit.call(handle)(MessageAt(at.plusSeconds(5))).persisted)
      assert(!kit.call(handle)(MessageAt(at.plusSeconds(6))).persisted)
    }
    val s = kit.currentState
    assertEquals((s.readAt, s.deletedByPlayerAt, s.delivered), (Some(at.plusSeconds(5)), Some(at.plusSeconds(5)), true))
    assertEquals(s.body.reveal, "Your bonus")
  }

  test("a message is erased to empty text, once") {
    val kit = written
    assert(kit.call(MessageEntity.erase)(MessageAt(at)).persisted)
    assertEquals((kit.currentState.subject.reveal, kit.currentState.body.reveal), ("", ""))
    assert(!kit.call(MessageEntity.erase)(MessageAt(at)).persisted)
  }

  test("a note is written once, pinned and unpinned, and its text is Personal") {
    val kit = KeyValueEntityTestKit.of(NoteEntity, "n-1")
    kit.call(NoteEntity.write)(WriteNote("p-1", "called about limits", "op-1", at)): Unit
    assertEquals(kit.call(NoteEntity.write)(WriteNote("p-1", "other", "op-2", at)).replyValue.text.reveal, "called about limits")
    assert(kit.call(NoteEntity.pin)(PinNote(true)).replyValue.pinned)
    assert(!kit.call(NoteEntity.pin)(PinNote(false)).replyValue.pinned)
    val text: Personal[String] = kit.currentState.text
    assertEquals(text.reveal, "called about limits")
    assertEquals(kit.call(NoteEntity.erase).replyValue.text.reveal, "")
    assertEquals(kit.currentState.author, "op-1")
  }
