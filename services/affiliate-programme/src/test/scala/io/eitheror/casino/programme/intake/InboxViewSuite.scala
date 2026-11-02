package io.eitheror.casino.programme.intake


import java.time.Instant

class InboxViewSuite extends munit.FunSuite:
  test("a row is the fact's receipt without its body") {
    val at  = Instant.parse("2026-11-01T23:59:59Z")
    val s   = InboxState("goldvibe", "f-1", "deposit", "topic", "{\"secret\":1}", Some(Instant.parse("2026-11-01T00:00:00Z")),
      Some(at), Some("unattributed:no_attribution"), 2)
    assertEquals(InboxRow.of(s), InboxRow("goldvibe", "f-1", "deposit", "topic", "2026-11-01", Some("unattributed:no_attribution"), 2, Some(at)))
  }

  test("a row keeps the currency of a fact with an amount, for the usage report") {
    val s = InboxState("goldvibe", "f-2", "deposit", "webhook:goldvibe", """{"kind":"deposit","currency" : "USD","amount":100}""",
      receivedAt = Some(Instant.parse("2026-11-01T10:00:00Z")))
    assertEquals(InboxRow.of(s).currency, Some("USD"))
    assertEquals(InboxRow.of(s.copy(fact = """{"kind":"registration"}""")).currency, None)
  }
