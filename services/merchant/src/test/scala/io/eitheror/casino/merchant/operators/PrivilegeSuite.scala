package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** The original's `PrivilegeCheck`: who may give which role at which level (FR-027). */
class PrivilegeSuite extends munit.FunSuite with LogCapturing:

  test("an operator holds the highest level of their roles") {
    assertEquals(Privilege.level(Set("resource_viewer", "resource_admin", "brand_goldvibe")), 40)
    assertEquals(Privilege.level(Set("brand_goldvibe")), 0)
  }

  test("a role is given only by someone who strictly outranks it") {
    assert(Privilege.canAssignRole(Set("resource_admin"), "resource_operator"))
    assert(!Privilege.canAssignRole(Set("resource_admin"), "resource_admin"), "equal is not above")
    assert(!Privilege.canAssignRole(Set("resource_admin"), "resource_owner"))
    assert(Privilege.canAssignRole(Set("platform_admin"), "compliance_officer"))
    assert(!Privilege.canAssignRole(Set("platform_admin"), "brand_goldvibe"), "a role outside the table is never assignable")
  }

  test("a scope may not broaden the caller's level") {
    assert(!Privilege.canAssignScope(Scope(Level.Company, "c-A"), Scope(Level.Holding, "h-A"), Set.empty))
    assert(Privilege.canAssignScope(Scope(Level.Company, "c-A"), Scope(Level.Company, "c-A"), Set("h-A")))
  }

  test("a scope must lie in the caller's subtree") {
    assert(Privilege.canAssignScope(Scope(Level.Holding, "h-A"), Scope(Level.SubBrand, "sb-A"), Set("h-A", "c-A")))
    assert(!Privilege.canAssignScope(Scope(Level.Holding, "h-A"), Scope(Level.SubBrand, "sb-B"), Set("h-B", "c-B")))
    assert(Privilege.canAssignScope(Scope.PlatformScope, Scope(Level.Holding, "h-B"), Set.empty))
  }
