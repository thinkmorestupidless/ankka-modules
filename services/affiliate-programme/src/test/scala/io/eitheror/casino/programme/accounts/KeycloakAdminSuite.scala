package io.eitheror.casino.programme.accounts

import io.eitheror.casino.programme.comms.Stub

class KeycloakAdminSuite extends munit.FunSuite:
  private val creds = () => Some(KeycloakAdmin.Credentials("admin", "pw"))

  private def realm = Stub {
    case ("POST", "/realms/master/protocol/openid-connect/token", _) => (200, """{"access_token":"t"}""", Map.empty)
    case ("POST", "/admin/realms/eitheror/users", body) if body.contains("taken@") => (409, "", Map.empty)
    case ("POST", "/admin/realms/eitheror/users", _) => (201, "", Map("Location" -> "http://kc/admin/realms/eitheror/users/u-1"))
    case ("GET", "/admin/realms/eitheror/roles/brand_goldvibe", _) => (200, """{"id":"r-1","name":"brand_goldvibe"}""", Map.empty)
    case (_, "/admin/realms/eitheror/users/u-1/role-mappings/realm", _) => (204, "", Map.empty)
    case ("GET", p, _) if p.startsWith("/admin/realms/eitheror/users?email=") => (200, """[{"id":"u-1"}]""", Map.empty)
    case ("DELETE", "/admin/realms/eitheror/users/gone", _) => (404, "", Map.empty)
    case _ => (500, "unexpected", Map.empty)
  }

  test("an affiliate's login is made with the original's attributes, and given and taken a brand role") {
    val stub = realm
    try
      val kc = KeycloakAdmin.http(stub.url, "eitheror", creds)
      val (first, last) = KeycloakAdmin.names(Some("Ada King Lovelace"))
      assertEquals((first, last), (Some("Ada"), Some("King Lovelace")))
      assertEquals(kc.createUser("ada@example.com", first, last, KeycloakAdmin.affiliateAttributes("aff-1")), Right("u-1"))
      val created = stub.seen.find(s => s.method == "POST" && s.path.endsWith("/users")).get.body
      assert(created.contains(""""eitheror_scope_id":["aff-1"]""") && created.contains(""""eitheror_actor_type":["affiliate"]"""), created)
      assertEquals(kc.assignRole("u-1", "brand_goldvibe"), Right(()))
      assertEquals(kc.removeRole("u-1", "brand_goldvibe"), Right(()))
      assertEquals(stub.seen.filter(_.path.endsWith("role-mappings/realm")).map(_.method).toVector, Vector("POST", "DELETE"))
      assertEquals(kc.findByEmail("ada@example.com"), Right(Some("u-1")))
      assertEquals(kc.deleteUser("gone"), Right(()))
    finally stub.stop()
  }

  test("an email the realm holds is AlreadyExists; no credentials is NotConfigured") {
    val stub = realm
    try
      assertEquals(KeycloakAdmin.http(stub.url, "eitheror", creds).createUser("taken@example.com", None, None, Map.empty),
        Left(RealmError.AlreadyExists("taken@example.com")))
      assert(KeycloakAdmin.http(stub.url, "eitheror", () => None).deleteUser("u").isLeft)
    finally stub.stop()
  }

  test("one admin token serves its life; a 401 fetches another once; a body that is not JSON is a failure, not a throw") {
    var rejectNext = false
    val stub = Stub {
      case ("POST", "/realms/master/protocol/openid-connect/token", _) => (200, """{"access_token":"t","expires_in":300}""", Map.empty)
      case ("GET", "/admin/realms/eitheror/users/u-2", _) if rejectNext => rejectNext = false; (401, "", Map.empty)
      case ("GET", "/admin/realms/eitheror/users/u-2", _) => (200, "<html>a proxy's page</html>", Map.empty)
      case ("GET", "/admin/realms/eitheror/roles/broken", _) => (200, "not json", Map.empty)
      case ("GET", p, _) if p.startsWith("/admin/realms/eitheror/users?email=") => (200, "[]", Map.empty)
      case _ => (500, "unexpected", Map.empty)
    }
    try
      val kc = KeycloakAdmin.http(stub.url, "eitheror", creds)
      (1 to 3).foreach(_ => kc.findByEmail("x@example.com"): Unit)
      def grants = stub.seen.count(_.path.endsWith("/token"))
      assertEquals(grants, 1)
      assert(kc.assignRole("u-1", "broken").isLeft)
      assert(kc.updateEmail("u-2", "y@example.com").isLeft)
      rejectNext = true
      assert(kc.updateEmail("u-2", "y@example.com").isLeft)
      assertEquals(grants, 2)
    finally stub.stop()
  }
