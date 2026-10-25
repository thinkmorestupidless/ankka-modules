package io.eitheror.casino.ingest.keys

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant

/** FR-002, FR-004: the key index's entry, which a late issue cannot revive once deactivated. */
class KeySuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-22T10:00:00Z")

  test("an issue sets the digest, the sub-brand, active and since; a repeat changes nothing") {
    val kit = KeyValueEntityTestKit.of(Key, "k-1")
    assertEquals(kit.call(Key.issue)(Issue("d1", "sb-1", at)).replyValue, KeyState("d1", "sb-1", active = true, since = Some(at)))
    val again = kit.call(Key.issue)(Issue("d1", "sb-1", at.plusSeconds(5)))
    assert(!again.changed)
    assertEquals(again.replyValue.since, Some(at))
  }

  test("a deactivation sets it inactive, and a later issue leaves it so") {
    val kit = KeyValueEntityTestKit.of(Key, "k-1")
    kit.call(Key.issue)(Issue("d1", "sb-1", at)): Unit
    assertEquals(kit.call(Key.deactivate)(Deactivate(at.plusSeconds(1))).replyValue.active, false)
    val revived = kit.call(Key.issue)(Issue("d1", "sb-1", at.plusSeconds(2)))
    assert(!revived.changed)
    assertEquals(kit.call(Key.read).replyValue.active, false)
  }

  test("a deactivation on an empty entity is recorded with no digest, so a later issue cannot revive it") {
    val kit = KeyValueEntityTestKit.of(Key, "k-2")
    val s   = kit.call(Key.deactivate)(Deactivate(at)).replyValue
    assertEquals((s.digest, s.active, s.deactivated), ("", false, true))
    kit.call(Key.issue)(Issue("d2", "sb-2", at.plusSeconds(1))): Unit
    assertEquals(kit.call(Key.read).replyValue.active, false)
  }
