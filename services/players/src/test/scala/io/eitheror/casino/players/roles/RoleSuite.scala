package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.players.facts.Facts

import java.time.Instant

/** Spec 030 P12, P13: a role, the rules the brand holds over its roles, and their audit records (T012). */
class RoleSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-11-15T10:00:00Z")

  private def role(name: String) = EventSourcedTestKit.of(Role, name)

  test("a default role is seeded once with its default bag; a later seed leaves it as it stands") {
    val kit = role("brand_support")
    val bag = OperatorPermissions.DefaultRoles("brand_support")
    assert(kit.call(Role.seed)(SetRole("brand_support", "d", bag, "seed", at)).persisted)
    assertEquals((kit.currentState.permissions, kit.currentState.default), (bag, true))
    assert(kit.call(Role.set)(SetRole("brand_support", "d", bag - "note.write", "op-1", at)).persisted)
    assert(!kit.call(Role.seed)(SetRole("brand_support", "d", bag, "seed", at)).persisted)
    assertEquals(kit.currentState.permissions, bag - "note.write")
  }

  test("a role the brand makes is no default; changing it records what it carried before and after") {
    val kit = role("vip_host")
    assert(kit.call(Role.set)(SetRole("vip_host", "VIP desk", Set("player.read"), "op-1", at)).persisted)
    assert(!kit.currentState.default)
    val changed = kit.call(Role.set)(SetRole("vip_host", "VIP desk", Set("player.read", "note.write"), "op-1", at))
    assertEquals(changed.events, Vector[RoleEvent](RoleEvent.PermissionsChanged("VIP desk", Set("player.read"), Set("player.read", "note.write"), "op-1", at)))
    assert(!kit.call(Role.set)(SetRole("vip_host", "VIP desk", Set("player.read", "note.write"), "op-1", at)).persisted)
  }

  test("a default role resets to the role matrix's bag and is never deleted") {
    val kit = role("brand_finance")
    kit.call(Role.seed)(SetRole("brand_finance", "d", OperatorPermissions.DefaultRoles("brand_finance"), "seed", at)): Unit
    kit.call(Role.set)(SetRole("brand_finance", "d", Set("player.read"), "op-1", at)): Unit
    assert(kit.call(Role.reset)(RoleAct("op-1", at)).persisted)
    assertEquals(kit.currentState.permissions, OperatorPermissions.DefaultRoles("brand_finance"))
    assertEquals(kit.call(Role.delete)(RoleAct("op-1", at)).replyValue.refusal, Some(RoleRules.DefaultRole))
  }

  test("a role the brand made is deleted, has no reset, and its name may be used again") {
    val kit = role("vip_host")
    kit.call(Role.set)(SetRole("vip_host", "", Set("player.read"), "op-1", at)): Unit
    assertEquals(kit.call(Role.reset)(RoleAct("op-1", at)).replyValue.refusal, Some(RoleRules.NotDefault))
    assert(kit.call(Role.delete)(RoleAct("op-1", at)).persisted)
    assert(!kit.currentState.exists)
    assertEquals(kit.call(Role.delete)(RoleAct("op-1", at)).replyValue.refusal, Some(RoleRules.NoSuchRole))
    assert(kit.call(Role.set)(SetRole("vip_host", "", Set("note.write"), "op-2", at)).persisted)
    assertEquals(kit.currentState.permissions, Set("note.write"))
  }

  test("names: lower case letters, digits and _; platform_admin, brand_<slug> and payments roles are not the brand's") {
    assertEquals(RoleRules.nameRefusal("vip_host"), None)
    assertEquals(RoleRules.nameRefusal("brand_finance"), None)
    assertEquals(RoleRules.nameRefusal("VIP").map(_._1), Some(RoleRules.InvalidName))
    for r <- Vector("platform_admin", "brand_goldvibe", "resource_admin", "compliance_officer") do
      assertEquals(RoleRules.nameRefusal(r).map(_._1), Some(RoleRules.ReservedRole), r)
  }

  test("a permission the contract does not know is named") {
    assertEquals(RoleRules.unknown(Set("player.read", "player.teleport")), Some("player.teleport"))
    assertEquals(RoleRules.unknown(Set("player.read")), None)
  }

  test("the last role carrying roles.change cannot lose it or go; another carrying it lets it") {
    val only = Map("brand_admin" -> Set("roles.change", "player.read"), "brand_support" -> Set("player.read"))
    assert(RoleRules.leavesNoChanger(only, "brand_admin", Some(Set("player.read"))))
    assert(RoleRules.leavesNoChanger(only, "brand_admin", None))
    assert(!RoleRules.leavesNoChanger(only, "brand_support", Some(Set.empty)))
    val two = only.updated("brand_support", Set("roles.change"))
    assert(!RoleRules.leavesNoChanger(two, "brand_admin", Some(Set("player.read"))))
  }

  test("giving: the first permission the giver lacks of those the roles carry is named") {
    assertEquals(RoleRules.escalation(Set("roles.give", "player.read"), Set("player.read", "adjustment.make")), Some("adjustment.make"))
    assertEquals(RoleRules.escalation(Set("roles.give", "player.read"), Set("player.read")), None)
  }

  test("giving: what a giving takes away weighs as much as what it adds; a role since deleted carries nothing") {
    val bags  = Map("brand_admin" -> Set("roles.change", "roles.give", "player.read"), "brand_support" -> Set("player.read"))
    val giver = Set("roles.give", "player.read")
    assertEquals(RoleRules.givingLacks(giver, bags, before = Set("brand_admin"), asked = Set.empty), Some("roles.change"))
    assertEquals(RoleRules.givingLacks(giver, bags, before = Set("brand_admin"), asked = Set("brand_support")), Some("roles.change"))
    assertEquals(RoleRules.givingLacks(giver, bags, before = Set("brand_support"), asked = Set.empty), None)
    assertEquals(RoleRules.givingLacks(giver, bags, before = Set("vip_gone"), asked = Set("brand_support")), None)
    assertEquals(RoleRules.givingLacks(giver, bags, before = Set.empty, asked = Set("brand_admin")), Some("roles.change"))
  }

  test("every change to a role is an audit record naming the operator, the role and the permissions before and after") {
    val e = RoleEvent.PermissionsChanged("", Set("player.read", "adjustment.make"), Set("player.read"), "op-1", at)
    val r = Facts.roleAudit("goldvibe", "brand_finance", 3, e).get.fact
    assertEquals((r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId), ("role-change", "operator", "op-1", "role", "brand_finance"))
    assertEquals(r.changes.map(c => (c.field, c.before, c.after)), Vector(("permissions", Some("adjustment.make,player.read"), Some("player.read"))))
    val seeded = Facts.roleAudit("goldvibe", "brand_support", 1, RoleEvent.Defined("brand_support", "", Set("player.read"), true, "seed", at)).get.fact
    assertEquals(seeded.actorKind, "system")
  }

  test("a giving is recorded once per change and its audit record names both operators and the roles") {
    val kit = EventSourcedTestKit.of(RoleAssignment, "op-2")
    val first = kit.call(RoleAssignment.give)(Give("op-2", Set("brand_support"), "op-1", at))
    assertEquals(first.events, Vector[AssignmentEvent](AssignmentEvent.Given("op-2", Set.empty, Set("brand_support"), "op-1", at)))
    assert(!kit.call(RoleAssignment.give)(Give("op-2", Set("brand_support"), "op-1", at)).persisted)
    val r = Facts.assignmentAudit("goldvibe", "op-2", 1, first.events.head).get.fact
    assertEquals((r.action, r.actorId, r.subjectKind, r.subjectId), ("roles-give", "op-1", "operator", "op-2"))
    assertEquals(r.changes.map(_.after), Vector(Some("brand_support")))
  }
