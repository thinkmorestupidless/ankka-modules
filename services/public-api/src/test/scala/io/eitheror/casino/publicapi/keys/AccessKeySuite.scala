package io.eitheror.casino.publicapi.keys

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.publicapi.keys.KeyEvent.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

class AccessKeySuite extends munit.FunSuite:
  private val at        = Instant.parse("2026-11-12T08:00:00Z")
  private val (id, raw) = RawKeys.mint()
  private val digest    = RawKeys.digest(raw)

  private def kit = EventSourcedTestKit.of(AccessKey, id)
  private def created(expiresAt: Option[Instant] = None) =
    val k = kit
    k.call(AccessKey.create)(Create("bi", digest, expiresAt, Set("email"), 600, "op-1", at)): Unit
    k
  private def admit(k: EventSourcedTestKit[AccessKey, KeyState, KeyEvent], d: String = digest, now: Instant = at.plusSeconds(1)) =
    k.call(AccessKey.admit)(Admit(d, now)).replyValue.refused

  test("create: enabled, the fields and rate as given; a repeat with the same digest changes nothing") {
    val k = created()
    assertEquals(k.currentState.enabled, true)
    assertEquals(k.currentState.identifyingFields, Set("email"))
    assertEquals(k.call(AccessKey.create)(Create("bi", digest, None, Set.empty, 600, "op-1", at)).events, Vector.empty)
    assert(k.call(AccessKey.create)(Create("bi", RawKeys.digest(raw + "x"), None, Set.empty, 600, "op-1", at)).isError)
  }

  test("change, disable, enable and delete, each carrying the operator") {
    val k = created()
    val c = k.call(AccessKey.change)(Change(name = Some("bi-2"), requestsPerMinute = Some(5), by = "op-2", at = at)).eventOfType[KeyChanged]
    assertEquals((c.name, c.requestsPerMinute, c.by), (Some("bi-2"), Some(5), "op-2"))
    assertEquals(k.call(AccessKey.disable)(By("op-2", at)).eventOfType[KeyDisabled].by, "op-2")
    assertEquals(k.call(AccessKey.disable)(By("op-2", at)).events, Vector.empty)
    assertEquals(k.call(AccessKey.enable)(By("op-3", at)).eventOfType[KeyEnabled].by, "op-3")
    assertEquals(k.call(AccessKey.delete)(By("op-4", at)).eventOfType[KeyDeleted].by, "op-4")
    assertEquals(k.currentState.digest, None)
  }

  test("an expiry is cleared only by clearExpiry") {
    val k = created(Some(at.plusSeconds(3600)))
    k.call(AccessKey.change)(Change(name = Some("x"), by = "op", at = at)): Unit
    assertEquals(k.currentState.expiresAt, Some(at.plusSeconds(3600)))
    k.call(AccessKey.change)(Change(clearExpiry = true, by = "op", at = at)): Unit
    assertEquals(k.currentState.expiresAt, None)
  }

  test("a command on a deleted key is key_deleted, except delete and disable, which are idempotent") {
    val k = created()
    k.call(AccessKey.delete)(By("op", at)): Unit
    assertEquals(k.call(AccessKey.change)(Change(name = Some("x"), by = "op", at = at)).errorMessage, "key_deleted")
    assertEquals(k.call(AccessKey.enable)(By("op", at)).errorMessage, "key_deleted")
    assertEquals(k.call(AccessKey.delete)(By("op", at)).events, Vector.empty)
    assert(!k.call(AccessKey.disable)(By("op", at)).isError)
  }

  test("admit: the key's fields and rate") {
    val a = created().call(AccessKey.admit)(Admit(digest, at)).replyValue
    assertEquals(a, Admission(None, Set("email"), 600))
  }

  test("admit's order: a wrong digest is key_invalid even when the key is disabled or expired; expired before disabled") {
    val k = created(Some(at.plusSeconds(60)))
    k.call(AccessKey.disable)(By("op", at)): Unit
    assertEquals(admit(k, RawKeys.digest(raw + "x"), at.plusSeconds(120)), Some("key_invalid"))
    assertEquals(admit(k, now = at.plusSeconds(120)), Some("key_expired"))
    assertEquals(admit(k, now = at.plusSeconds(60)), Some("key_expired"))
    assertEquals(admit(k, now = at.plusSeconds(30)), Some("key_disabled"))
    assertEquals(admit(kit), Some("key_invalid"))
    k.call(AccessKey.delete)(By("op", at)): Unit
    assertEquals(admit(k, now = at.plusSeconds(30)), Some("key_invalid"))
  }

  test("no event carries the key; only KeyCreated carries the digest") {
    val k = created(Some(at.plusSeconds(60)))
    k.call(AccessKey.change)(Change(name = Some("y"), by = "op", at = at)): Unit
    k.call(AccessKey.disable)(By("op", at)): Unit
    k.call(AccessKey.enable)(By("op", at)): Unit
    k.call(AccessKey.delete)(By("op", at)): Unit
    k.allEvents.foreach { e =>
      val text = String(AccessKey.eventSerializer.toBytes(e), UTF_8)
      assert(!text.contains(raw.takeRight(43)), text)
      e match
        case _: KeyCreated => assert(text.contains(digest))
        case _             => assert(!text.contains(digest), text)
    }
  }
