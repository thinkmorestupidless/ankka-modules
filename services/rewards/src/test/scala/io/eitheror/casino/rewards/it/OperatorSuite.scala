package io.eitheror.casino.rewards.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import scala.jdk.CollectionConverters.*

/**
 * The operator's routes of US6 (FR-017; contracts/routes.md `RewardAdminEndpoint`) over the whole service: the brand's
 * list for any operator of the brand, filtered and paged; a player's every reward with the primary and the wagering
 * from the wallet; one reward; and the award in its three shapes, each the operator's override (rules 1 to 4).
 */
class OperatorSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def admin = kit.asOperator()
  private def viewer = kit.asOperator("support_agent", "op-viewer")

  private def get(path: String, headers: Seq[(String, String)] = admin): Reply = kit.request("GET", kit.grantsPath(path), None, headers)
  private def give(player: String, fields: (String, Any)*): Reply =
    kit.json("POST", kit.grantsPath(s"/$player"), Json.obj(fields*), admin)

  private def items(r: Reply): Vector[JsonNode] = r.json.get("items").elements.asScala.toVector
  private def rows(player: String) = RewardsView.byPlayer(kit.views.forView(RewardsView), player)

  // ── who may call ──────────────────────────────────────────────────────────────────────────────

  test("any operator of the brand reads the list; reading one reward and awarding need an admin role"):
    val p = paths.newPlayer("op-p")
    assertEquals(get("", viewer).status, 200)
    assertEquals(get("/no-such-reward", viewer).str("error"), "forbidden")
    val r = kit.json("POST", kit.grantsPath(s"/$p"), Json.obj("offerId" -> paths.noDeposit()), viewer)
    assertEquals((r.status, r.str("error")), (403, "forbidden"), r.text)
    assertEquals(kit.request("GET", kit.grantsPath(""), None, kit.asPlayer(p)).status, 403)
    assertEquals(kit.request("GET", kit.grantsPath(""), None, kit.asService("engagement")).status, 401)

  // ── the lists ─────────────────────────────────────────────────────────────────────────────────

  test("the brand's list across players, newest first, filterable by state, offer, player and search, and paged"):
    val offer = paths.noDeposit()
    val a     = paths.newPlayer("op-a")
    val b     = paths.newPlayer("op-b")
    paths.holdingActive(a, "name" -> paths.unique("Listed")): Unit
    paths.target(b, offer): Unit
    assertEquals(give(b, "offerId" -> offer).status, 200)
    paths.active(b, offer): Unit
    val c = paths.newPlayer("op-c")
    paths.target(c, offer): Unit
    kit.eventually("listed")(Some(rows(c)).filter(_.nonEmpty))
    val active = items(get(s"?status=ACTIVE&offerId=$offer&pageSize=200"))
    assertEquals(active.flatMap(_.text("playerId")), Vector(b))
    assertEquals(items(get(s"?offerId=$offer&status=all")).flatMap(_.text("playerId")).toSet, Set(b, c))
    val found = get(s"?search=${c.drop(3).toUpperCase}")
    assertEquals(items(found).flatMap(_.text("playerId")), Vector(c), found.text)
    // An operator's award is a reward of its own: b's targeted reward is still theirs to select.
    val page = get(s"?offerId=$offer&status=AVAILABLE&page=2&pageSize=1")
    assertEquals((page.json.long("total"), page.json.long("page"), page.json.long("pageSize")), (Some(2L), Some(2L), Some(1L)))
    assertEquals(items(page).flatMap(_.text("playerId")), Vector(b))
    val row = items(get(s"?offerId=$offer&playerId=$b&status=ACTIVE")).head
    assertEquals((row.text("status"), row.long("faceValue"), row.text("currency"), row.text("source")),
      (Some("ACTIVE"), Some(1000L), Some("EUR"), Some(Source.OperatorManual.wire)))
    assert(row.text("grantedAt").isDefined && row.text("expiresAt").isDefined, row.toString)

  test("a pageSize outside 1 to 200 is 400 naming it"):
    Seq("0", "201", "x").foreach { n =>
      val r = get(s"?pageSize=$n")
      assertEquals((r.status, r.str("error")), (400, "pageSize"), r.text)
    }

  test("a player's every reward, newest first, with the primary and the wagering the wallet reports"):
    val p      = paths.newPlayer("op-p")
    val first  = paths.holdingActive(p)
    val second = paths.holdingActive(p)
    val third  = paths.noDeposit()
    paths.target(p, third): Unit
    kit.eventually("three rewards")(Some(rows(p)).filter(_.size == 3))
    val r    = get(s"?playerId=$p")
    val list = items(r)
    assertEquals(list.flatMap(_.text("offerId")), Vector(third, second, first), r.text)
    val records = kit.wallet.open(p, "EUR").sortBy(-_.priority)
    val primary = records.head.grantRef
    assertEquals(list.filter(_.bool("isPrimary").contains(true)).flatMap(_.text("rewardId")), Vector(primary))
    assertEquals(list.head.get("isPrimary").asBoolean, false)
    assertEquals(list.head.get("wageredAmount").isNull, true)
    assertEquals(list(1).long("wageringRequirement"), Some(10000L))
    assertEquals(list(1).long("wageredAmount"), Some(0L))
    assertEquals(r.json.get("unmatchedRounds").size, 0)

  test("one reward, by id; one this service does not hold is CLAIM_NOT_FOUND (404)"):
    val p = paths.newPlayer("op-p")
    val offer = paths.holdingActive(p)
    val id    = paths.active(p, offer).rewardId
    val r     = get(s"/$id")
    assertEquals((r.status, r.str("rewardId"), r.str("status"), r.str("offerId"), r.json.long("faceValue")),
      (200, id, "ACTIVE", offer, Some(1000L)), r.text)
    assert(r.json.get("freeSpins").isNull, r.text)
    val gone = get("/no-such-reward")
    assertEquals((gone.status, gone.str("error")), (404, "CLAIM_NOT_FOUND"), gone.text)

  // ── the award: an offer ───────────────────────────────────────────────────────────────────────

  test("an offer awarded by an operator: sized by the deposit given, else its fixed amount; named by the operator"):
    val offer = paths.depositMatch()
    val p     = paths.newPlayer("op-p")
    val r     = give(p, "offerId" -> offer, "depositAmount" -> 3000L)
    assertEquals((r.status, r.str("status"), r.json.long("bonusAmount"), r.json.long("wageringRequired")),
      (200, "granted", Some(3000L), Some(90000L)), r.text)
    val reward = paths.active(p, offer)
    assertEquals((reward.source, reward.openedBy), (Source.OperatorManual, Some("op-1")))
    val q = paths.newPlayer("op-q")
    assertEquals(give(q, "offerId" -> offer).json.long("bonusAmount"), Some(50000L))

  test("an unknown offer is offer_not_found (404); an inactive one is awarded, as the original's"):
    val r = give(paths.newPlayer("op-p"), "offerId" -> "no-such-offer")
    assertEquals((r.status, r.str("error")), (404, "offer_not_found"), r.text)
    val offer = paths.noDeposit()
    kit.request("DELETE", kit.offersPath(s"/$offer"), None, admin): Unit
    assertEquals(give(paths.newPlayer("op-p"), "offerId" -> offer).status, 200)

  test("a deposit that is not whole minor units is refused naming it"):
    val r = give(paths.newPlayer("op-p"), "offerId" -> paths.depositMatch(), "depositAmount" -> 30.5)
    assertEquals((r.status, r.str("error"), r.str("field")), (400, "amount_not_minor_units", "depositAmount"), r.text)

  test("the wallet refusing the award is 409 {status: ineligible, reason: <its code>}"):
    kit.wallet.routeAnswer("awards", FakeWallet.refusal("BONUS_ALREADY_ACTIVE"))
    try
      val r = give(paths.newPlayer("op-p"), "offerId" -> paths.noDeposit())
      assertEquals((r.status, r.str("status"), r.str("reason")), (409, "ineligible", "BONUS_ALREADY_ACTIVE"), r.text)
    finally kit.wallet.clearRoute("awards")

  // ── the award: a free bonus ───────────────────────────────────────────────────────────────────

  test("a free bonus with no offer: manual:<uuid> of type MANUAL, the amount and wagering given, a 30-day window"):
    val p = paths.newPlayer("op-p")
    val r = give(p, "bonusAmount" -> 2000L, "wager" -> 5, "currency" -> "EUR")
    assertEquals((r.status, r.str("status"), r.json.long("bonusAmount"), r.json.long("wageringRequired")),
      (200, "granted", Some(2000L), Some(10000L)), r.text)
    val row = kit.eventually("listed")(rows(p).headOption.filter(_.status == RewardStatus.Active.wire))
    assert(row.offerId.startsWith("manual:"), row.offerId)
    assertEquals((row.offerType, row.offerName, row.source), ("MANUAL", "Free Bonus", Source.OperatorManual.wire))
    val record = kit.wallet.open(p, "EUR").head
    assertEquals((record.grantRef, record.bonusRef, record.amount, record.requirement), (row.rewardId, row.offerId, 2000L, 10000L))
    val s = paths.reward(row.rewardId)
    assertEquals((s.maxConvertible, s.openedBy), (2000L, Some("op-1")))
    assertEquals(java.time.Duration.between(s.grantedAt.get, s.expiresAt.get).toDays, 30L)

  test("a free bonus without an amount or a wager is refused; a suspected duplicate is ineligible"):
    val p = paths.newPlayer("op-p")
    Seq(Json.obj("wager" -> 5, "currency" -> "EUR"), Json.obj("bonusAmount" -> 2000L, "currency" -> "EUR")).foreach { b =>
      val r = kit.json("POST", kit.grantsPath(s"/$p"), b, admin)
      assertEquals((r.status, r.str("error")), (400, "bonusAmount and wager are required"), r.text)
    }
    paths.checked(p)
    paths.flagged(p)
    val r = give(p, "bonusAmount" -> 2000L, "wager" -> 5, "currency" -> "EUR")
    assertEquals((r.status, r.str("status"), r.str("reason")), (409, "ineligible", "SUSPECTED_DUPLICATE"), r.text)

  test("a free bonus with no currency named is in the player's currency, and refused when it is unknown"):
    val r = give(paths.newPlayer("op-p"), "bonusAmount" -> 2000L, "wager" -> 5)
    assertEquals((r.status, r.str("error")), (422, "player_currency_unresolved"), r.text)

  // ── the award: free rounds ────────────────────────────────────────────────────────────────────

  test("free rounds with no offer are refused for each missing field, in the original's order"):
    val p = paths.newPlayer("op-p")
    val full = Vector[(String, Any)]("type" -> "FREE_SPINS", "gameId" -> "g-1", "provider" -> "st8", "spinCount" -> 10, "spinValue" -> 20L,
      "winningsDestination" -> "bonus_balance", "maxConvertibleAmount" -> 5000L, "wageringMultiplier" -> 5)
    def without(field: String) = full.filterNot(_._1 == field)
    Seq("gameId" -> "game_id_required", "spinCount" -> "spin_count_required", "spinValue" -> "spin_value_required",
      "maxConvertibleAmount" -> "max_convertible_amount_required").foreach { (field, code) =>
      val r = give(p, without(field)*)
      assertEquals((r.status, r.str("error")), (400, code), s"$field: ${r.text}")
    }

  test("free rounds with no offer are created at the provider under the reward's id (task T054)".pending("T054")):
    ()

  test("an offer of free rounds awarded by an operator answers {status: granted, grantId, type, providerStatus} (task T054)"
      .pending("T054")):
    ()
