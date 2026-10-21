package io.eitheror.casino.merchant.security

import com.thinkmorestupidless.ankka.sdk.SecretStore

import java.time.{Duration, Instant}
import scala.collection.mutable

/** Research R9: the cache, its invalidation, and an unreadable store told apart from an absent secret. */
class SecretsSuite extends munit.FunSuite:

  final class CountingStore extends SecretStore:
    val values = mutable.Map.empty[String, String]
    var reads  = 0
    def put(name: String, value: String): Unit = values.update(name, value)
    def get(name: String): Option[String]      = { reads += 1; values.get(name) }
    def delete(name: String): Unit             = values.remove(name): Unit

  test("a read is cached for 30 seconds, then read again") {
    var clock = Instant.parse("2026-10-12T10:00:00Z")
    val store = CountingStore()
    val s     = Secrets(store, now = () => clock)
    store.put("credential/c1", """{"webhookSecret":"w"}""")
    assertEquals(s.credential("c1"), Right(Map("webhookSecret" -> "w")))
    assertEquals(s.credential("c1"), Right(Map("webhookSecret" -> "w")))
    assertEquals(store.reads, 1)
    clock = clock.plus(Duration.ofSeconds(31))
    s.credential("c1"): Unit
    assertEquals(store.reads, 2)
  }

  test("a write here is seen here at once") {
    val store = CountingStore()
    val s     = Secrets(store)
    assertEquals(s.credential("c1"), Right(Map.empty))
    s.putMap("credential/c1", Map("apiKey" -> "k"))
    assertEquals(s.credential("c1"), Right(Map("apiKey" -> "k")))
    s.putMap("credential/c1", Map.empty)
    assertEquals(s.credential("c1"), Right(Map.empty), "an empty map deletes the entry")
    assert(!store.values.contains("credential/c1"))
  }

  test("an unreadable store is unavailable, never absent") {
    val s = Secrets(Secrets.failing("database down"))
    assert(s.credential("c1").isLeft)
    assert(s.get("terminal/t1").left.exists(_.reason.contains("database down")))
  }
