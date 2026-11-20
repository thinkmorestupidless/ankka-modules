package io.eitheror.casino.backoffice

import com.thinkmorestupidless.ankka.http.RoutesOf

import java.net.URI

/** contracts/routes.md's `backoffice-state` section: its routes and no other. */
class RoutesSuite extends munit.FunSuite:

  private val settings = Settings("goldvibe", URI.create("https://auth.example/realms/eitheror"), "backoffice-goldvibe",
    URI.create("https://backoffice-goldvibe.example"))

  test("the service serves exactly the routes of the contract") {
    val routes = BackofficeStateService.endpoints(settings).map(_(RoutesOf.clients)).flatMap(RoutesOf(_)).toSet
    assertEquals(routes, Set(
      "POST /sessions",
      "POST /sessions/{id}/token",
      "DELETE /sessions/{id}",
      "GET /preferences/{subject}",
      "PUT /preferences/{subject}",
      "DELETE /subjects/{subject}"
    ))
  }

  test("a subject is one segment that makes an operator's data subject") {
    assert(Answers.validSubject("8c1f2a90-1b2c-4d5e-8f00-123456789abc"))
    assert(!Answers.validSubject(""))
    assert(!Answers.validSubject("a/b"))
    assert(!Answers.validSubject("ada@example.com"))
  }
