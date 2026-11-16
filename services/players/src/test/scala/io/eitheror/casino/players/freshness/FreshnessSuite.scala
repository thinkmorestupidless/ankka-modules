package io.eitheror.casino.players.freshness

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

/** Spec 030 P2 (F): a view's mark keeps the latest time; `status` answers every view, `asOf` null until one is marked. */
class FreshnessSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-15T10:00:00Z")

  test("a mark keeps the latest time a follower recorded; an earlier one changes nothing") {
    val kit = KeyValueEntityTestKit.of(ViewMark, "players")
    assertEquals(kit.call(ViewMark.mark)(Mark(at)).replyValue, ViewMarkState(Some(at)))
    assertEquals(kit.call(ViewMark.mark)(Mark(at.minusSeconds(5))).replyValue, ViewMarkState(Some(at)))
    assertEquals(kit.call(ViewMark.mark)(Mark(at.plusSeconds(2))).replyValue, ViewMarkState(Some(at.plusSeconds(2))))
    assertEquals(kit.currentState, ViewMarkState(Some(at.plusSeconds(2))))
  }

  test("the answer: one entry per view, asOf an ISO instant or null, rebuilding false") {
    val r    = Freshness.answer(Vector(Freshness.ViewStatus("players", Some(at), false), Freshness.ViewStatus("fraud-cases", None, false)))
    val body = ObjectMapper().readTree(r.body.body)
    assertEquals(r.status, 200)
    val views = body.get("views")
    assertEquals(views.size, 2)
    assertEquals((views.get(0).get("name").asText, views.get(0).get("asOf").asText, views.get(0).get("rebuilding").asBoolean),
      ("players", "2026-11-15T10:00:00Z", false))
    assert(views.get(1).has("asOf") && views.get(1).get("asOf").isNull, body.toString)
  }

  test("the views behind the operator lists, each followed") {
    assertEquals(ViewFollower.views, Vector("players", "sessions", "activity", "notes", "messages", "fraud-cases", "roles", "rounds"))
    assertEquals(ViewFollower.descriptors.size, 1 + 9)
  }
