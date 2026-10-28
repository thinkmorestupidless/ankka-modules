package io.eitheror.casino.ingest.keys

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{MerchantKeyDeactivated, MerchantKeyIssued, PaymentsMerchants}
import io.eitheror.casino.ingest.Recorded
import io.eitheror.casino.ingest.keys.KeyCodecs.given

import java.time.Instant

/** FR-004, research R3: the key index from merchant's facts, at least once, never throwing on what it cannot read. */
class KeyFactsSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-22T10:00:00Z")

  private def kit(r: Recorded) = ConsumerTestKit.of(KeyFacts, r.client)

  test("merchant_key_issued issues the key under its id with the fact's digest, sub-brand and time") {
    val r = Recorded()
    assertEquals(kit(r).onMessage(Some(MerchantKeyIssued("k-1", "sb-1", "d1", at))).effect, ConsumerEffect.Done)
    val c = r.calls.loneElement
    assertEquals((c.component, c.entity, c.method), ("key", "k-1", "issue"))
    assertEquals(c.as[Issue], Issue("d1", "sb-1", at))
  }

  test("merchant_key_deactivated deactivates the key under its id") {
    val r = Recorded()
    kit(r).onMessage(Some(MerchantKeyDeactivated("k-1", "sb-1", at))): Unit
    val c = r.calls.loneElement
    assertEquals((c.entity, c.method, c.as[Deactivate]), ("k-1", "deactivate", Deactivate(at)))
  }

  test("the same fact twice is the same command twice: the entity makes it idempotent") {
    val r = Recorded()
    val k = kit(r)
    k.onMessage(Some(MerchantKeyIssued("k-1", "sb-1", "d1", at))): Unit
    k.onMessage(Some(MerchantKeyIssued("k-1", "sb-1", "d1", at))): Unit
    assertEquals(r.calls.map(c => (c.entity, c.method)), Vector(("k-1", "issue"), ("k-1", "issue")))
  }

  test("a fact the codec cannot read is skipped, never thrown") {
    assertEquals(LenientFacts.serializer.fromBytes("""{"type":"merchant_key_rotated","keyId":"k"}""".getBytes), None)
    assertEquals(LenientFacts.serializer.fromBytes("not json".getBytes), None)
    val fact = MerchantKeyIssued("k-1", "sb-1", "d1", at)
    assertEquals(LenientFacts.serializer.fromBytes(PaymentsMerchants.serializer.toBytes(fact)), Some(fact))
    val r = Recorded()
    assertEquals(kit(r).onMessage(None).effect, ConsumerEffect.Ignore)
    assertEquals(r.calls, Vector.empty)
  }

  extension [A](xs: Vector[A]) private def loneElement: A = { assertEquals(xs.size, 1, xs.toString); xs.head }

/** What `Key` decided is written under the digest; a key with no digest yet is not. */
class KeyIndexWriteSuite extends munit.FunSuite with LogCapturing:
  import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, KeyValueEntityTestKit}
  import io.eitheror.casino.contract.facts.MerchantKeyIssued
  import io.eitheror.casino.ingest.Recorded
  import io.eitheror.casino.ingest.keys.KeyIndexCodecs.given
  private val at = java.time.Instant.parse("2026-10-22T10:00:00Z")

  test("an issue that Key made active is set under its digest") {
    val decided = KeyCodecs.given_Serializer_KeyState.toBytes(KeyState("d1", "sb-1", active = true, since = Some(at)))
    val r = Recorded(c => if c.component == "key" then decided else "null".getBytes)
    ConsumerTestKit.of(KeyFacts, r.client).onMessage(Some(MerchantKeyIssued("k-1", "sb-1", "d1", at))): Unit
    val index = r.calls.filter(_.component == "key-index")
    assertEquals(index.map(c => (c.entity, c.method, c.as[KeyRow])), Vector(("d1", "set", KeyRow("k-1", "d1", "sb-1", active = true))))
  }

  test("the index never reactivates a deactivated key, whatever arrives after") {
    val kit = KeyValueEntityTestKit.of(KeyIndex, "d1")
    kit.call(KeyIndex.set)(KeyRow("k-1", "d1", "sb-1", active = true)): Unit
    kit.call(KeyIndex.set)(KeyRow("k-1", "d1", "sb-1", deactivated = true)): Unit
    val late = kit.call(KeyIndex.set)(KeyRow("k-1", "d1", "sb-1", active = true))
    assert(!late.changed)
    assertEquals(kit.call(KeyIndex.read).replyValue.map(_.active), Some(false))
    assertEquals(KeyValueEntityTestKit.of(KeyIndex, "none").call(KeyIndex.read).replyValue, None)
  }
