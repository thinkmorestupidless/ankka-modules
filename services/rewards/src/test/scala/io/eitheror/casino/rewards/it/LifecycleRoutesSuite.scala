package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.{Json, RewardsRuntime}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.*

import java.util.UUID

/**
 * The player's cancel, switch and history (FR-016; contracts/routes.md) beside lifecycle.feature's scenarios: the
 * primary cancelled with no grant named, the refusals each route answers, and the history of closed rewards.
 */
class LifecycleRoutesSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur = Currency.unsafe("EUR")
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()

  /** An active reward of `player` at `priority`, its record at the fake. */
  private def active(player: String, priority: Long, amount: Long = 1000L): String =
    val id = unique("lr-r")
    val opening = Opening(player, unique("o"), "Lifecycle", "MANUAL", PayloadType.BonusFunds, Decision(amount, amount * 10, amount, 10L, Eur),
      priority = Some(priority), source = Source.System)
    entity(id).call(Reward.open).invoke(Open(opening, RewardStatus.Active, kit.now(), Some(WalletAward(Some(id), amount * 10)))): Unit
    RewardsRuntime.wallet(kit.services).award(player, Eur, id, AwardTerms(opening.offerId, amount, 10L, amount, Some(priority), None)): Unit
    // A cancel naming no reward reads the player's currency from their rewards' rows.
    kit.eventually(s"$id's row")(RewardsView.byPlayer(kit.views.forView(RewardsView), player).find(_.rewardId == id))
    id

  private def post(player: String, route: String, body: Option[String]) =
    kit.request("POST", kit.rewardsPath(s"/$player/$route"), body, kit.asPlayer(player))

  test("a cancel naming no reward, with no body or an empty one, cancels the primary"):
    for body <- Vector(None, Some("{}")) do
      val p = unique("lr-p")
      val (low, high) = (active(p, 1), active(p, 5))
      val r = post(p, "cancel", body)
      assertEquals((r.status, r.str("status")), (200, "canceled"), r.text)
      assertEquals(kit.wallet.keys("awards/cancel").last, s"$high/cancel")
      assertEquals(kit.eventually("cancelled")(Some(reward(high)).filter(_.status == RewardStatus.Cancelled)).terminationReason,
        Some("player_cancel"))
      assertEquals(reward(low).status, RewardStatus.Active)

  test("an explicit empty grant is grant_not_found, never the primary; nothing open is no_active_bonus"):
    val p = unique("lr-p")
    val id = active(p, 1)
    val empty = post(p, "cancel", Some(Json.render(Json.obj("grantRef" -> ""))))
    assertEquals((empty.status, empty.str("error")), (404, "grant_not_found"))
    assertEquals(reward(id).status, RewardStatus.Active)
    val nobody = unique("lr-p")
    val none = post(nobody, "cancel", None)
    assertEquals((none.status, none.str("error")), (409, "no_active_bonus"))
    assertEquals(kit.wallet.keys("awards/cancel"), Vector.empty)

  test("a wallet that cannot be read is no_active_bonus, as the original; a cancel the wallet does not answer is cancel_failed"):
    val p = unique("lr-p")
    val id = active(p, 1)
    kit.wallet.routeAnswer("restricted-funds", Answer(503, """{"error":"down"}"""))
    assertEquals(post(p, "cancel", None).str("error"), "no_active_bonus")
    kit.wallet.clearRoute("restricted-funds")
    kit.wallet.routeAnswer("awards/cancel", Answer(503, """{"error":"down"}"""))
    val r = post(p, "cancel", Some(Json.render(Json.obj("grantRef" -> id))))
    assertEquals((r.status, r.str("error")), (503, "cancel_failed"))

  test("a switch without a grant is grantRef_required; another player's reward, or a switch the wallet refuses, switch_failed"):
    val p = unique("lr-p")
    val none = post(p, "switch", Some("{}"))
    assertEquals((none.status, none.str("error")), (400, "grantRef_required"))
    val theirs = active(unique("lr-p"), 1)
    val foreign = post(p, "switch", Some(Json.render(Json.obj("grantRef" -> theirs))))
    assertEquals((foreign.status, foreign.str("error")), (409, "switch_failed"))
    assertEquals(kit.wallet.keys("awards/switch"), Vector.empty)
    val mine = active(p, 1)
    kit.wallet.answerFor(s"$mine/switch-1", FakeWallet.refusal("GRANT_NOT_FOUND", 404))
    val refused = post(p, "switch", Some(Json.render(Json.obj("grantRef" -> mine))))
    assertEquals((refused.status, refused.str("error")), (409, "switch_failed"))
    assertEquals(reward(mine).switches, 0)

  test("each switch is its own step: a second switch to primary is switch-2"):
    val p = unique("lr-p")
    val (a, b) = (active(p, 5), active(p, 1))
    for (id, n) <- Vector(b -> 1, a -> 1, b -> 2) do
      val r = post(p, "switch", Some(Json.render(Json.obj("grantRef" -> id))))
      assertEquals(r.status, 200, r.text)
      kit.eventually(s"$id switch $n")(Some(reward(id)).filter(_.switches == n))
    assertEquals(kit.wallet.keys("awards/switch"), Vector(s"$b/switch-1", s"$a/switch-1", s"$b/switch-2"))

  test("the history lists the player's closed rewards, newest first, with face value, currency and clocks"):
    val p = unique("lr-p")
    val (first, second, open) = (active(p, 1, 1000L), active(p, 2, 2000L), active(p, 3))
    entity(first).call(Reward.forfeit).invoke(Forfeit(ForfeitReason.BonusExhausted, None, kit.now())): Unit
    entity(second).call(Reward.close).invoke(Close(CloseReason.CapExhausted, kit.now().plusSeconds(1))): Unit
    kit.eventually("the rows closed")(Some(RewardsView.history(kit.views.forView(RewardsView), p)).filter(_.size == 2))
    val r = kit.request("GET", kit.rewardsPath(s"/$p/history"), None, kit.asPlayer(p))
    assertEquals(r.status, 200, r.text)
    val items = (0 until r.json.size).map(r.json.get).toVector
    assertEquals(items.flatMap(_.text("grantRef")), Vector(second, first))
    assertEquals(items.map(i => (i.long("faceValue"), i.text("currency"), i.text("status"))),
      Vector((Some(2000L), Some("EUR"), Some("CLOSED")), (Some(1000L), Some("EUR"), Some("FORFEITED"))))
    assert(items.forall(i => i.text("grantedAt").isDefined && i.text("closedAt").isDefined), r.text)
    assert(!items.flatMap(_.text("grantRef")).contains(open))
    val other = kit.request("GET", kit.rewardsPath(s"/$p/history"), None, kit.asPlayer("someone-else"))
    assertEquals(other.status, 403)
