package io.eitheror.casino.ingest.rate

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import java.time.Instant

/** FR-015, research R4: a fixed one-minute window per sub-brand; the raise holds across minutes. */
class RateWindowSuite extends munit.FunSuite with LogCapturing:
  private val noon = Instant.parse("2026-10-22T12:00:15Z")

  test("the first take is served with count 1 and the minute of now") {
    val kit = KeyValueEntityTestKit.of(RateWindow, "sb-1")
    assertEquals(kit.call(RateWindow.take)(Take(noon, 600)).replyValue, Taken(served = true, 1))
    assertEquals(kit.call(RateWindow.read).replyValue, RateState(noon.getEpochSecond / 60, 1, None))
  }

  test("the 600th take is served, the 601st refused with the seconds to the next minute, and the refusal does not count") {
    val kit = KeyValueEntityTestKit.of(RateWindow, "sb-1")
    (1 to 599).foreach(_ => kit.call(RateWindow.take)(Take(noon, 600)): Unit)
    assertEquals(kit.call(RateWindow.take)(Take(noon, 600)).replyValue, Taken(served = true, 600))
    val refused = kit.call(RateWindow.take)(Take(noon, 600))
    assertEquals(refused.replyValue, Taken(served = false, 600, 45))
    assert(!refused.changed)
    assertEquals(kit.call(RateWindow.read).replyValue.count, 600)
  }

  test("a take in the next minute resets the count") {
    val kit = KeyValueEntityTestKit.of(RateWindow, "sb-1")
    (1 to 601).foreach(_ => kit.call(RateWindow.take)(Take(noon, 600)): Unit)
    assertEquals(kit.call(RateWindow.take)(Take(noon.plusSeconds(60), 600)).replyValue, Taken(served = true, 1))
  }

  test("a raise to 6000 serves 700 and outlives the default each take carries") {
    val kit = KeyValueEntityTestKit.of(RateWindow, "sb-1")
    kit.call(RateWindow.setLimit)(6000): Unit
    assert((1 to 700).forall(_ => kit.call(RateWindow.take)(Take(noon, 600)).replyValue.served))
    assertEquals(kit.call(RateWindow.read).replyValue, RateState(noon.getEpochSecond / 60, 700, Some(6000)))
  }
