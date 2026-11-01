package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.steps.*

import java.util.UUID

/**
 * `WalletStep` wired to `Reward` (research R5) over the whole service: an award activates the reward, a timer's
 * forfeit expires it, and an award landing on a reward that ended while it was in flight cancels the record.
 */
class WiringSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val eur = Currency.unsafe("EUR")
  private def fresh(): (String, String) =
    val tag = UUID.randomUUID().toString.take(8)
    (s"wr-r-$tag", s"wr-p-$tag")

  private def opening(player: String) =
    Opening(player, "o-wiring", "Welcome", "DEPOSIT_MATCH", PayloadType.BonusFunds, Decision(0L, 0L, 0L, 30L, eur), source = Source.System)
  private val terms = AwardTerms("o-wiring", 2000L, 30L, 4000L, Some(5L), None)

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()
  private def open(id: String, player: String, initial: RewardStatus = RewardStatus.Pending): Unit =
    entity(id).call(Reward.open).invoke(Open(opening(player), initial, kit.now(), Option.when(initial == RewardStatus.Active)(
      WalletAward(None, 0L)))): Unit
  private def award(id: String, player: String): Unit =
    val r = StepRequest(id, player, "EUR", WalletCall.Award(terms))
    kit.components.forWorkflow(EntityId(r.id)).call(WalletStep.start).invoke(r): Unit
  private def until(id: String, status: RewardStatus): RewardState =
    kit.eventually(s"$id ${status.wire}")(Some(reward(id)).filter(_.status == status))

  test("an award activates the reward with the wallet's record and requirement and the amount awarded"):
    val (id, player) = fresh()
    open(id, player)
    award(id, player)
    val s = until(id, RewardStatus.Active)
    assertEquals((s.recordId, s.wageringRequirement, s.faceValue, s.maxConvertible), (Some(id), 60000L, 2000L, 4000L))
    assertEquals(kit.wallet.open(player, "EUR").map(_.grantRef), Vector(id))

  test("an award the wallet refuses is recorded on the reward, which stays pending"):
    val (id, player) = fresh()
    open(id, player)
    kit.wallet.answerFor(id, FakeWallet.refusal("BONUS_ALREADY_ACTIVE"))
    award(id, player)
    val s = kit.eventually("refusal recorded")(Some(reward(id)).filter(_.lastDecline.isDefined))
    assertEquals((s.status, s.lastDecline), (RewardStatus.Pending, Some("BONUS_ALREADY_ACTIVE")))

  test("a timer's forfeit closes the record and expires the reward with the timer's reason"):
    val (id, player) = fresh()
    open(id, player)
    award(id, player)
    until(id, RewardStatus.Active): Unit
    Wiring.Timed.timed(kit.components, id, Steps.Forfeit, Ending.Expire(ExpireReason.ClaimExpired))
    val s = until(id, RewardStatus.Expired)
    assertEquals(s.terminationReason, Some("claim_expired"))
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector(s"$id/forfeit"))
    assertEquals(kit.wallet.open(player, "EUR"), Vector.empty)

  test("a timer's forfeit of a record not open moves the reward all the same, moving no money"):
    val (id, player) = fresh()
    open(id, player, RewardStatus.Active)
    Wiring.Timed.timed(kit.components, id, Steps.Forfeit, Ending.Expire(ExpireReason.WageringWindowExpired))
    assertEquals(until(id, RewardStatus.Expired).terminationReason, Some("wagering_window_expired"))

  test("an award landing on a reward that ended while it was in flight cancels the wallet's record"):
    val (id, player) = fresh()
    open(id, player)
    entity(id).call(Reward.expire).invoke(Expire(ExpireReason.PendingExpired, kit.now())): Unit
    award(id, player)
    kit.eventually("the record cancelled")(Some(kit.wallet.keys("awards/cancel")).filter(_.nonEmpty))
    assertEquals(kit.wallet.keys("awards/cancel"), Vector(s"$id/cancel"))
    assertEquals(kit.wallet.open(player, "EUR"), Vector.empty)
    kit.eventually("the cancel step done")(Some(kit.components.forWorkflow(EntityId(s"$id/cancel")).call(WalletStep.status).invoke())
      .filter(_.phase == StepState.Done))
    assertEquals((reward(id).status, reward(id).terminationReason), (RewardStatus.Expired, Some("pending_expired")))
