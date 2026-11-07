package io.eitheror.casino.programme.accounts

import io.eitheror.casino.programme.links.CreateLink
import io.eitheror.casino.programme.sealing.Identity

import java.time.Instant
import scala.collection.mutable

class ProvisionerSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-25T08:00:00Z")

  test("two brands' approvals racing: the realm says the user exists, and it is found and given the roles") {
    val set   = mutable.Map.empty[String, String]
    val roles = mutable.Buffer.empty[(String, String)]
    val calls = new ProvisioningCalls:
      def account(affiliate: String): AffiliateState = AffiliateState(id = affiliate, keycloakUser = set.get(affiliate))
      def membership(affiliate: String, brand: String): MembershipState = MembershipState()
      def identity(affiliate: String, account: AffiliateState): Option[Identity] = Some(Identity(name = "Ann", email = "ann@example.com"))
      def setKeycloakUser(affiliate: String, user: String): Unit = set(affiliate) = user
      def createLink(c: CreateLink): Unit = ()
    // The other approval made the user between this one's look and its make.
    var looks = 0
    val realm = new KeycloakAdmin:
      def createUser(email: String, f: Option[String], l: Option[String], a: Map[String, String]) = Left(RealmError.AlreadyExists(email))
      def assignRole(userId: String, role: String) = { roles += userId -> role; Right(()) }
      def removeRole(userId: String, role: String) = Right(())
      def findByEmail(email: String) = { looks += 1; Right(Option.when(looks > 1)("kc-1")) }
      def resetPassword(userId: String, password: String) = Right(())
      def updateEmail(userId: String, email: String) = Right(())
      def deleteUser(userId: String) = Right(())
      def usersWithRole(role: String) = Right(Vector.empty)
      def rolesOf(userId: String) = Right(Set.empty)
    Provisioner(calls, realm, () => at).provision("aff-1", "silvervibe", Membership.Approved("plan-1", "mgr", at))
    assertEquals(set.get("aff-1"), Some("kc-1"))
    assertEquals(roles.toSet, Set("kc-1" -> "affiliate", "kc-1" -> "brand_silvervibe"))
  }
