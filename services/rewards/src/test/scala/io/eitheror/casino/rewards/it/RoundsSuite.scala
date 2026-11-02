package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoRounds, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.api.UnmatchedRounds
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.player.PlayerRewards
import io.eitheror.casino.rewards.reward.*

import java.util.UUID

/**
 * `RoundsConsumer` over the whole service (task T055): `round_settled` on `casino.rounds` naming free rounds counts on
 * the reward once per round; one naming a reward this service does not hold is recorded on the player's record.
 */
class RoundsSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur = Currency.unsafe("EUR")
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"
  private def reward(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.get).invoke()

  private def live(player: String, count: Int): String =
    val id = unique("rs-r")
    val opening = Opening(player, unique("o"), "Spins", "FREE_SPINS", PayloadType.FreeSpins, Decision(0L, 0L, 0L, 0L, Eur),
      source = Source.OperatorManual, freeRounds = Some(FreeRoundsTerms("g-1", "st8", count, 20L, Destination.RealBalance)))
    kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.open).invoke(Open(opening, RewardStatus.Active, kit.now())): Unit
    id

  private def settle(player: String, grant: Option[String], round: String, win: Long): Unit =
    kit.publish(CasinoRounds, s"$player/EUR", RoundSettled(player, Eur, round, "st8", s"p-$round", "g-1", "s-1", MinorUnits.unsafe(0),
      MinorUnits.unsafe(win), SignedMinorUnits(win), RoundSettled.Outcome.Win, Vector.empty, Vector.empty, Vector.empty, grant, kit.now()))

  test("a round naming free rounds counts once, however often it is delivered"):
    val player = unique("rs-p")
    val id     = live(player, 5)
    settle(player, Some(id), "rd-1", 150L)
    settle(player, Some(id), "rd-1", 150L)
    settle(player, Some(id), "rd-2", 0L)
    val tally = kit.eventually("two rounds")(reward(id).freeRounds.map(_.tally).filter(_.seen.size == 2))
    assertEquals((tally.used, tally.accumulated), (2, 150L))

  test("a round naming a reward this service does not hold is recorded on the player's record, once"):
    val player = unique("rs-p")
    val ghost  = unique("ghost")
    settle(player, Some(ghost), "rd-9", 300L)
    settle(player, Some(ghost), "rd-9", 300L)
    val state = kit.eventually("unmatched")(Some(kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.get).invoke())
      .filter(_.unmatchedRounds.nonEmpty))
    Thread.sleep(500)
    val again = kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.get).invoke()
    assertEquals(again.unmatchedRounds.map(r => (r.roundId, r.grantRef, r.winCents)), Vector(("rd-9", ghost, 300L)))
    val shown = UnmatchedRounds.json(state)
    assertEquals((shown.size, shown.get(0).text("grantRef"), shown.get(0).long("winCents")), (1, Some(ghost), Some(300L)))
    assertEquals(reward(ghost).opened, false)

  test("a round of the player's own money is not read"):
    val player = unique("rs-p")
    settle(player, None, "rd-own", 500L)
    Thread.sleep(500)
    assertEquals(kit.components.forEventSourcedEntity(EntityId(player)).call(PlayerRewards.get).invoke().unmatchedRounds, Vector.empty)
