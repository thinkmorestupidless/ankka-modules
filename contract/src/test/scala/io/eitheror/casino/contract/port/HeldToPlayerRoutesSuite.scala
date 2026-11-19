package io.eitheror.casino.contract.port

import java.nio.file.Path

/**
 * The held kit's rule (spec 031 T034): a violation counts when its route is a kept operation, the
 * brand in place of `the-brand`. Over the description-check fixture, whose one tagged route is the
 * balance and whose other is the transactions; a third, operator, route is in neither.
 */
class HeldToPlayerRoutesSuite extends munit.FunSuite:

  private val description = Path.of(getClass.getResource("/description-check/held.player.json").toURI)
  private val held        = HeldToPlayerRoutes(description, "goldvibe")

  private val balance  = "GET /v1/casino/players/goldvibe/wallets/{currency}/balance: answered 409, which its description does not name (200, 404)"
  private val operator = "GET /v1/casino/operator/goldvibe/wallets/{player}: answered 409, which its description does not name (200)"
  private val other    = "GET /v1/casino/players/other-brand/wallets/{currency}/balance: answered 409, which its description does not name (200)"

  test("the kept operations are named as HeldToDocument names a route, the kit's brand in place of the-brand") {
    assertEquals(held.kept, Set("GET /v1/casino/players/goldvibe/wallets/{currency}/balance"))
  }

  test("a violation of a kept route counts; one of a route the description does not keep, or of another brand, does not") {
    assertEquals(HeldToPlayerRoutes.relevant(Vector(balance, operator, other), held.kept), Vector(balance))
  }

  test("with no violation recorded, the kit is held") {
    held.assertHeld()
  }
