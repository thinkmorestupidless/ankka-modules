package io.eitheror.casino.programme.intake


import java.time.Instant

class InboxViewSuite extends munit.FunSuite:
  test("a row is the fact's receipt without its body") {
    val s   = InboxState("goldvibe", "f-1", "deposit", "topic", "{\"secret\":1}", Some(Instant.parse("2026-11-01T00:00:00Z")),
      Some(Instant.parse("2026-11-01T23:59:59Z")), Some("unattributed:no_attribution"), 2)
    assertEquals(InboxRow.of(s), InboxRow("goldvibe", "f-1", "deposit", "topic", "2026-11-01", Some("unattributed:no_attribution"), 2))
  }
