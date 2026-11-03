package io.eitheror.casino.rewards.it

import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.reward.RewardStatus

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * The open count of rule 4 (FR-005; research R3, `EE:164-165`) over the whole service: the player's open rewards, or
 * the wallet's active records from `GET W/restricted-funds` at evaluation if more, 0 when the wallet cannot be read;
 * an offer of one, two and no maximum; two selections at once; and the wallet refusing `BONUS_ALREADY_ACTIVE`, which
 * is recorded on the reward and leaves it as it was, and a later claim that is awarded.
 */
class ConcurrencySuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val http = HttpClient.newHttpClient()

  /** A record at the wallet this service did not award: another service's, or one from before the port. */
  private def foreignRecord(player: String, key: String): Unit =
    val body = Json.render(Json.obj("key" -> key, "bonusRef" -> "elsewhere", "amount" -> 500L, "wageringMultiplier" -> 1L))
    val r = http.send(HttpRequest.newBuilder(URI.create(s"${kit.wallet.address}/v1/casino/brands/${kit.slug}/wallets/$player/EUR/awards"))
      .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(r.statusCode, 200, r.body)

  private def endRecord(player: String, key: String): Unit =
    val body = Json.render(Json.obj("key" -> s"$key/forfeit", "grantRef" -> key))
    val r = http.send(HttpRequest.newBuilder(URI.create(s"${kit.wallet.address}/v1/casino/brands/${kit.slug}/wallets/$player/EUR/awards/forfeit"))
      .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(r.statusCode, 200, r.body)

  private def walletReadsActive(n: Int): Unit =
    val records = (1 to n).map(i => Json.obj("recordId" -> s"x-$i", "grantRef" -> s"x-$i", "bonusRef" -> "elsewhere", "status" -> "active",
      "priority" -> 0L, "balance" -> 100L, "wageringRequirement" -> 100L, "releaseProgress" -> 0L, "maxConvertible" -> 100L))
    kit.wallet.routeAnswer("restricted-funds", Answer.ok(Json.obj("records" -> Json.arr(records*), "restrictedBalance" -> 100L * n)))

  test("an offer allowing one open reward: refused beside a reward the player holds, or a record only the wallet holds"):
    val p = paths.newPlayer("cc-p")
    paths.holdingActive(p): Unit
    assertEquals(paths.viaSelect(p, paths.noDeposit("maxConcurrentGrants" -> 1)).refusal, Some("ACTIVE_BONUS_CONFLICT"))
    val q = paths.newPlayer("cc-q")
    walletReadsActive(1)
    val c = paths.viaSelect(q, paths.noDeposit("maxConcurrentGrants" -> 1))
    assertEquals((c.refusal, c.reply.map(_.status)), (Some("ACTIVE_BONUS_CONFLICT"), Some(409)))
    assert(kit.wallet.calls("restricted-funds").exists(_.path.contains(s"/wallets/$q/EUR/")))

  test("an offer allowing two: claimed beside one, refused beside two, the more of the player's and the wallet's counting"):
    val p = paths.newPlayer("cc-p")
    paths.holdingActive(p): Unit
    val second = paths.noDeposit("maxConcurrentGrants" -> 2)
    assertEquals(paths.viaSelect(p, second).refusal, None)
    paths.active(p, second): Unit
    assertEquals(paths.viaSelect(p, paths.noDeposit("maxConcurrentGrants" -> 2)).refusal, Some("ACTIVE_BONUS_CONFLICT"))
    val q = paths.newPlayer("cc-q")
    paths.holdingActive(q): Unit
    walletReadsActive(2)
    assertEquals(paths.viaSelect(q, paths.noDeposit("maxConcurrentGrants" -> 2)).refusal, Some("ACTIVE_BONUS_CONFLICT"))

  test("an offer setting no maximum is claimed beside any number"):
    val p = paths.newPlayer("cc-p")
    paths.holdingActive(p): Unit
    paths.holdingActive(p): Unit
    walletReadsActive(5)
    val id = paths.noDeposit()
    assertEquals(paths.viaSelect(p, id).refusal, None)
    kit.wallet.clearRoute("restricted-funds")
    paths.active(p, id): Unit

  test("a wallet that cannot be read counts no records (EE:501-512)"):
    val p = paths.newPlayer("cc-p")
    kit.wallet.routeAnswer("restricted-funds", Answer(500, """{"error":"boom"}"""))
    val id = paths.noDeposit("maxConcurrentGrants" -> 1)
    assertEquals(paths.viaSelect(p, id).refusal, None)
    paths.active(p, id): Unit

  test("two selections at once: the second is ACTIVE_BONUS_CONFLICT while the first is pending; after a deselect it may be selected"):
    val p      = paths.newPlayer("cc-p")
    val first  = paths.depositMatch()
    val second = paths.depositMatch()
    assertEquals(paths.viaSelect(p, first).refusal, None)
    val c = paths.viaSelect(p, second)
    assertEquals((c.refusal, c.reply.map(_.status)), (Some("ACTIVE_BONUS_CONFLICT"), Some(409)))
    assertEquals(paths.viaSelect(p, paths.noDeposit(), visible = false).refusal, Some("ACTIVE_BONUS_CONFLICT"))
    assertEquals(paths.rewardOf(p, first).status, RewardStatus.Pending)
    assertEquals(paths.state(p).get("state").asText, "pending")
    assertEquals(kit.request("POST", kit.rewardsPath(s"/$p/deselect"), None, kit.asPlayer(p)).status, 200)
    assertEquals(paths.select(p, second).status, 200)
    assertEquals(paths.rewardOf(p, second).status, RewardStatus.Pending)

  test("the wallet refusing BONUS_ALREADY_ACTIVE is recorded and leaves the reward available; a later claim is awarded"):
    val p = paths.newPlayer("cc-p")
    foreignRecord(p, s"$p-other")
    kit.wallet.routeAnswer("restricted-funds", Answer(500, """{"error":"boom"}"""))
    val offerId = paths.noDeposit("maxConcurrentGrants" -> 1)
    val first   = paths.target(p, offerId)
    val refused = paths.select(p, offerId)
    assertEquals((refused.status, refused.str("error")), (409, "BONUS_ALREADY_ACTIVE"), refused.text)
    val r = paths.until(first, "refusal recorded")(_.lastDecline.isDefined)
    assertEquals((r.status, r.lastDecline), (RewardStatus.Available, Some("BONUS_ALREADY_ACTIVE")))
    paths.awaitRecord(p, "the claim answered")(s => s.rewards.get(first).exists(h => !h.claiming)): Unit
    endRecord(p, s"$p-other")
    kit.wallet.clearRoute("restricted-funds")
    val again = paths.select(p, offerId)
    assertEquals(again.status, 200, again.text)
    val awarded = paths.active(p, offerId)
    assertNotEquals(awarded.rewardId, first)
    assertEquals(kit.wallet.open(p, "EUR").map(_.grantRef), Vector(awarded.rewardId))
    assertEquals(paths.until(first, "retired")(_.status == RewardStatus.Expired).terminationReason, Some("superseded"))
    val record = paths.record(p)
    assertEquals((record.rewards.keySet, record.retired), (Set(awarded.rewardId), Set(first)))

  test("on a deposit: the refusal leaves the selection pending with the code; the next deposit is awarded"):
    val p = paths.newPlayer("cc-p")
    foreignRecord(p, s"$p-other")
    kit.wallet.routeAnswer("restricted-funds", Answer(500, """{"error":"boom"}"""))
    val offerId = paths.depositMatch("maxConcurrentGrants" -> 1)
    val first   = paths.target(p, offerId)
    assertEquals(paths.select(p, offerId).status, 200)
    paths.deposit(p, 3000L): Unit
    val r = paths.until(first, "refusal recorded")(_.lastDecline.isDefined)
    assertEquals((r.status, r.lastDecline), (RewardStatus.Pending, Some("BONUS_ALREADY_ACTIVE")))
    paths.awaitRecord(p, "the claim answered")(s => s.rewards.get(first).exists(h => !h.claiming)): Unit
    assertEquals(paths.state(p).get("state").asText, "pending")
    endRecord(p, s"$p-other")
    kit.wallet.clearRoute("restricted-funds")
    paths.deposit(p, 4000L): Unit
    val awarded = paths.active(p, offerId)
    assertNotEquals(awarded.rewardId, first)
    assertEquals(awarded.faceValue, 4000L)
    assertEquals(kit.wallet.open(p, "EUR").map(r => (r.grantRef, r.amount)), Vector((awarded.rewardId, 4000L)))
