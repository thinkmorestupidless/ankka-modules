package io.eitheror.casino.contract.access

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.http.Principal

import scala.jdk.CollectionConverters.*

/** Spec 032: every row of `brand-roles.cases.json`, which the backoffice's `roles.test.ts` runs too. */
class BrandRolesSuite extends munit.FunSuite:

  private val table = ObjectMapper().readTree(getClass.getResourceAsStream("/brand-roles.cases.json"))
  private def strings(n: JsonNode): Set[String] = n.elements().asScala.map(_.asText()).toSet
  private val bags: Map[String, Set[String]] =
    table.path("bags").properties().asScala.map(e => e.getKey -> strings(e.getValue)).toMap

  table.path("cases").elements().asScala.foreach { c =>
    test(c.path("name").asText()) {
      val slug  = c.path("slug").asText()
      val roles = strings(c.path("roles"))
      assertEquals(BrandRoles.heldIn(slug, roles), strings(c.path("held")))
      val expected =
        if c.path("permissions").isTextual then OperatorPermissions.All.toSet else strings(c.path("permissions"))
      assertEquals(OperatorPermissions.permissionsOf(slug, roles, bags), expected)
      assertEquals(RoleBags.fixed(bags).permissionsOf(slug, roles), Some(expected))
    }
  }

  table.path("brands").elements().asScala.foreach { c =>
    test(s"brands: ${c.path("name").asText()}") {
      val p = Principal("s", roles = strings(c.path("roles")), issuer = Some(Routes.StaffIssuer))
      assertEquals(Routes.brandsOf(p), Routes.BrandSet.Only(strings(c.path("brands"))))
    }
  }

  test("a role's realm name is built and read back") {
    assertEquals(BrandRoles.nameIn("goldvibe", "brand_finance"), "brand_finance@goldvibe")
    assertEquals(BrandRoles.brandOf("brand_finance@goldvibe"), Some(("brand_finance", "goldvibe")))
    assertEquals(BrandRoles.brandOf("brand_finance"), None)
    assertEquals(BrandRoles.brandOf("@goldvibe"), None)
    assertEquals(BrandRoles.brandOf("brand_finance@"), None)
  }

  test("no role a brand makes can hold the separator") {
    assert(!OperatorPermissions.validRoleName("vip_host@goldvibe"))
  }
