package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.audit.ids.RegistryKeyBytes

import java.time.Instant

class IngestEntitiesSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-09T10:00:00Z")
  private val key = RegistryKeyBytes(Array.fill[Byte](32)(9))

  test("a message is quarantined once, under its source and the digest of its bytes, sealed (FR-012, SC-002)") {
    val bytes = """{"actorId":"pid-123","not json""".getBytes
    val id    = Quarantined.id("brand:goldvibe", bytes)
    assert(id.matches("brand:goldvibe/[0-9a-f]{64}"), id)
    val k = KeyValueEntityTestKit.of(Quarantined, id)
    val held = k.call(Quarantined.hold)(Quarantined.hold("brand:goldvibe", "undecodable", bytes, at, key)).replyValue
    assertEquals(held.why, "undecodable")
    assert(!held.toString.contains("pid-123"), held)
    assertEquals(Sealing.unseal(key, held.sealedBytes).map(String(_)), Some(String(bytes)))
    assertEquals(Sealing.unseal(RegistryKeyBytes(Array.fill[Byte](32)(1)), held.sealedBytes), None)
    val again = k.call(Quarantined.hold)(Quarantined.hold("brand:goldvibe", "undecodable", bytes, at.plusSeconds(5), key))
    assert(!again.changed)
    assertEquals(again.replyValue.arrivedAt, Some(at))
  }

  test("a source's counts: stored and passed over, skewed, the latest arrival, and the latest occurrence not skewed") {
    val k = KeyValueEntityTestKit.of(SourceState, "brand:goldvibe")
    k.call(SourceState.received)(Received(at, at.minusSeconds(1), kept = true, skewed = false)): Unit
    k.call(SourceState.received)(Received(at.plusSeconds(2), at.minusSeconds(30), kept = false, skewed = false)): Unit
    val s = k.call(SourceState.received)(Received(at.plusSeconds(1), at.plusSeconds(900), kept = true, skewed = true)).replyValue
    assertEquals((s.stored, s.passedOver, s.skewed), (2L, 1L, 1L))
    // The skewed record's occurrence, ahead of its arrival, does not move the one the lag is measured from.
    assertEquals((s.lastReceived, s.lastOccurred), (Some(at.plusSeconds(2)), Some(at.minusSeconds(1))))
  }
