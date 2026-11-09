package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.ingest.IngestCalls
import io.eitheror.casino.audit.roles.{Grants, RealmRead}

import scala.concurrent.duration.*

/** `/v1/audit-roles` and the daily realm read (FR-016, research R8): grant, revoke, each recorded; an outside holding found once. */
class RolesSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  /** The installation pseudonym a holder is listed under. */
  private def listed(person: String): String = Grants.holder(IngestCalls.of(kit.components), person)

  private def holders(role: String): Map[String, String] =
    import scala.jdk.CollectionConverters.*
    val r = kit.request("GET", s"/v1/audit-roles/$role", headers = kit.asOwner())
    assertEquals(r.status, 200, r.text)
    r.json.get("holders").elements.asScala.map(h => h.get("person").asText -> h.get("held").asText).toMap

  test("the owner grants a brand's compliance role and revokes it; exactly that role, each recorded under the brand, no one named by realm id") {
    val person = kit.fresh("u")
    kit.realm.add(person, "brand_spinvibe")
    val granted = kit.request("PUT", s"/v1/audit-roles/compliance_goldvibe/$person", None, kit.asOwner())
    assertEquals(granted.status, 200, granted.text)
    // The person's operator role at another brand gives them nothing in the audit, and none is added.
    assertEquals(kit.realm.rolesOf(person), Set("brand_spinvibe", "compliance_goldvibe"))
    val listing = holders("compliance_goldvibe")
    assertEquals(listing.get(listed(person)), Some("granted"))
    assert(!listing.keySet.contains(person), "a holder is listed by pseudonym, never by realm id")
    val grant = kit.eventually()(kit.own(Grants.GrantAction).find(r => r.brand == "goldvibe" && r.changes.exists(_.after.contains("compliance_goldvibe"))))
    assert(!grant.toString.contains(person), grant.toString)
    // Revoked by the pseudonym the listing answered.
    assertEquals(kit.request("DELETE", s"/v1/audit-roles/compliance_goldvibe/${listed(person)}", headers = kit.asOwner()).status, 200)
    assertEquals(kit.realm.rolesOf(person), Set("brand_spinvibe"))
    kit.eventually()(kit.own(Grants.RevokeAction).find(r => r.subject == grant.subject))
  }

  test("an officer of two brands revoked at one reads the other alone") {
    val person = kit.fresh("u")
    kit.realm.add(person)
    Vector("compliance_goldvibe", "compliance_spinvibe").foreach(r =>
      assertEquals(kit.request("PUT", s"/v1/audit-roles/$r/$person", None, kit.asOwner()).status, 200))
    assertEquals(kit.request("DELETE", s"/v1/audit-roles/compliance_goldvibe/$person", headers = kit.asOwner()).status, 200)
    val roles = kit.realm.rolesOf(person).toSeq
    assertEquals(roles.toSet, Set("compliance_spinvibe"))
    val as = kit.as(person, roles*)
    assertEquals(kit.query(as, "brand" -> "goldvibe").status, 403)
    assertEquals(kit.query(as, "brand" -> "spinvibe").status, 200)
  }

  test("audit_compliance and a brand's re-identification role are granted alone; an unknown role or person is refused") {
    val person = kit.fresh("u")
    kit.realm.add(person)
    assertEquals(kit.request("PUT", s"/v1/audit-roles/audit_compliance/$person", Some("{}"), kit.asOwner()).status, 200)
    assertEquals(kit.request("PUT", s"/v1/audit-roles/reidentify_spinvibe/$person", Some("{}"), kit.asOwner()).status, 200)
    assertEquals(kit.realm.rolesOf(person), Set("audit_compliance", "reidentify_spinvibe"))
    assertEquals(kit.request("PUT", s"/v1/audit-roles/platform_admin/$person", Some("{}"), kit.asOwner()).code, "unknown_role")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/reidentify_nowhere/$person", Some("{}"), kit.asOwner()).code, "unknown_role")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/brand_compliance/$person", Some("{}"), kit.asOwner()).code, "unknown_role")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/compliance_nowhere/$person", Some("{}"), kit.asOwner()).code, "unknown_role")
    assertEquals(kit.request("PUT", "/v1/audit-roles/audit_compliance/nobody", Some("{}"), kit.asOwner()).code, "unknown_person")
    assertEquals(kit.request("PUT", s"/v1/audit-roles/audit_compliance/$person", Some("{}"), kit.asInstallationCompliance()).status, 403)
  }

  test("a holding given outside the audit is found by the realm read once, recorded, and listed as outside") {
    val person = kit.fresh("u")
    kit.realm.add(person, "audit_compliance")
    val before = kit.own(Grants.FoundAction).size
    assertEquals(RealmRead.run(kit.components).get("audit_compliance"), Some(1))
    assertEquals(RealmRead.run(kit.components).get("audit_compliance"), Some(0))
    assertEquals(holders("audit_compliance").get(listed(person)), Some("outside"))
    kit.eventually()(Option.when(kit.own(Grants.FoundAction).size == before + 1)(()))
  }
