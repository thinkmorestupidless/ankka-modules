package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

/** The lookup index (T014; spec 011 T013). */
class LookupIndexEntitySuite extends munit.FunSuite with LogCapturing:

  test("Add answers the others already held; a repeat adds nothing; Remove takes one out") {
    val kit = KeyValueEntityTestKit.of(LookupIndexEntity, "token-1")
    assertEquals(kit.call(LookupIndexEntity.add)(LookupPlayer("p1")).replyValue, Others(Set.empty))
    assertEquals(kit.call(LookupIndexEntity.add)(LookupPlayer("p2")).replyValue, Others(Set("p1")))
    assertEquals(kit.call(LookupIndexEntity.add)(LookupPlayer("p1")).replyValue, Others(Set("p2")))
    assertEquals(kit.currentState.playerIds, Set("p1", "p2"))
    assertEquals(kit.call(LookupIndexEntity.remove)(LookupPlayer("p1")).replyValue, Others(Set("p2")))
    assertEquals(kit.currentState.playerIds, Set("p2"))
  }

  test("Arrive keeps an address's registrations of the last 24 hours and answers the others among them") {
    val t0  = Instant.parse("2026-11-01T10:00:00Z")
    val kit = KeyValueEntityTestKit.of(LookupIndexEntity, "address-1")
    assertEquals(kit.call(LookupIndexEntity.arrive)(Arrival("p1", t0)).replyValue, Others(Set.empty))
    assertEquals(kit.call(LookupIndexEntity.arrive)(Arrival("p2", t0.plusSeconds(3600))).replyValue, Others(Set("p1")))
    assertEquals(kit.call(LookupIndexEntity.arrive)(Arrival("p2", t0.plusSeconds(7200))).replyValue, Others(Set("p1")), "a repeat")
    assertEquals(kit.currentState.recent.get("p2"), Some(t0.plusSeconds(3600)))
    // p1 is 24 hours old: no longer counted, and no longer kept
    assertEquals(kit.call(LookupIndexEntity.arrive)(Arrival("p3", t0.plusSeconds(24 * 3600))).replyValue, Others(Set("p2")))
    assertEquals(kit.currentState.recent.keySet, Set("p2", "p3"))
    assertEquals(kit.currentState.playerIds, Set.empty[String], "an address's entry keeps no player for good")
    assertEquals(kit.call(LookupIndexEntity.remove)(LookupPlayer("p2")).replyValue, Others(Set.empty))
    assertEquals(kit.currentState.recent.keySet, Set("p3"))
  }
