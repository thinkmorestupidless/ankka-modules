package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import java.time.Instant
import scala.concurrent.duration.*

class RoundIndexSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-25T23:59:00Z")

  test("remember: the first day wins, with a 7-day life") {
    val k = KeyValueEntityTestKit.of(RoundIndex, RoundIndex.id("p-1", "EUR", "r-1"))
    assertEquals(k.call(RoundIndex.remember)(Remember("2026-10-25", at)).replyValue.day, Some("2026-10-25"))
    assertEquals(k.lastRetention, Some(Retention.ExpireAfter(7.days)))
    val again = k.call(RoundIndex.remember)(Remember("2026-10-26", at.plusSeconds(120)))
    assert(!again.changed)
    assertEquals(again.replyValue, RoundState(Some("2026-10-25"), Some(at)))
  }
