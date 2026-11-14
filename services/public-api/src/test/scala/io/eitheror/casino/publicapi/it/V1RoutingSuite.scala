package io.eitheror.casino.publicapi.it

/** The `/v1` endpoint's shape (contracts/routes.md): the public routes, the methods, the unknown paths. */
class V1RoutingSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  test("/v1/openapi.json is the served contract, with no key") {
    val r = kit.v1("/openapi.json")
    assertEquals(r.status, 200)
    assertEquals(r.json.path("info").path("version").asText(), "1.1.0")
    assertEquals(r.json.path("servers").get(0).path("url").asText(), "https://public-api-goldvibe.example/v1")
  }

  test("/v1/docs is the reference page, with no key") {
    val r = kit.v1("/docs")
    assertEquals(r.status, 200)
    assert(r.text.contains("Casino public API"))
  }

  test("any method but GET on a contract path is 405 invalid_request, Only GET is supported") {
    Vector("POST", "PUT", "PATCH", "DELETE").foreach { m =>
      Vector("/players", "/players/00000000-0000-0000-0000-000000000001/sessions", "/openapi.json").foreach { p =>
        val r = kit.v1(p, method = m)
        assertEquals(r.status, 405, s"$m $p")
        assertEquals(r.error, "invalid_request")
        assertEquals(r.json.path("message").asText(), "Only GET is supported")
      }
    }
  }

  test("an unknown path is 401 key_missing without a key and 404 not_found with one") {
    Vector("/nothing", "/nothing/here", "/players/x/unknown").foreach { p =>
      assertEquals(kit.v1(p).error, "key_missing", p)
      assertEquals(kit.v1(p).status, 401, p)
      assertEquals(kit.v1(p, key = Some("ak_x_y")).status, 404, p)
    }
  }

  test("/health answers ok with no key") {
    val r = kit.request("GET", "/health")
    assertEquals(r.status, 200)
    assertEquals(r.json.path("status").asText(), "ok")
  }
