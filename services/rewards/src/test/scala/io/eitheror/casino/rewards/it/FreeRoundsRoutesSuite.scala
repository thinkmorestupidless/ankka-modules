package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.*

import java.util.UUID

/**
 * The free-rounds reads (task T057; contracts/routes.md): the player's own free rounds, `game-provider`'s read at a
 * launch by identity, and the operator's spin values.
 */
class FreeRoundsRoutesSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur = Currency.unsafe("EUR")
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"
  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))

  /** Live free rounds of the player on `game`, opened now: the reward id once the view has it. */
  private def live(player: String, game: String, gameName: Option[String] = Some("Book")): String =
    val id = unique("fx-r")
    val opening = Opening(player, unique("o"), "Spins", "FREE_SPINS", PayloadType.FreeSpins, Decision(0L, 0L, 0L, 0L, Eur),
      source = Source.OperatorManual, freeRounds = Some(FreeRoundsTerms(game, "st8", 10, 20L, Destination.RealBalance, gameName)))
    entity(id).call(Reward.open).invoke(Open(opening, RewardStatus.Active, kit.now())): Unit
    kit.eventually(s"$id in the view")(RewardsView.byPlayer(kit.views.forView(RewardsView), player).find(_.rewardId == id).map(_ => ()))
    id

  private def freeRounds(player: String, as: Seq[(String, String)]) = kit.request("GET", kit.rewardsPath(s"/$player/free-rounds"), None, as)

  private def eligible(player: String, game: String, as: Seq[(String, String)] = kit.asService("game-provider")) =
    kit.request("GET", kit.rewardsPath(s"/$player/free-rounds/eligible?provider=st8&gameCode=$game"), None, as)

  private def spinValues(query: String, as: Seq[(String, String)] = kit.asOperator()) =
    kit.request("GET", kit.offersPath(s"/free-rounds/spin-values$query"), None, as)

  test("the player's free rounds: live ones, oldest first, a cancelled one not listed; another player's refused"):
    val player = unique("fx-p")
    val first  = live(player, "g-a")
    val second = live(player, "g-b", None)
    val failed = live(player, "g-c")
    entity(failed).call(Reward.cancel).invoke(Cancel(CancelReason.ProviderFailed, 0L, kit.now())): Unit
    kit.eventually("cancelled in the view")(RewardsView.byPlayer(kit.views.forView(RewardsView), player)
      .find(r => r.rewardId == failed && r.status == RewardStatus.Cancelled.wire))
    val r = freeRounds(player, kit.asPlayer(player))
    assertEquals(r.status, 200, r.text)
    val items = (0 until r.json.size).map(r.json.get).toVector
    assertEquals(items.flatMap(_.text("id")), Vector(first, second))
    assertEquals((items.head.text("gameId"), items.head.text("gameName"), items.head.long("spinsRemaining")), (Some("g-a"), Some("Book"), Some(10L)))
    assert(items.head.text("expiresAt").isDefined)
    assertEquals(freeRounds(player, kit.asPlayer(unique("other"))).str("error"), "player_id_mismatch")

  test("game-provider's read at a launch: the oldest live free rounds for the game, by identity alone"):
    val player = unique("fx-p")
    val oldest = live(player, "g-x")
    live(player, "g-x"): Unit
    live(player, "g-y"): Unit
    val r = eligible(player, "g-x")
    assertEquals((r.status, r.str("grantId")), (200, oldest), r.text)
    val none = eligible(player, "g-none")
    assertEquals((none.status, none.str("error")), (404, "no_eligible_grant"))
    assertEquals(eligible(player, "g-x", kit.asPlayer(player)).status, 403)
    assert(Set(401, 403).contains(eligible(player, "g-x", kit.asService("wallet")).status))

  test("spin values: the provider's values in minor units, distinct and sorted, from st8"):
    kit.provider.spinValues("EUR", "0.50", "0.10", "0.20000000", "0.2")
    val r = spinValues("?gameId=g-1&currency=EUR")
    assertEquals(r.status, 200, r.text)
    assertEquals((r.str("gameId"), r.str("currency"), r.str("source")), ("g-1", "EUR", "st8"))
    assertEquals(r.json.get("values").toString, "[10,20,50]")

  test("spin values on a brand with no free rounds set up: none, not_configured; a provider not answering is 502"):
    kit.provider.notConfigured = true
    val none = spinValues("?gameId=g-1&currency=EUR")
    assertEquals((none.status, none.str("source"), none.json.get("values").size), (200, "not_configured", 0))
    kit.provider.notConfigured = false
    kit.provider.spinValuesAnswer = Some(Answer(503, """{"error":"down"}"""))
    val down = spinValues("?gameId=g-1&currency=EUR")
    assertEquals((down.status, down.str("error")), (502, "provider_unavailable"))

  test("spin values: the game and the currency are required; only the admin roles may ask"):
    assertEquals(spinValues("?currency=EUR").str("field"), "gameId")
    assertEquals(spinValues("?gameId=g-1").status, 400)
    assertEquals(spinValues("?gameId=g-1&currency=EUR", kit.asOperator("brand_support")).status, 403)
