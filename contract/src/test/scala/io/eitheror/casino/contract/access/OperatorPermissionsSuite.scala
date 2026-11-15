package io.eitheror.casino.contract.access

/** Spec 030 contracts/roles.md: the permissions' names and the role matrix a brand starts with. */
class OperatorPermissionsSuite extends munit.FunSuite:

  import OperatorPermissions.*

  test("every permission of contracts/roles.md is named once") {
    assertEquals(All.size, All.distinct.size)
    assertEquals(All.size, 42)
    for p <- Vector("player.read", "adjustment.make", "withdrawal.review", "reward.grant", "reward.revoke",
        "modules.read", "modules.request", "roles.read", "roles.change", "roles.give", "report.dayEnd", "send.release")
    do assert(known(p), p)
    assert(!known("withdrawal.approve"))
    assert(All.forall(_.matches("[a-z]+\\.[a-zA-Z]+")), All.filterNot(_.matches("[a-z]+\\.[a-zA-Z]+")))
  }

  test("the role matrix gives each default role its default bag") {
    assertEquals(DefaultRoles.keySet, Set("brand_support", "brand_finance", "brand_compliance", "brand_admin"))
    assertEquals(DefaultRoles("brand_admin"), All.toSet)
    val support = DefaultRoles("brand_support")
    assert(support("player.read") && support("note.write") && support("message.send") && support("roles.read"))
    assert(!support("adjustment.make") && !support("standing.change") && !support("report.read"))
    val finance = DefaultRoles("brand_finance")
    assert(finance("adjustment.make") && finance("withdrawal.review") && finance("report.dayEnd") && finance("export.player"))
    assert(!finance("identity.review") && !finance("standing.change") && !finance("report.compliance"))
    val compliance = DefaultRoles("brand_compliance")
    assert(compliance("identity.review") && compliance("protection.change") && compliance("fraud.review") && compliance("report.compliance"))
    assert(!compliance("adjustment.make") && !compliance("withdrawal.review") && !compliance("tier.change"))
    for (role, bag) <- DefaultRoles do assert(bag.forall(known), s"$role carries an unknown permission")
  }

  test("only brand_admin can change and give roles by default, so a brand starts with one way back in") {
    assertEquals(DefaultRoles.collect { case (r, bag) if bag("roles.change") => r }.toSet, Set("brand_admin"))
    assertEquals(DefaultRoles.collect { case (r, bag) if bag("roles.give") => r }.toSet, Set("brand_admin"))
  }

  test("no payments role, no brand role and not platform_admin is in the matrix or a brand's to make") {
    for r <- Routes.PaymentsRoles.keys do
      assert(!DefaultRoles.contains(r), r)
      assert(reserved(r), r)
    assert(reserved(PlatformAdmin))
    assert(reserved("brand_goldvibe"))
    assert(reserved("brand_spinvibe"))
    // the default roles are the brand's to change, not reserved
    for r <- DefaultRoles.keys do assert(!reserved(r), r)
    assert(!reserved("vip_desk"))
  }

  test("platform_admin carries every permission whatever a brand's roles say") {
    assertEquals(permissionsOf(Set(PlatformAdmin), Map.empty), All.toSet)
    assertEquals(permissionsOf(Set(PlatformAdmin), Map("brand_admin" -> Set.empty)), All.toSet)
  }

  test("an operator's permissions are the union of the bags of the roles they hold, and nothing else") {
    val bags = Map("desk" -> Set("player.read", "note.write"), "cashier" -> Set("withdrawal.review"))
    assertEquals(permissionsOf(Set("brand_goldvibe", "desk", "cashier", "resource_admin"), bags), Set("player.read", "note.write", "withdrawal.review"))
    assertEquals(permissionsOf(Set("brand_goldvibe"), bags), Set.empty[String])
    // a role's name in a token carries nothing the bag does not hold
    assertEquals(permissionsOf(Set("brand_admin"), bags), Set.empty[String])
  }

  test("a role's name is lower case letters, digits and _, at most 40") {
    assert(validRoleName("vip_desk"))
    assert(validRoleName("brand_support"))
    assert(!validRoleName("VIP"))
    assert(!validRoleName("vip-desk"))
    assert(!validRoleName(""))
    assert(!validRoleName("a" * 41))
  }
