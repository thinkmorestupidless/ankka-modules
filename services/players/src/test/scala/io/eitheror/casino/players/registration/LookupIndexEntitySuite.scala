package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

/** The lookup index (T014). */
class LookupIndexEntitySuite extends munit.FunSuite:

  test("Add answers the others already held; a repeat adds nothing; Remove takes one out") {
    val kit = KeyValueEntityTestKit.of(LookupIndexEntity, "token-1")
    assertEquals(kit.call(LookupIndexEntity.add)(LookupPlayer("p1")).replyValue, Others(Set.empty))
    assertEquals(kit.call(LookupIndexEntity.add)(LookupPlayer("p2")).replyValue, Others(Set("p1")))
    assertEquals(kit.call(LookupIndexEntity.add)(LookupPlayer("p1")).replyValue, Others(Set("p2")))
    assertEquals(kit.currentState.playerIds, Set("p1", "p2"))
    assertEquals(kit.call(LookupIndexEntity.remove)(LookupPlayer("p1")).replyValue, Others(Set("p2")))
    assertEquals(kit.currentState.playerIds, Set("p2"))
  }
