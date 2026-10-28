package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
class WalletCursorSuite extends munit.FunSuite with LogCapturing:
  private def kit = KeyValueEntityTestKit.of(WalletCursor, WalletCursor.id("p-1", "EUR"))

  test("advance from 0: a deposit of 5000, then a bet of 1000") {
    val k = kit
    assertEquals(k.call(WalletCursor.advance)(Advance("d-1", 5000)).replyValue, Advanced.Delta(5000))
    assertEquals(k.call(WalletCursor.advance)(Advance("b-1", 4000)).replyValue, Advanced.Delta(-1000))
  }

  test("the same dedupe id is Seen with the delta it was given, and moves nothing") {
    val k = kit
    k.call(WalletCursor.advance)(Advance("d-1", 5000)): Unit
    val again = k.call(WalletCursor.advance)(Advance("d-1", 9000))
    assertEquals(again.replyValue, Advanced.Seen(5000))
    assert(!again.changed)
    assertEquals(k.call(WalletCursor.get).replyValue.lastRealAfter, 5000L)
  }

  test("recent is bounded at 200") {
    val k = kit
    (1 to 205).foreach(i => k.call(WalletCursor.advance)(Advance(s"d-$i", i.toLong)): Unit)
    val s = k.call(WalletCursor.get).replyValue
    assertEquals((s.recent.size, s.recent.head._1), (200, "d-6"))
  }
