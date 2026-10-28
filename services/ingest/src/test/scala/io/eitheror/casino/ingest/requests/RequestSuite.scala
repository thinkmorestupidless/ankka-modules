package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.ingest.{IngestRuntime, Settings}

import java.time.{Duration, Instant}

/** data-model.md's command table for `Request`, row by row (FR-010, FR-013, FR-014; research R5). */
class RequestSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-22T10:00:00Z")
  private val a  = Answer(200, """{"session_token":"a"}""")
  private val b  = Answer(200, """{"session_token":"b"}""")

  override def beforeAll(): Unit = IngestRuntime.wire(Settings())

  private def begin(kit: KeyValueEntityTestKit[Request, RequestState], d: String, r: String, when: Instant = at) =
    kit.call(Request.begin)(Begin(d, r, when))

  test("every row of the table, in order") {
    val kit = KeyValueEntityTestKit.of(Request, "sb-1/t-1")
    assertEquals(begin(kit, "d1", "r1").replyValue, Begun(Begun.Forward, 1))
    assertEquals(kit.currentState.state, RequestState.InFlight)
    val inFlight = begin(kit, "d1", "r2")
    assertEquals((inFlight.replyValue, inFlight.changed), (Begun(Begun.InFlight, 1), false))
    val other = begin(kit, "d2", "r3")
    assertEquals((other.replyValue, other.changed), (Begun(Begun.Mismatch, 1), false))
    assertEquals(kit.call(Request.answer)(Settle(1, at, Some(a))).replyValue, Settled(true))
    assertEquals((kit.currentState.state, kit.currentState.answer), (RequestState.Answered, Some(a)))
    assertEquals(begin(kit, "d1", "r4").replyValue, Begun(Begun.Answered, 1, Some(a)))
    assertEquals(begin(kit, "d2", "r5").replyValue, Begun(Begun.Mismatch, 1))
    assertEquals(kit.call(Request.answer)(Settle(1, at, Some(b))).replyValue, Settled(false))
    assertEquals(kit.currentState.answer, Some(a))
  }

  test("an unanswered attempt frees the id; the next is attempt 2, and attempt 1's late answer is refused") {
    val kit = KeyValueEntityTestKit.of(Request, "sb-1/t-2")
    begin(kit, "d1", "r1"): Unit
    assertEquals(kit.call(Request.unanswered)(Settle(1, at)).replyValue, Settled(true))
    assertEquals((kit.currentState.state, kit.currentState.attempt), (RequestState.Free, 1))
    assertEquals(begin(kit, "d1", "r6").replyValue, Begun(Begun.Forward, 2))
    assertEquals(kit.call(Request.answer)(Settle(1, at, Some(b))).replyValue, Settled(false))
    assertEquals(kit.call(Request.answer)(Settle(2, at, Some(b))).replyValue, Settled(true))
    assertEquals(kit.currentState.recordIds, Vector("r1", "r6"))
  }

  test("a refused attempt frees the id (R5), and a corrected body is forwarded") {
    val kit = KeyValueEntityTestKit.of(Request, "sb-1/t-3")
    begin(kit, "d1", "r1"): Unit
    assertEquals(kit.call(Request.refused)(Settle(1, at)).replyValue, Settled(true))
    assertEquals(kit.currentState.state, RequestState.Free)
    assertEquals(begin(kit, "d2", "r2").replyValue, Begun(Begun.Forward, 2))
  }

  test("an answered id past its retention on the clock is a fresh one") {
    val kit = KeyValueEntityTestKit.of(Request, "sb-1/t-4")
    begin(kit, "d1", "r1"): Unit
    kit.call(Request.answer)(Settle(1, at, Some(a))): Unit
    assertEquals(begin(kit, "d1", "r2", at.plus(Duration.ofHours(23))).replyValue.kind, Begun.Answered)
    assertEquals(begin(kit, "d1", "r3", at.plus(Duration.ofHours(24))).replyValue, Begun(Begun.Forward, 1))
  }

  test("the entity keeps the id for the retention it is built with") {
    IngestRuntime.wire(Settings(requestIdRetention = Duration.ofHours(2)))
    try
      val r = begin(KeyValueEntityTestKit.of(Request, "sb-1/t-5"), "d1", "r1")
      assert(r.retention.isDefined, "the request id expires")
      assert(r.retention.get.toString.contains("2 hours") || r.retention.get.toString.contains("7200"), r.retention.get.toString)
    finally IngestRuntime.wire(Settings())
  }
