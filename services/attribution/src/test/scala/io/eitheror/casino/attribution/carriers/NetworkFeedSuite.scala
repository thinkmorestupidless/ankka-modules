package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.attribution.{Json, Named}
import io.eitheror.casino.attribution.Json.text
import io.eitheror.casino.attribution.facts.Facts

import java.time.Instant

class NetworkFeedSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-25T10:00:00Z")
  private def fact(click: String) =
    FeedFact.of(Facts.click("goldvibe", click, Named("programme", "aff-1", "lnk-1", None, Vector.empty), at, Some("GB"), Some("mobile")))
  private def kit = EventSourcedTestKit.of(NetworkFeed, "programme")

  test("a feed fact is the contract's rendering, with its id and kind") {
    val f = fact("c-1")
    assertEquals(f.factId, "click/c-1")
    assertEquals(f.kind, "click")
    assertEquals(Json.parse(f.json).flatMap(_.text("affiliate")), Some("aff-1"))
  }

  test("append numbers each fact from 1; the same fact id is Seen") {
    val k = kit
    val one = k.call(NetworkFeed.append)(fact("c-1"))
    assertEquals(one.replyValue, AppendResult.Added(1))
    assertEquals(one.events, Vector(FeedEvent.Appended(1, fact("c-1"))))
    assertEquals(k.call(NetworkFeed.append)(fact("c-2")).replyValue, AppendResult.Added(2))
    val again = k.call(NetworkFeed.append)(fact("c-1"))
    assertEquals(again.replyValue, AppendResult.Seen)
    assert(!again.persisted)
  }

  test("recent is bounded at 10,000") {
    val k = kit
    (1 to 10005).foreach(i => k.call(NetworkFeed.append)(FeedFact(s"f-$i", "click", "{}")): Unit)
    val s = k.currentState
    assertEquals((s.lastSeq, s.recent.size, s.recent.head), (10005L, 10000, "f-6"))
  }
