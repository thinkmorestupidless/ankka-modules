package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.{Json, RewardsRuntime}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.steps.*

import java.util.UUID
import scala.concurrent.duration.*

/**
 * SC-003 over the whole service: every wallet command, fact and timer delivered twice, and a command repeated after
 * 1,000 other keys at the wallet (past which the wallet would carry it out afresh), awards, forfeits and claws back
 * once per reward. A step's start is the claim (research R5): a repeat answers the first state and calls nothing.
 */
class RepeatSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur = Currency.unsafe("EUR")
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()
  private def step(id: String)   = kit.components.forWorkflow(EntityId(id))
  private def until(id: String, status: RewardStatus): RewardState =
    kit.eventually(s"$id ${status.wire}")(Some(reward(id)).filter(_.status == status))
  private def done(id: String): StepState =
    kit.eventually(s"step $id done")(Some(step(id).call(WalletStep.status).invoke()).filter(_.phase == StepState.Done))

  private val terms = AwardTerms("o-repeat", 1000L, 10L, 1000L, Some(1L), None)
  private def opening(player: String) =
    Opening(player, "o-repeat", "Repeat", "MANUAL", PayloadType.BonusFunds, Decision(1000L, 10000L, 1000L, 10L, Eur), source = Source.System)

  /** A pending reward of a new player, awarded through its `award` step: (reward, player, the award's request). */
  private def awarded(): (String, String, StepRequest) =
    val (id, player) = (unique("rp-r"), unique("rp-p"))
    entity(id).call(Reward.open).invoke(Open(opening(player), RewardStatus.Pending, kit.now())): Unit
    val r = StepRequest(id, player, "EUR", WalletCall.Award(terms))
    step(r.id).call(WalletStep.start).invoke(r): Unit
    until(id, RewardStatus.Active)
    done(r.id)
    (id, player, r)

  /** 1,000 other keys at the wallet, so the wallet no longer remembers any key sent before them. */
  private def flood(): Unit =
    val wallet = RewardsRuntime.wallet(kit.services)
    (1 to FakeWallet.RecentKeys).foreach(n => wallet.forfeit("rp-flood", Eur, s"flood-$n", "none"): Unit)

  private def keysFor(route: String, id: String) = kit.wallet.keys(route).filter(_.startsWith(id))
  private def facts[F](id: String)(pick: PartialFunction[CasinoRewardsFact, F]): Vector[F] =
    kit.facts(CasinoRewards).filter {
      case f: BonusGranted => f.bonusId == id
      case f: BonusCompleted => f.bonusId == id
      case f: BonusForfeited => f.bonusId == id
      case f: BonusCancelled => f.bonusId == id
      case f: GrantExpired => f.bonusId == id
      case _ => false
    }.collect(pick)

  test("the wallet carries out afresh a key sent before 1,000 others, which is what a step's state guards against"):
    val wallet = RewardsRuntime.wallet(kit.services)
    val player = unique("rp-p")
    wallet.award(player, Eur, "rp-once", terms): Unit
    wallet.award(player, Eur, "rp-once", terms): Unit
    assertEquals(kit.wallet.open(player, "EUR").size, 1)
    flood()
    wallet.award(player, Eur, "rp-once", terms): Unit
    assertEquals(kit.wallet.open(player, "EUR").size, 2)

  test("an award started twice, delivered twice, and started again after 1,000 other keys awards once"):
    val (id, player, r) = awarded()
    step(r.id).call(WalletStep.start).invoke(r): Unit
    val answer = done(r.id).answer.get
    Wiring.Deliver(kit.components).deliver(id, r, answer)
    flood()
    assertEquals(step(r.id).call(WalletStep.start).invoke(r).phase, StepState.Done)
    Wiring.Deliver(kit.components).deliver(id, r, answer)
    assertEquals(keysFor("awards", id), Vector(id))
    assertEquals(kit.wallet.open(player, "EUR").map(_.grantRef), Vector(id))
    kit.eventually("bonus_granted")(facts(id) { case f: BonusGranted => f }.headOption)
    assertEquals(facts(id) { case f: BonusGranted => f }.size, 1)

  test("the wallet's facts delivered twice move the reward once: one completion, one forfeit"):
    val (id, player, _) = awarded()
    val conversion = RestrictedFundsConverted(player, Eur, unique("t"), id, MinorUnits.unsafe(900), SignedMinorUnits(900), MinorUnits.unsafe(0),
      kit.now())
    kit.publish(MoneyTransactions, player, conversion)
    kit.publish(MoneyTransactions, player, conversion)
    val (other, otherPlayer, _) = awarded()
    val loss = RestrictedFundsForfeited(otherPlayer, Eur, unique("t"), other, MinorUnits.unsafe(1000), "loss", MinorUnits.unsafe(0),
      SignedMinorUnits(0), MinorUnits.unsafe(0), kit.now())
    kit.publish(MoneyTransactions, otherPlayer, loss)
    kit.publish(MoneyTransactions, otherPlayer, loss)
    until(id, RewardStatus.ConvertedPendingCap)
    until(other, RewardStatus.Forfeited)
    // A later fact of each player, read in order, shows the repeats were read.
    kit.publish(MoneyTransactions, player, conversion.copy(transactionId = unique("t")))
    kit.publish(MoneyTransactions, otherPlayer, loss.copy(transactionId = unique("t")))
    kit.eventually("the facts published")(Option.when(
      facts(id) { case f: BonusCompleted => f }.nonEmpty && facts(other) { case f: BonusForfeited => f }.nonEmpty)(()))
    Thread.sleep(1000)
    assertEquals(facts(id) { case f: BonusCompleted => f }.size, 1)
    assertEquals(facts(other) { case f: BonusForfeited => f }.size, 1)

  test("a timer fired twice, and its step asked again after 1,000 other keys, forfeits once"):
    val (id, player, _) = awarded()
    val deadline = reward(id).expiresAt.get
    kit.eventually("the claim timer")(Option.when(kit.timerExists(Clock.Claim.name(id)))(()))
    kit.fireTimer(Clock.Claim.name(id))
    kit.scheduler.createSingleTimer(s"again-$id", 0.seconds, ClockTimer.fire.deferred(Due(id, Clock.Claim, deadline)))
    until(id, RewardStatus.Expired)
    done(s"$id/forfeit")
    flood()
    Wiring.Timed.timed(kit.components, id, Steps.Forfeit, Ending.Expire(ExpireReason.ClaimExpired))
    assertEquals(keysFor("awards/forfeit", id), Vector(s"$id/forfeit"))
    assertEquals(kit.wallet.open(player, "EUR"), Vector.empty)
    kit.eventually("grant_expired")(facts(id) { case f: GrantExpired => f }.headOption)
    assertEquals(facts(id) { case f: GrantExpired => f }.size, 1)

  test("a player's cancel asked twice, and its step after 1,000 other keys, claws back once"):
    val (id, player, _) = awarded()
    kit.wallet.realFor(player, "EUR", 5000L)
    kit.wallet.winningsSince(player, "EUR", id, 1200L)
    val cancel = () => kit.json("POST", kit.rewardsPath(s"/$player/cancel"), Json.obj("grantRef" -> id), kit.asPlayer(player))
    val first = cancel()
    assertEquals((first.status, first.json.long("clawbackAmount")), (200, Some(1200L)), first.text)
    until(id, RewardStatus.Cancelled)
    assertEquals(cancel().str("error"), "no_active_bonus")
    flood()
    val r = StepRequest(id, player, "EUR", WalletCall.Cancel())
    assertEquals(step(r.id).call(WalletStep.start).invoke(r).phase, StepState.Done)
    assertEquals(keysFor("awards/cancel", id), Vector(s"$id/cancel"))
    assertEquals(kit.wallet.realOf(player, "EUR"), 3800L)
    kit.eventually("bonus_cancelled")(facts(id) { case f: BonusCancelled => f }.headOption)
    assertEquals(facts(id) { case f: BonusCancelled => f }.map(_.amountCents.value), Vector(1200L))

  test("a revoke asked twice, and its step after 1,000 other keys, claws back once"):
    val (id, player, _) = awarded()
    kit.wallet.realFor(player, "EUR", 5000L)
    kit.wallet.winningsSince(player, "EUR", id, 1200L)
    val revoke = () => kit.json("POST", kit.grantsPath(s"/$id/revoke"), Json.obj("mode" -> "reclaim"), kit.asOperator())
    val first = revoke()
    assertEquals((first.status, first.json.long("clawbackApplied")), (200, Some(1200L)), first.text)
    assertEquals(revoke().str("error"), "NO_ACTIVE_BONUS")
    flood()
    val r = StepRequest(id, player, "EUR", WalletCall.Revoke(RevokeMode.Reclaim))
    assertEquals(step(r.id).call(WalletStep.start).invoke(r).phase, StepState.Done)
    assertEquals(keysFor("awards/cancel", id), Vector(s"$id/revoke-reclaim"))
    assertEquals(kit.wallet.realOf(player, "EUR"), 3800L)
    assertEquals(reward(id).status, RewardStatus.Revoked)
