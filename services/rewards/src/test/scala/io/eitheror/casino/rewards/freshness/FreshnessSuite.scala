package io.eitheror.casino.rewards.freshness

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

/** Spec 030 RW3 (F): a view's mark keeps the latest time; `status` answers every view, `asOf` null until one is marked. */
class FreshnessSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-15T10:00:00Z")

  test("a mark keeps the latest time a follower recorded; an earlier one changes nothing") {
    val kit = KeyValueEntityTestKit.of(ViewMark, "rewards-view")
    assertEquals(kit.call(ViewMark.mark)(Mark(at)).replyValue, ViewMarkState(Some(at)))
    assertEquals(kit.call(ViewMark.mark)(Mark(at.minusSeconds(5))).replyValue, ViewMarkState(Some(at)))
    assertEquals(kit.call(ViewMark.mark)(Mark(at.plusSeconds(2))).replyValue, ViewMarkState(Some(at.plusSeconds(2))))
    assertEquals(kit.currentState, ViewMarkState(Some(at.plusSeconds(2))))
  }

  test("the answer: one entry per view, asOf an ISO instant or null, rebuilding false") {
    val r = Freshness.answer(Vector(Freshness.ViewStatus("rewards-view", Some(at), false), Freshness.ViewStatus("other", None, false)))
    assertEquals(r.status, 200)
    assertEquals(String(r.body.body, "UTF-8"),
      """{"views":[{"name":"rewards-view","asOf":"2026-11-15T10:00:00Z","rebuilding":false},{"name":"other","asOf":null,"rebuilding":false}]}""")
  }

  test("the views behind the operator lists, each followed") {
    assertEquals(ViewFollower.views, Vector("rewards-view"))
    assertEquals(ViewFollower.descriptors.size, 1 + 1)
  }
