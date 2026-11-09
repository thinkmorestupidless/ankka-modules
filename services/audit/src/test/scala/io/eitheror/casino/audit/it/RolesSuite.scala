package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.roles.{Grants, RealmRead}

import scala.concurrent.duration.*

/** `/v1/audit-roles` and the daily realm read (FR-016, research R8): grant, revoke, each recorded; an outside holding found once. */
class RolesSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  private def holders(role: String): Map[String, String] =
    import scala.jdk.CollectionConverters.*
    val r = kit.request("GET", s"/v1/audit-roles/$role", headers = kit.asOwner())
    assertEquals(r.status, 200, r.text)
    r.json.get("holders").elements.asScala.map(h => h.get("person").asText -> h.get("held").asText).toMap

  test("the owner grants brand compliance for a brand and revokes it; each is recorded under the brand, naming no one by realm id") {
    val person = kit.fresh("u")
    kit.realm.add(person)
    val granted = kit.request("PUT", s"/v1/audit-roles/brand_compliance/$person", Some("""{"brand":"goldvibe"}"""), kit.asOwner())
    assertEquals(granted.status, 200, granted.text)
    assertEquals(kit.realm.rolesOf(person), Set("brand_compliance", "brand_goldvibe"))
    assertEquals(holders("brand_compliance").get(person), Some("granted"))
    val grant = kit.eventually()(kit.own(Grants.GrantAction).find(r => r.brand == "goldvibe" && r.changes.exists(_.after.contains("brand_compliance"))))
    assert(!grant.toString.contains(person), grant.toString)
    assertEquals(kit.request("DELETE", s"/v1/audit-roles/brand_compliance/$person?brand=goldvibe", headers = kit.asOwner()).status, 200)
    assertEquals(kit.realm.rolesOf(person), Set("brand_goldvibe"))
    kit.eventually()(kit.own(Grants.RevokeAction).find(r => r.subject == grant.subject))
  }

  test("audit_compliance and a brand's re-identification role are granted alone; an unknown role or person is refused") {
    val person = kit.fresh("u")
    kit.realm.add(person)
    assertEquals(kit.request("PUT", s"/v1/audit-roles/audit_compliance/$person", Some("{}"), kit.asOwner()).status, 200)
    assertEquals(kit.request("PUT", s"/v1/audit-roles/reidentify_spinvibe/$person", Some("{}"), kit.asOwner()).status, 200)
    assertEquals(kit.realm.rolesOf(person), Set("audit_compliance", "reidentify_spinvibe"))
    assertEquals(kit.request("PUT", s"/v1/audit-roles/platform_admin/$person", Some("{}"), kit.asOwner()).code, "unknown_role")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/reidentify_nowhere/$person", Some("{}"), kit.asOwner()).code, "unknown_role")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/brand_compliance/$person", Some("{}"), kit.asOwner()).status, 400)
    assertEquals(kit.request("PUT", "/v1/audit-roles/audit_compliance/nobody", Some("{}"), kit.asOwner()).code, "unknown_person")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/audit_compliance/$person", Some("{}"), kit.asInstallationCompliance()).status, 403)
  }

  test("a holding given outside the audit is found by the realm read once, recorded, and listed as outside") {
    val person = kit.fresh("u")
    kit.realm.add(person, "audit_compliance")
    val before = kit.own(Grants.FoundAction).size
    assertEquals(RealmRead.run(kit.components).get("audit_compliance"), Some(1))
    assertEquals(RealmRead.run(kit.components).get("audit_compliance"), Some(0))
    assertEquals(holders("audit_compliance").get(person), Some("outside"))
    kit.eventually()(Option.when(kit.own(Grants.FoundAction).size == before + 1)(()))
  }
