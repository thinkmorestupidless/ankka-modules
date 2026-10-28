package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** Scope through the hierarchy, as the original's `TenantIsolationSpec` and `HierarchicalAccess` decide it. */
class ScopeSuite extends munit.FunSuite with LogCapturing:

  private val subBrandA = Target(Some("h-A"), Some("c-A"), Some("sb-A"))
  private val subBrandB = Target(Some("h-B"), Some("c-B"), Some("sb-B"))

  test("return scope_violation when operator for holding-A calls holding-B merchant") {
    assert(!Access.permits(Scope(Level.Holding, "h-A"), subBrandB))
  }

  test("not refuse when operator for holding-A calls holding-A merchant") {
    assert(Access.permits(Scope(Level.Holding, "h-A"), subBrandA))
  }

  test("not refuse when platform operator calls any merchant") {
    assert(Access.permits(Scope.PlatformScope, subBrandB))
  }

  test("a company operator reaches its company's sub-brands and not another company's") {
    assert(Access.permits(Scope(Level.Company, "c-A"), subBrandA))
    assert(!Access.permits(Scope(Level.Company, "c-A"), subBrandB))
  }

  test("a company operator does not reach its own holding (deny a company-scoped operator writing a holding-level tree)") {
    assert(!Access.permits(Scope(Level.Company, "c-A"), Target(Some("h-A"))))
  }

  test("a sub-brand operator reaches that sub-brand alone") {
    assert(Access.permits(Scope(Level.SubBrand, "sb-A"), subBrandA))
    assert(!Access.permits(Scope(Level.SubBrand, "sb-A"), Target(Some("h-A"), Some("c-A"), Some("sb-A2"))))
  }

  test("a sub-brand with no parent is served to the installation level only (FR-029)") {
    val orphan = Target(None, None, Some("sb-0"))
    assert(Access.permits(Scope.PlatformScope, orphan))
    assert(!Access.permits(Scope(Level.Holding, "h-A"), orphan))
  }

  test("a level is one of the claim's four operator words; affiliate is none of them") {
    assertEquals(Level.from("sub_brand"), Some(Level.SubBrand))
    assertEquals(Level.from("affiliate"), None)
  }
