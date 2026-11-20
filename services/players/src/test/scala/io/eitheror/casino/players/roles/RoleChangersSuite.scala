package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

/**
 * FR-008d with one writer: the roles carrying `roles.change` kept by one entity, so two changes at once each
 * taking it from a different role cannot both pass, as two reads of a lagging view let them.
 */
class RoleChangersSuite extends munit.FunSuite with LogCapturing:

  test("two roles carry it: the first to lose it is let, the second is refused, whatever each caller read") {
    val kit = KeyValueEntityTestKit.of(RoleChangers, "brand")
    def claim(role: String, carries: Boolean, known: Set[String] = Set.empty) =
      kit.call(RoleChangers.claim)(ClaimChange(role, carries, known)).replyValue
    assertEquals(claim("brand_admin", carries = false, known = Set("brand_admin", "ops")), ChangersAnswer(false, Set("ops")))
    // the second caller read the same two holders before the first's change was written: what it read is not asked
    assertEquals(claim("ops", carries = false, known = Set("brand_admin", "ops")), ChangersAnswer(true, Set("ops")))
    assertEquals(kit.currentState.holders, Some(Set("ops")))
  }

  test("a role given it is one more holder; a role that never carried it loses nothing; deleting the last is refused") {
    val kit = KeyValueEntityTestKit.of(RoleChangers, "brand")
    def claim(role: String, carries: Boolean, known: Set[String] = Set.empty) =
      kit.call(RoleChangers.claim)(ClaimChange(role, carries, known)).replyValue
    assertEquals(claim("brand_support", carries = false, known = Set("brand_admin")), ChangersAnswer(false, Set("brand_admin")))
    assertEquals(claim("ops", carries = true), ChangersAnswer(false, Set("brand_admin", "ops")))
    assertEquals(claim("brand_admin", carries = false), ChangersAnswer(false, Set("ops")))
    assert(claim("ops", carries = false).refused)
  }
