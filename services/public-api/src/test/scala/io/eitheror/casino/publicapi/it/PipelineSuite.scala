package io.eitheror.casino.publicapi.it

/** Original `test/requestPipeline.test.ts`: the order of refusals (contracts/routes.md's pipeline). */
class PipelineSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  private lazy val key = Keys.noFields(kit)

  test("the public routes need no key") {
    Vector("/v1/openapi.json", "/v1/docs").foreach(p => assertEquals(kit.request("GET", p).status, 200, p))
    assertEquals(kit.request("GET", "/health").status, 200)
  }

  test("the key is checked before the path id: no key is key_missing, a bad key key_invalid") {
    val none = kit.v1("/players/not-a-uuid")
    assertEquals((none.status, none.error), (401, "key_missing"))
    val bad = kit.v1("/players/not-a-uuid", Some("ak_nope"))
    assertEquals((bad.status, bad.error), (401, "key_invalid"))
    val d   = Keys.disabled(kit)
    val off = kit.v1("/players/not-a-uuid", Some(d.secret))
    assertEquals((off.status, off.error), (403, "key_disabled"))
  }

  test("an admitted key with a malformed path id is 400 naming the id") {
    Vector("/players/not-a-uuid" -> "playerId", "/sessions/123" -> "sessionId", "/rounds/x/bets" -> "roundId",
      "/bets/ABC" -> "betId", "/transactions/0000" -> "transactionId", "/bonus-awards/b" -> "bonusAwardId",
      "/players/p/stats" -> "playerId", s"/games/${"g" * 65}" -> "gameId", "/games/a%20b/stats" -> "gameId").foreach { (path, name) =>
      val r = kit.v1(path, Some(key.secret))
      assertEquals((r.status, r.error), (400, "invalid_request"), path)
      assertEquals(r.json.path("message").asText(), s"$name is not a valid identifier", path)
    }
  }

  test("an unknown path is 401 without a key and 404 with one") {
    Vector("/nothing", "/players/a/b", "/a/b", "/games/a/b").foreach { p =>
      assertEquals(kit.v1(p).status, 401, p)
      val r = kit.v1(p, Some(key.secret))
      assertEquals((r.status, r.error), (404, "not_found"), p)
    }
  }

  test("a write is 405 invalid_request, with or without a key") {
    Vector("/players", "/players/00000000-0000-0000-0000-000000000000", "/contract").foreach { p =>
      val r = kit.v1(p, Some(key.secret), "POST")
      assertEquals((r.status, r.error, r.json.path("message").asText()), (405, "invalid_request", "Only GET is supported"), p)
    }
  }

  test("every refusal is application/json with an error and no more than a message") {
    val r = kit.v1("/players/x", Some(key.secret))
    assert(r.header("content-type").exists(_.startsWith("application/json")))
    assertEquals(Kit.mapper.readTree(r.text).fieldNames().next(), "error")
    assert(r.json.size() <= 2)
  }
