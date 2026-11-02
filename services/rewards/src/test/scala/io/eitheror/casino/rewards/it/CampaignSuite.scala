package io.eitheror.casino.rewards.it

import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.features.Scene
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Duration as JDuration

/**
 * The campaign routes (FR-022; contracts/routes.md `CampaignEndpoint`) over the whole service, called as the brand's
 * `engagement`: the target route by the offer's role, refusing rounds and an unknown role, and idempotent; the award
 * route resolving a stale offer by its role once, answering `alreadyProcessed` for a claim the player holds, a
 * decline's reason alone, and sizing a deposit match from the player's last deposit, never from the caller.
 */
class CampaignSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def engagement = kit.asService("engagement")

  private def targetRole(player: String, role: String, headers: Seq[(String, String)] = engagement): Reply =
    kit.json("POST", kit.campaignsPath(s"/$player/target"), Json.obj("role" -> role), headers)

  private def award(player: String, fields: (String, Any)*): Reply =
    kit.json("POST", kit.campaignsPath(s"/$player/award"), Json.obj(fields*), engagement)

  private def awardsOf(player: String) = kit.wallet.calls("awards").filter(_.path.contains(s"/wallets/$player/"))

  private def rows(player: String) = RewardsView.byPlayer(kit.views.forView(RewardsView), player)

  /** A free-rounds offer on a game the provider lists as eligible, at a spin value it offers. */
  private def rounds(fields: (String, Any)*): String =
    val game = paths.unique("g")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.10", "0.20")
    paths.offer((Vector[(String, Any)]("name" -> paths.unique("Spins"), "type" -> "FREE_SPINS", "currency" -> "EUR", "gameId" -> game,
      "gameName" -> "Book", "provider" -> "st8", "spinCount" -> 10, "spinValue" -> 20L) ++ fields)*)

  // ── who may call ──────────────────────────────────────────────────────────────────────────────

  test("only the brand's engagement may call the campaign routes"):
    val p    = paths.newPlayer("ca-p")
    val role = Scene.roleOf(kit, paths.noDeposit())
    assertEquals(targetRole(p, role, kit.asService("payouts")).status, 403)
    assertEquals(targetRole(p, role, kit.asOperator()).status, 403)
    assertEquals(targetRole(p, role).status, 200)

  // ── the target route ──────────────────────────────────────────────────────────────────────────

  test("a target makes the offer of the role visible, AVAILABLE, from a campaign; a repeat changes nothing"):
    val p     = paths.newPlayer("ca-p")
    val offer = paths.noDeposit()
    val role  = Scene.roleOf(kit, offer)
    val first = targetRole(p, role)
    assertEquals((first.status, first.json.get("ok").asBoolean), (200, true), first.text)
    val again = targetRole(p, role)
    assertEquals((again.status, again.text), (first.status, first.text))
    val row = kit.eventually("the reward listed")(rows(p).find(_.offerId == offer))
    assertEquals((row.status, row.source), (RewardStatus.Available.wire, Source.CustomerioWebhook.wire))
    Thread.sleep(500)
    assertEquals(rows(p).count(_.offerId == offer), 1)
    assertEquals(paths.record(p).rewards.values.count(_.offerId == offer), 1)

  test("a target of a role no active offer holds is role_not_found (422)"):
    val r = targetRole(paths.newPlayer("ca-p"), paths.unique("nobody"))
    assertEquals((r.status, r.str("error")), (422, "role_not_found"), r.text)
    val gone = paths.noDeposit()
    val role = Scene.roleOf(kit, gone)
    kit.request("DELETE", kit.offersPath(s"/$gone"), None, kit.asOperator()): Unit
    assertEquals(targetRole(paths.newPlayer("ca-p"), role).str("error"), "role_not_found")

  test("a target of free rounds, or of a deposit match with free rounds, is free_spins_offer_not_targetable (422)"):
    val pure  = rounds()
    val mixed = rounds("type" -> "DEPOSIT_MATCH_WITH_FREE_SPINS", "bonusPercentage" -> 10000L, "maxBonusValue" -> 50000L,
      "wageringMultiplier" -> 30L)
    Seq(pure, mixed).foreach { id =>
      val p = paths.newPlayer("ca-p")
      val r = targetRole(p, Scene.roleOf(kit, id))
      assertEquals((r.status, r.str("error")), (422, "free_spins_offer_not_targetable"), r.text)
      assertEquals(paths.record(p).rewards, Map.empty)
    }

  test("an offer with no currency is opened in the player's currency as registered"):
    val p = paths.newPlayer("ca-p")
    paths.players(p, io.eitheror.casino.contract.facts.PlayerRegistered(p, kit.now(), None, None, None, Some("GB"), Some("GBP"), None,
      kit.now()))
    paths.awaitRecord(p, "registered")(_.currency.contains("GBP")): Unit
    val offer = paths.noDeposit("currency" -> null)
    val id    = Scene.target(kit, p, offer)
    assertEquals(paths.reward(id).currency, "GBP")

  // ── the award route ───────────────────────────────────────────────────────────────────────────

  test("an award of a free bonus: {ok, bonusAmount} with the awarded amount; a repeat is alreadyProcessed and awards nothing"):
    val p     = paths.newPlayer("ca-p")
    val offer = paths.noDeposit("maxBonusValue" -> 1500L)
    val r     = award(p, "offerId" -> offer)
    assertEquals((r.status, r.json.get("ok").asBoolean, r.json.get("bonusAmount").asLong), (200, true, 1500L), r.text)
    val rewarded = paths.active(p, offer)
    assertEquals(rewarded.source, Source.CustomerioWebhook)
    val again = award(p, "offerId" -> offer)
    assertEquals((again.status, again.json.get("alreadyProcessed").asBoolean), (200, true), again.text)
    assertEquals(awardsOf(p).size, 1)

  test("an award of an offer carrying a code does not ask for the code; one targeted first claims that reward"):
    val p     = paths.newPlayer("ca-p")
    val offer = paths.noDeposit(paths.codes(paths.unique("CAMP")))
    val id    = Scene.target(kit, p, offer)
    assertEquals(award(p, "offerId" -> offer).status, 200)
    assertEquals(paths.until(id, "active")(_.status == RewardStatus.Active).rewardId, id)

  test("an unknown offer is offer_not_found, an inactive one offer_not_active; a role names the offer to fall back to, once"):
    val p = paths.newPlayer("ca-p")
    assertEquals(award(p, "offerId" -> "no-such-offer").str("error"), "offer_not_found")
    val old = paths.noDeposit()
    kit.request("DELETE", kit.offersPath(s"/$old"), None, kit.asOperator()): Unit
    val stale = award(p, "offerId" -> old)
    assertEquals((stale.status, stale.str("error")), (422, "offer_not_active"), stale.text)
    assertEquals(award(p, "offerId" -> old, "role" -> paths.unique("nobody")).str("error"), "offer_not_active")
    val current = paths.noDeposit()
    val role    = Scene.roleOf(kit, current)
    val r       = award(p, "offerId" -> old, "role" -> role)
    assertEquals(r.status, 200, r.text)
    paths.active(p, current): Unit
    assertEquals(award(paths.newPlayer("ca-p"), "offerId" -> "", "role" -> role).status, 200)

  test("a deposit match is sized from the player's last confirmed deposit, never from the caller"):
    val p     = paths.newPlayer("ca-p")
    val offer = paths.depositMatch()
    paths.deposit(p, 4000L): Unit
    paths.awaitRecord(p, "the deposit")(_.lastDeposit.exists(_.amount == 4000L)): Unit
    val r = award(p, "offerId" -> offer, "depositAmount" -> 9000L, "amount" -> 9000L)
    assertEquals((r.status, r.json.get("bonusAmount").asLong), (200, 4000L), r.text)
    assertEquals(kit.wallet.open(p, "EUR").map(_.amount), Vector(4000L))

  test("a deposit match with no deposit seen is BELOW_MIN_DEPOSIT against a minimum; a decline is its reason alone"):
    val p = paths.newPlayer("ca-p")
    val r = award(p, "offerId" -> paths.depositMatch("minDepositAmount" -> 2000L))
    assertEquals((r.status, r.json.toString), (422, """{"error":"BELOW_MIN_DEPOSIT"}"""))

  test("an offer with no currency and a player whose currency is unknown is player_currency_unresolved; one sent is used"):
    val offer = paths.noDeposit("currency" -> null)
    val r     = award(paths.newPlayer("ca-p"), "offerId" -> offer)
    assertEquals((r.status, r.str("error")), (422, "player_currency_unresolved"), r.text)
    val p = paths.newPlayer("ca-p")
    assertEquals(award(p, "offerId" -> offer, "currency" -> "GBP").status, 200)
    assertEquals(kit.wallet.open(p, "GBP").size, 1)

  test("the wallet refusing the award is grant_failed (422), as the original's"):
    val p = paths.newPlayer("ca-p")
    kit.wallet.routeAnswer("awards", FakeWallet.refusal("BONUS_ALREADY_ACTIVE"))
    try
      val r = award(p, "offerId" -> paths.noDeposit())
      assertEquals((r.status, r.str("error")), (422, "grant_failed"), r.text)
    finally kit.wallet.clearRoute("awards")

  test("ELIGIBILITY_CHECK_UNAVAILABLE is 503 for the campaign to retry; the retry once the check ends is awarded"):
    val p     = paths.newPlayer("ca-p")
    val offer = paths.noDeposit()
    paths.checkStarted(p, kit.now())
    val r = award(p, "offerId" -> offer)
    assertEquals((r.status, r.str("error")), (503, "ELIGIBILITY_CHECK_UNAVAILABLE"), r.text)
    paths.checkEnded(p)
    assertEquals(award(p, "offerId" -> offer).status, 200)

  test("a claim window counted from registration applies to the campaign's award"):
    val p = paths.newPlayer("ca-p")
    paths.registered(p, paths.ago(JDuration.ofDays(3)))
    assertEquals(award(p, "offerId" -> paths.noDeposit("claimWindowDays" -> 1)).str("error"), "CLAIM_WINDOW_EXPIRED")

  test("an award of free rounds answers {ok, grantId, providerStatus}: the reward ACTIVE, created at the provider under its id"):
    val p     = paths.newPlayer("ca-p")
    val offer = rounds()
    val r     = award(p, "offerId" -> offer)
    assertEquals((r.status, r.json.get("ok").asBoolean, r.str("providerStatus")), (200, true, "active"), r.text)
    val id = r.str("grantId")
    val s  = paths.reward(id)
    assertEquals((s.status, s.offerId, s.source, s.faceValue), (RewardStatus.Active, offer, Source.CustomerioWebhook, 0L))
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(id)), 1)
    assertEquals(awardsOf(p), Vector.empty)

  test("an award of free rounds the player already holds is alreadyProcessed and creates nothing more"):
    val p     = paths.newPlayer("ca-p")
    val offer = rounds()
    assertEquals(award(p, "offerId" -> offer).status, 200)
    val again = award(p, "offerId" -> offer)
    assertEquals((again.status, again.json.get("alreadyProcessed").asBoolean), (200, true), again.text)
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("playerId").contains(p)), 1)

  test("free rounds the provider rejects are 502 provider_rejected naming the grant; a game not eligible is 422"):
    val offer = rounds()
    kit.provider.createAnswer = Some(FakeGameProvider.status("rejected"))
    try
      val r = award(paths.newPlayer("ca-p"), "offerId" -> offer)
      assertEquals((r.status, r.str("error")), (502, "provider_rejected"), r.text)
      assertEquals(paths.reward(r.str("grantId")).terminationReason, Some("provider_failed"))
    finally kit.provider.createAnswer = None
    kit.provider.game(paths.offerOf(offer).gameId.get)
    val r = award(paths.newPlayer("ca-p"), "offerId" -> offer)
    assertEquals((r.status, r.str("error")), (422, "game_not_eligible"), r.text)
