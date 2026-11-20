package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** What goes into a Keycloak admin URL from a caller (spec 030 H6): one segment, never a path. */
class KeycloakAdminSuite extends munit.FunSuite with LogCapturing:

  test("a value is one path segment: /, ?, #, % and a space are encoded, and . and .. are no steps") {
    assertEquals(KeycloakAdmin.segment("6f1c2d3e-0000-4000-8000-000000000001"), "6f1c2d3e-0000-4000-8000-000000000001")
    assertEquals(KeycloakAdmin.segment("../../users"), "..%2F..%2Fusers")
    assertEquals(KeycloakAdmin.segment("a b?c#d%e"), "a%20b%3Fc%23d%25e")
    assertEquals(KeycloakAdmin.segment(".."), "%2E%2E")
    assertEquals(KeycloakAdmin.segment("."), "%2E")
  }

  test("an operator id is the realm's shape: letters, digits, - and _, at most 64") {
    assert(KeycloakAdmin.validUserId("6f1c2d3e-0000-4000-8000-000000000001"))
    assert(KeycloakAdmin.validUserId("op_7"))
    for bad <- Vector("", "..", "a/b", "a b", "a%2Fb", "é", "x" * 65) do assert(!KeycloakAdmin.validUserId(bad), bad)
  }

  test("an answer the realm may give again is unreachable; any other refusal is refused (spec 032 R5)") {
    for s <- Vector(500, 502, 503, 504, 429) do
      val e = RealmError.of(s, "busy", "roles of u-1")
      assert(e.isInstanceOf[RealmError.Unreachable], s"$s: $e")
      assert(RealmError.retryable(e), s.toString)
    for s <- Vector(400, 401, 403, 404, 409, 422) do
      val e = RealmError.of(s, "{\"error\":\"no\"}", "roles of u-1")
      assertEquals(e.asInstanceOf[RealmError.Refused].status, s)
      assert(!RealmError.retryable(e), s.toString)
    assert(RealmError.retryable(RealmError.NotConfigured("no credentials")))
    assert(!RealmError.retryable(RealmError.NotFound("u-1")))
  }

  test("a role named for a brand is one path segment: its @ reaches the one role") {
    assertEquals(KeycloakAdmin.segment("brand_admin@goldvibe"), "brand_admin%40goldvibe")
    assertEquals(KeycloakAdmin.segment("a b@x/y"), "a%20b%40x%2Fy")
  }
