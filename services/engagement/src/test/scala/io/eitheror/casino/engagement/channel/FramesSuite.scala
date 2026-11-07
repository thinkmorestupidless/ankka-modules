package io.eitheror.casino.engagement.channel

import com.thinkmorestupidless.ankka.http.SseEvent

import java.time.Instant

/** contracts/routes.md: each SSE frame's name and exact data; named events only, no `id:`, no comment lines. */
class FramesSuite extends munit.FunSuite:
  private val t = Instant.parse("2026-11-06T10:15:30.250Z")

  private def frame(e: SseEvent) = (e.name, e.data)

  test("ready: subjectId and serverTime"):
    assertEquals(frame(Frames.ready("player-1", t)),
      (Some("ready"), """{"subjectId":"player-1","serverTime":"2026-11-06T10:15:30.250Z"}"""))

  test("heartbeat: at"):
    assertEquals(frame(Frames.heartbeat(Instant.parse("2026-11-06T10:15:45Z"))),
      (Some("heartbeat"), """{"at":"2026-11-06T10:15:45Z"}"""))

  test("signal: inbox.changed under <slug>:<messageId>, a content-free hint"):
    assertEquals(frame(Frames.signal("spinvibe", "m-42")),
      (Some("signal"), """{"kind":"inbox.changed","correlationId":"spinvibe:m-42","hint":{}}"""))

  test("closing: reason"):
    Vector("revalidation_failed", "connection_recycled", "shutting_down").foreach { reason =>
      assertEquals(frame(Frames.closing(reason)), (Some("closing"), s"""{"reason":"$reason"}"""))
    }

  test("player_online: snake_case, as the original"):
    assertEquals(frame(Frames.playerOnline("p-1", "ali", "s-9", t)),
      (Some("player_online"),
        """{"player_id":"p-1","player_email_prefix":"ali","session_id":"s-9","occurred_at":"2026-11-06T10:15:30.250Z"}"""))

  test("player_online keeps an empty email prefix"):
    assertEquals(Frames.playerOnline("p-1", "", "s-9", t).data,
      """{"player_id":"p-1","player_email_prefix":"","session_id":"s-9","occurred_at":"2026-11-06T10:15:30.250Z"}""")

  test("ping: {}"):
    assertEquals(frame(Frames.ping()), (Some("ping"), "{}"))

  test("text that needs escaping stays one line of JSON"):
    assertEquals(Frames.closing("a\"b\nc").data, """{"reason":"a\"b\nc"}""")
