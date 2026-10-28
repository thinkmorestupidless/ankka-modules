package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import java.time.Instant

/** FR-016 to FR-018: one record per request, written once; a lost answer on an unanswered one; nothing personal. */
class RequestRecordSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-22T10:00:00Z")
  private val record = RecordState("ignored", Some("sb-1"), Route.Create, Some(at), Some("t-1"), Some(5000), Some("EUR"), Some("GB"),
    Outcome.Unanswered, None, None, None, Some(1))

  test("a write records every field under the entity's id, and a second write is refused") {
    val kit = KeyValueEntityTestKit.of(RequestRecord, "r-1")
    val first = kit.call(RequestRecord.write)(record)
    assertEquals(first.replyValue, record.copy(recordId = "r-1"))
    assert(first.retention.isDefined, "the record expires")
    assertEquals(kit.call(RequestRecord.write)(record.copy(outcome = Outcome.Served)).error.code, ErrorCode.Conflict)
  }

  test("a lost answer on an unanswered record makes it lost_answer, not seen; on any other outcome it is refused") {
    val kit = KeyValueEntityTestKit.of(RequestRecord, "r-2")
    kit.call(RequestRecord.write)(record): Unit
    val s = kit.call(RequestRecord.lostAnswer)(LostAnswer(at.plusSeconds(3), Some("tok"), None)).replyValue
    assertEquals((s.outcome, s.seen, s.lostAnswer), (Outcome.LostAnswer, false, Some(LostAnswer(at.plusSeconds(3), Some("tok"), None))))
    assertEquals(kit.call(RequestRecord.lostAnswer)(LostAnswer(at, None, None)).error.code, ErrorCode.Conflict)
    val served = KeyValueEntityTestKit.of(RequestRecord, "r-3")
    served.call(RequestRecord.write)(record.copy(outcome = Outcome.Served)): Unit
    assertEquals(served.call(RequestRecord.lostAnswer)(LostAnswer(at, None, None)).error.code, ErrorCode.Conflict)
  }

  test("markSeen sets seen on a lost answer; refuses another outcome (409) and no record (404)") {
    val kit = KeyValueEntityTestKit.of(RequestRecord, "r-4")
    kit.call(RequestRecord.write)(record): Unit
    assertEquals(kit.call(RequestRecord.markSeen).error.code, ErrorCode.Conflict)
    kit.call(RequestRecord.lostAnswer)(LostAnswer(at, Some("tok"))): Unit
    assertEquals(kit.call(RequestRecord.markSeen).replyValue, true)
    assertEquals(kit.currentState.seen, true)
    assertEquals(KeyValueEntityTestKit.of(RequestRecord, "none").call(RequestRecord.markSeen).error.code, ErrorCode.NotFound)
  }

  test("the record's fields are exactly data-model.md's: no customer field, no address, no key (SC-006)") {
    val fields = classOf[RecordState].getDeclaredFields.map(_.getName).filterNot(_.startsWith("$")).toVector
    assertEquals(fields.sorted, RecordState.Fields.sorted)
    Seq("customerEmail", "clientIp", "merchantKey", "customer", "ip", "key").foreach(f => assert(!fields.contains(f), f))
  }
