package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * The applier's own rules (spec 032 H6a, research R4): what it writes to the realm for a giving, and how
 * long it waits on a realm that does not answer. The giving end to end, against the fake realm, is
 * `features/payments/operators.feature` (`OperatorsFeatures`).
 */
class BrandRolesApplierSuite extends munit.FunSuite with LogCapturing:

  /** A realm in memory: one user's roles, the roles that exist, and every write in order. */
  final class Realm(initial: String*) extends KeycloakAdmin:
    val roles   = mutable.Set(initial*)
    val exists  = mutable.Set.empty[String]
    val writes  = mutable.ArrayBuffer.empty[String]
    var refuse: Option[RealmError] = None
    private def fail[A](a: => A): Either[RealmError, A] = refuse.toLeft(a)
    def realmRoles(userId: String) = fail(roles.toSet)
    def ensureRole(role: String) = fail { if !exists(role) then { exists += role; writes += s"create $role" } }
    def assignRole(userId: String, role: String) = fail { roles += role; writes += s"assign $role" }
    def removeRole(userId: String, role: String) = fail { roles -= role; writes += s"remove $role" }
    def createUser(email: String, firstName: Option[String], lastName: Option[String]) = ???
    def setScope(userId: String, scopeId: String, scopeLevel: String, ancestors: Vector[String]) = ???
    def resetPassword(userId: String, password: String) = ???
    def setEmailVerified(userId: String) = ???
    def listUsers() = ???
    def deleteUser(userId: String) = ???

  test("a giving brings the brand's named roles to the asked set and touches no other brand's") {
    val realm = Realm("brand_goldvibe", "brand_spinvibe", "brand_support@goldvibe", "brand_admin@spinvibe", "resource_viewer")
    assertEquals(BrandRolesApplier.apply(realm, "u-1", "goldvibe", Set("brand_finance")), Right(()))
    assertEquals(realm.roles.toSet,
      Set("brand_goldvibe", "brand_spinvibe", "brand_finance@goldvibe", "brand_admin@spinvibe", "resource_viewer"))
    assertEquals(realm.writes.toVector, Vector("create brand_finance@goldvibe", "assign brand_finance@goldvibe", "remove brand_support@goldvibe"))
  }

  test("a role named for the brand that no giving asked for, the cutover's, is taken away (FR-007, FR-011)") {
    val realm = Realm("brand_goldvibe", "brand_finance@goldvibe", "brand_compliance@goldvibe")
    assertEquals(BrandRolesApplier.apply(realm, "u-1", "goldvibe", Set("brand_finance")), Right(()))
    assertEquals(realm.roles.toSet, Set("brand_goldvibe", "brand_finance@goldvibe"))
  }

  test("an empty giving keeps the operator in the brand; applying a giving twice writes nothing the second time") {
    val realm = Realm("brand_support@goldvibe")
    assertEquals(BrandRolesApplier.apply(realm, "u-1", "goldvibe", Set.empty), Right(()))
    assertEquals(realm.roles.toSet, Set("brand_goldvibe"))
    realm.writes.clear()
    assertEquals(BrandRolesApplier.apply(realm, "u-1", "goldvibe", Set.empty), Right(()))
    assertEquals(realm.writes.toVector, Vector.empty)
  }

  test("the realm's answer decides: unreachable is tried again, refused and not found are not") {
    val realm = Realm()
    realm.refuse = Some(RealmError.Unreachable("503"))
    assert(BrandRolesApplier.apply(realm, "u-1", "goldvibe", Set("brand_admin")).left.exists(RealmError.retryable))
    realm.refuse = Some(RealmError.Refused(403, "forbidden"))
    assert(BrandRolesApplier.apply(realm, "u-1", "goldvibe", Set("brand_admin")).left.exists(e => !RealmError.retryable(e)))
    assertEquals(BrandRolesApplier.reason(RealmError.NotFound("u-1")), "the operator is not in the realm")
    assertEquals(BrandRolesApplier.reason(RealmError.Refused(403, "role x: 403 forbidden")), "role x: 403 forbidden")
  }

  test("the wait on an unreachable realm doubles from 1 s to a ceiling of 10 s (SC-002)") {
    assertEquals((1 to 6).map(BrandRolesApplier.backoff).toVector, Vector(1.second, 2.seconds, 4.seconds, 8.seconds, 10.seconds, 10.seconds))
    assertEquals(BrandRolesApplier.backoff(1000), 10.seconds)
  }
