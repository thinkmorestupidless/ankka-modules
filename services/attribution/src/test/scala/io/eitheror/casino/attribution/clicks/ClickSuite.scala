package io.eitheror.casino.attribution.clicks

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant
import scala.concurrent.duration.*

class ClickSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-25T10:00:00Z")
  private val click = ClickRecord("c-1", Some("programme"), Some("aff-1"), Some("lnk-1"), Some("summer"), Vector("s1"), at,
    "mobile", Some("GB"), Some("digest"), Map("utm_source" -> "x"))

  test("record sets every field and declares a 60-day life") {
    val k = KeyValueEntityTestKit.of(Click, "c-1")
    val r = k.call(Click.record)(click)
    assertEquals(r.replyValue.click, Some(click))
    assertEquals(k.lastRetention, Some(Retention.ExpireAfter(60.days)))
  }

  test("a second record under the id changes nothing and replies the first") {
    val k = KeyValueEntityTestKit.of(Click, "c-1")
    k.call(Click.record)(click): Unit
    val again = k.call(Click.record)(click.copy(affiliate = Some("aff-2"), at = at.plusSeconds(5)))
    assert(!again.changed)
    assertEquals(again.replyValue.click, Some(click))
  }

  test("a refused click is kept with its reason") {
    val k = KeyValueEntityTestKit.of(Click, "c-2")
    val refused = ClickRecord("c-2", None, None, None, at = at, refused = Some("bad_signature"))
    assertEquals(k.call(Click.record)(refused).replyValue.click.flatMap(_.refused), Some("bad_signature"))
    assertEquals(k.call(Click.get).replyValue.click.map(_.clickId), Some("c-2"))
  }
