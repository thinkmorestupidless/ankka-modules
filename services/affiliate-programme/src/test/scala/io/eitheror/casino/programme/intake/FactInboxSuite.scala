package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class FactInboxSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T10:00:00Z")
  private def claim(channel: String) = ClaimFact("goldvibe", "f-1", "deposit", channel, "{}", at.minusSeconds(5), at)

  test("claimed once; after that, on any channel, a duplicate counted") {
    val k = KeyValueEntityTestKit.of(FactInbox, "goldvibe/f-1")
    assert(k.call(FactInbox.claim)(claim("topic")).replyValue.isNew)
    val again = k.call(FactInbox.claim)(claim("webhook")).replyValue
    assertEquals((again.outcome, again.state.channel, again.state.duplicates), ("duplicate", "topic", 1))
  }

  test("the outcome is kept") {
    val k = KeyValueEntityTestKit.of(FactInbox, "goldvibe/f-1")
    k.call(FactInbox.claim)(claim("topic")): Unit
    assertEquals(k.call(FactInbox.outcome)("recorded").replyValue.outcome, Some("recorded"))
    assert(!k.call(FactInbox.outcome)("recorded").changed)
  }
