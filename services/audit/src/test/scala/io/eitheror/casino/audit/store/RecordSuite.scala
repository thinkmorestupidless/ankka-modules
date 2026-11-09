package io.eitheror.casino.audit.store

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.audit.stored.Stored

import java.time.Instant

class RecordSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-09T10:00:00Z")
  private val stored = Stored("brand:goldvibe/d-1", "brand:goldvibe", "goldvibe", "brand", "wallet", "wallet.adjust", "done",
    None, "money", at, "operator", "ps-1", "player", "ps-2", None, Vector.empty, Some(100L), Some("EUR"), "d-2", "d-3", "d-1", at)

  test("kept once; the same record again, redelivered later, changes nothing (FR-010)") {
    val k = KeyValueEntityTestKit.of(Record, stored.key)
    assert(k.call(Record.keep)(stored).replyValue.isNew)
    val again = k.call(Record.keep)(stored.copy(arrivedAt = at.plusSeconds(60)))
    assertEquals(again.replyValue.outcome, "same")
    assert(!again.changed)
    assertEquals(k.call(Record.get).replyValue.stored, Some(stored))
  }

  test("another record under the same key is a collision and is never written") {
    val k = KeyValueEntityTestKit.of(Record, stored.key)
    k.call(Record.keep)(stored): Unit
    val other = k.call(Record.keep)(stored.copy(outcome = "refused"))
    assertEquals(other.replyValue.outcome, "collision")
    assertEquals(k.call(Record.get).replyValue.stored.map(_.outcome), Some("done"))
  }

  test("only drop deletes, and dropping nothing is answered absent") {
    val k = KeyValueEntityTestKit.of(Record, stored.key)
    assertEquals(k.call(Record.drop)("sweep-1").replyValue.outcome, "absent")
    k.call(Record.keep)(stored): Unit
    assertEquals(k.call(Record.drop)("sweep-1").replyValue.outcome, "dropped")
    assertEquals(k.call(Record.get).replyValue.stored, None)
  }
