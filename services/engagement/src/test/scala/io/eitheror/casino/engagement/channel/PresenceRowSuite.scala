package io.eitheror.casino.engagement.channel

import io.eitheror.casino.engagement.channel.ChannelEvent.*
import io.eitheror.casino.engagement.reads.PresenceRow

import java.time.Instant

/** The presence row: online from the first open to the last close, and the instance each open connection is on (FR-027). */
class PresenceRowSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-08T10:00:00Z")

  private def fold(events: ChannelEvent*) = events.foldLeft(PresenceRow("p-1"))(PresenceRow.next)

  test("each open connection is counted on the instance holding it, until it closes or expires") {
    val two = fold(Opened("c-1", "i-a", 1, at), Opened("c-2", "i-b", 2, at.plusSeconds(1)))
    assertEquals((two.online, two.since, two.connections), (true, Some(at), Map("c-1" -> "i-a", "c-2" -> "i-b")))
    val one = PresenceRow.next(two, Closed("c-1", "client", 1, at.plusSeconds(2)))
    assertEquals((one.online, one.connections), (true, Map("c-2" -> "i-b")))
    val none = PresenceRow.next(one, Expired("c-2", at, 0, at.plusSeconds(3)))
    assertEquals(none, PresenceRow("p-1"))
  }

  test("other events change nothing") {
    val open = fold(Opened("c-1", "i-a", 1, at))
    assertEquals(PresenceRow.next(open, Leased("c-1", at.plusSeconds(1))), open)
    assertEquals(PresenceRow.next(open, Acked("c-1", 1, at.plusSeconds(1))), open)
  }
