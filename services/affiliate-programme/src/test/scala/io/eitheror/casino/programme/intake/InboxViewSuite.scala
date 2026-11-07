package io.eitheror.casino.programme.intake


import java.time.Instant

class InboxViewSuite extends munit.FunSuite:
  test("a row is the fact's receipt") {
    val at  = Instant.parse("2026-11-01T23:59:59Z")
    val s   = InboxState("goldvibe", "f-1", "deposit", "topic", None, Some(Instant.parse("2026-11-01T00:00:00Z")),
      Some(at), Some("unattributed:no_attribution"), 2)
    assertEquals(InboxRow.of(s), InboxRow("goldvibe", "f-1", "deposit", "topic", "2026-11-01", Some("unattributed:no_attribution"), 2, Some(at)))
  }

  test("the inbox keeps the currency of a fact with an amount, for the usage report, and none of its body") {
    assertEquals(FactInbox.currencyOf("""{"kind":"deposit","currency" : "USD","amount":100}"""), Some("USD"))
    assertEquals(FactInbox.currencyOf("""{"kind":"registration","country":"GB"}"""), None)
    val s = InboxState("goldvibe", "f-2", "deposit", "webhook:goldvibe", Some("USD"), receivedAt = Some(Instant.parse("2026-11-01T10:00:00Z")))
    assertEquals(InboxRow.of(s).currency, Some("USD"))
  }
