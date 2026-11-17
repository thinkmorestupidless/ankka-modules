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
