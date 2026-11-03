package io.eitheror.casino.rewards.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.features.Scene

import scala.jdk.CollectionConverters.*

/**
 * The player's lists of US6 (FR-004, FR-022a): the `available` state lists the offers made visible to the player in
 * the brand's order with the reason each is visible; `GET /rewards/{playerId}/grants` answers the player's rewards to
 * the player and to `support-agent` by its identity, with the wallet's wagering.
 */
class PlayerGrantsSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def grants(player: String, headers: Seq[(String, String)]): Reply =
    kit.request("GET", kit.rewardsPath(s"/$player/grants"), None, headers)

  private def elements(n: JsonNode): Vector[JsonNode] = n.elements.asScala.toVector

  test("the available list is in the brand's order, each offer with the reason it is visible"):
    val p = paths.newPlayer("pg-p")
    val first  = paths.noDeposit()
    val second = paths.noDeposit()
    val coded  = paths.unique("SEEN")
    val third  = paths.depositMatch(paths.codes(coded))
    Scene.target(kit, p, first): Unit
    Scene.target(kit, p, second): Unit
    val order = kit.json("PUT", kit.offersPath("/order"), Json.obj("orderedOfferIds" -> Json.arr(second, third, first)), kit.asOperator())
    assertEquals(order.status, 200, order.text)
    val state = paths.state(p)
    assertEquals(state.text("state"), Some("available"))
    val offers = elements(state.get("visibleOffers"))
    assertEquals(offers.flatMap(_.text("offerId")), Vector(second, first))
    assertEquals(offers.flatMap(_.text("source")).distinct, Vector("customerio_webhook"))
    assertEquals(paths.enterCode(p, coded).status, 200)
    val pending = paths.state(p)
    assertEquals((pending.text("state"), pending.get("selected").text("source")), (Some("pending"), Some("player_code")))
    assertEquals(elements(pending.get("competingOffers")).flatMap(_.text("offerId")), Vector(second, first))

  test("the player reads their own grants; another player's are player_id_mismatch"):
    val p     = paths.newPlayer("pg-p")
    val offer = paths.holdingActive(p)
    val r     = grants(p, kit.asPlayer(p))
    assertEquals(r.status, 200, r.text)
    val g = elements(r.json).head
    assertEquals((g.text("offerId"), g.text("status"), g.long("wageringRequirement"), g.long("releaseProgress"), g.text("currency")),
      (None, Some("ACTIVE"), Some(10000L), Some(0L), Some("EUR")))
    assertEquals(g.text("rewardId"), Some(paths.active(p, offer).rewardId))
    assert(g.text("offerName").isDefined && g.text("expiresAt").isDefined, g.toString)
    val other = grants(p, kit.asPlayer(paths.newPlayer("pg-q")))
    assertEquals((other.status, other.str("error")), (403, "player_id_mismatch"), other.text)

  test("support-agent reads a player's grants by its identity, with no token; another service may not"):
    val p = paths.newPlayer("pg-p")
    paths.holdingActive(p): Unit
    val r = grants(p, kit.asService("support-agent"))
    assertEquals((r.status, r.json.size), (200, 1), r.text)
    assertEquals(grants(p, kit.asService("engagement")).status, 401)
    assertEquals(grants(p, kit.asService("support-agent", project = "other-brand")).status, 401)
    assertEquals(grants(p, kit.asOperator()).status, 403)

  test("the progress is the wallet's, for a reward with an open record"):
    val p  = paths.newPlayer("pg-p")
    val id = paths.active(p, paths.holdingActive(p)).rewardId
    kit.wallet.routeAnswer("restricted-funds", Answer.ok(Json.obj("restrictedBalance" -> 800L, "records" -> Json.arr(
      Json.obj("recordId" -> id, "grantRef" -> id, "bonusRef" -> "o", "status" -> "active", "priority" -> 1L, "balance" -> 800L,
        "wageringRequirement" -> 12000L, "releaseProgress" -> 2500L, "maxConvertible" -> 1000L)))))
    try
      val g = elements(grants(p, kit.asService("support-agent")).json).head
      assertEquals((g.long("wageringRequirement"), g.long("releaseProgress")), (Some(12000L), Some(2500L)))
    finally kit.wallet.clearRoute("restricted-funds")
