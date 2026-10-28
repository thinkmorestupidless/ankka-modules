package io.eitheror.casino.merchant.keys

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{MerchantKeyDeactivated, MerchantKeyIssued}
import io.eitheror.casino.merchant.hierarchy.*

import java.time.Instant

/** FR-004, FR-005: the facts `ingest` verifies keys from, never carrying a raw key. */
class KeysSuite extends munit.FunSuite with LogCapturing:

  private val at  = Instant.parse("2026-10-12T10:00:00Z")
  private val raw = RawKeys.mint()
  private val key = MerchantKey("k-1", "frontend", RawKeys.digest(raw), active = true, at)

  test("a generated key is published as merchant_key_issued with its id, sub-brand and digest, keyed by the key id") {
    val r = ConsumerTestKit.of(MerchantFacts).onMessage(SubBrandEvent.KeyGenerated(key, Actor.operator("op")), subject = "sb-1", sequenceNumber = 3)
    assertEquals(r.payloads, Vector(MerchantKeyIssued("k-1", "sb-1", key.digest, at)))
    assertEquals(r.recordKeys, Vector(Some("k-1")))
  }

  test("a disabled key is published as merchant_key_deactivated") {
    val r = ConsumerTestKit.of(MerchantFacts).onMessage(SubBrandEvent.KeyDisabled("k-1", Actor.operator("op"), at), subject = "sb-1", sequenceNumber = 4)
    assertEquals(r.payloads, Vector(MerchantKeyDeactivated("k-1", "sb-1", at)))
  }

  test("no other event of a sub-brand is published") {
    assertEquals(ConsumerTestKit.of(MerchantFacts).onMessage(SubBrandEvent.StatusChanged("Suspended", Actor.seed, at), subject = "sb-1", sequenceNumber = 5).payloads, Vector.empty)
  }

  test("a raw key is never what the facts carry, and its digest is hex SHA-256") {
    assert(!key.digest.contains(raw))
    assert(key.digest.matches("[0-9a-f]{64}"))
    assertEquals(RawKeys.digest("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertNotEquals(RawKeys.mint(), RawKeys.mint())
  }
