package io.eitheror.casino.rewards.it

import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*

import java.util.UUID

/** `/rewards`' refusals and the state with nothing held (contracts/routes.md); the paths that award are offers.feature's. */
class RewardsRoutesSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player() = s"rr-p-${UUID.randomUUID().toString.take(8)}"

  test("a player id other than the token's is player_id_mismatch; an operator's token is refused"):
    val p = player()
    val r = kit.request("GET", kit.rewardsPath(s"/$p/state"), None, kit.asPlayer("someone-else"))
    assertEquals((r.status, r.str("error")), (403, "player_id_mismatch"))
    assertEquals(kit.request("GET", kit.rewardsPath(s"/$p/state"), None, kit.asOperator()).status, 403)

  test("a select without an offer id is 400; an offer not in the book or not held by the player is offer_not_visible"):
    val p = player()
    val none = kit.json("POST", kit.rewardsPath(s"/$p/select"), Json.obj(), kit.asPlayer(p))
    assertEquals((none.status, none.str("error")), (400, "offerId required"))
    val missing = kit.json("POST", kit.rewardsPath(s"/$p/select"), Json.obj("offerId" -> "nope"), kit.asPlayer(p))
    assertEquals((missing.status, missing.str("error")), (404, "offer_not_visible"))
    val offer = kit.json("POST", kit.offersPath(""), Json.obj("name" -> s"Free ${UUID.randomUUID()}", "type" -> "NO_DEPOSIT",
      "noDepositRequired" -> true, "maxBonusValue" -> 1000L, "wageringMultiplier" -> 10L, "currency" -> "EUR"), kit.asOperator())
    assertEquals(offer.status, 201, offer.text)
    val unseen = kit.json("POST", kit.rewardsPath(s"/$p/select"), Json.obj("offerId" -> offer.str("id")), kit.asPlayer(p))
    assertEquals((unseen.status, unseen.str("error")), (404, "offer_not_visible"))

  test("a player holding nothing reads available with no offers, and may deselect"):
    val p = player()
    val s = kit.request("GET", kit.rewardsPath(s"/$p/state"), None, kit.asPlayer(p))
    assertEquals((s.status, s.str("state"), s.json.objects("visibleOffers")), (200, "available", Vector.empty))
    val d = kit.request("POST", kit.rewardsPath(s"/$p/deselect"), None, kit.asPlayer(p))
    assertEquals((d.status, d.json.bool("ok")), (200, Some(true)))
