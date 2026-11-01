package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*

import java.time.{Duration as JDuration, Instant}
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.duration.*

/**
 * The clocks of research R10 over the whole service (SC-002): each timer, set by `RewardClocks` from the reward's
 * events, fires within a minute of its deadline (never before it) and does what FR-015 says through `WalletStep`;
 * a timer due on a terminal reward, or for a deadline the reward no longer holds, does nothing.
 */
class ClockSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur  = Currency.unsafe("EUR")
  private val Lead = JDuration.ofSeconds(3)

  private def fresh(): (String, String) =
    val tag = UUID.randomUUID().toString.take(8)
    (s"ck-r-$tag", s"ck-p-$tag")

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()
  private def opening(player: String) =
    Opening(player, "o-clock", "Clock", "MANUAL", PayloadType.BonusFunds, Decision(1000L, 10000L, 1000L, 10L, Eur), source = Source.System)

  /** A reward opened at `status` at `at`, its record at the fake when it is active. */
  private def open(id: String, player: String, status: RewardStatus, at: Instant, wagering: Option[Instant] = None): Unit =
    entity(id).call(Reward.open).invoke(Open(opening(player), status, at, Option.when(status == RewardStatus.Active)(WalletAward(Some(id), 10000L)),
      wagering)): Unit
    if status == RewardStatus.Active then
      RewardsRuntime.wallet(kit.services).award(player, Eur, id, AwardTerms("o-clock", 1000L, 10L, 1000L, Some(1L), None)): Unit

  /** `days` before the deadline, so the deadline is `Lead` from now. */
  private def back(days: Long): Instant = kit.now().plus(Lead).minus(days, ChronoUnit.DAYS)

  private def ended(id: String, status: RewardStatus): RewardState =
    kit.eventually(s"$id ${status.wire}", 90.seconds)(Some(reward(id)).filter(_.status == status))

  /** Ended at or after its deadline and within a minute of it (SC-002). */
  private def onTime(s: RewardState, deadline: Instant): Unit =
    val at = s.terminatedAt.getOrElse(fail("not ended"))
    assert(!at.isBefore(deadline), s"ended at $at, before its deadline $deadline")
    assert(JDuration.between(deadline, at).compareTo(JDuration.ofMinutes(1)) < 0, s"ended at $at, a minute or more after $deadline")

  test("the claim deadline forfeits the record and expires the reward claim_expired"):
    val (id, player) = fresh()
    open(id, player, RewardStatus.Active, back(Windows.WageringDays))
    val deadline = reward(id).expiresAt.get
    val s = ended(id, RewardStatus.Expired)
    assertEquals(s.terminationReason, Some("claim_expired"))
    onTime(s, deadline)
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector(s"$id/forfeit"))
    assertEquals(kit.wallet.open(player, "EUR"), Vector.empty)

  test("a wagering window apart from the claim deadline forfeits and expires the reward wagering_window_expired"):
    val (id, player) = fresh()
    val deadline = kit.now().plus(Lead)
    open(id, player, RewardStatus.Active, kit.now(), wagering = Some(deadline))
    val s = ended(id, RewardStatus.Expired)
    assertEquals(s.terminationReason, Some("wagering_window_expired"))
    onTime(s, deadline)
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector(s"$id/forfeit"))

  test("the cap deadline closes the pending cap at the wallet and the reward cap_expired"):
    val (id, player) = fresh()
    open(id, player, RewardStatus.Active, kit.now())
    entity(id).call(Reward.convert).invoke(Convert(back(Windows.WageringDays))): Unit
    kit.wallet.pendingCap(player, "EUR", id)
    val deadline = reward(id).capExpiresAt.get
    val s = ended(id, RewardStatus.Closed)
    assertEquals(s.terminationReason, Some("cap_expired"))
    onTime(s, deadline)
    assertEquals(kit.wallet.keys("awards/close-pending-cap"), Vector(s"$id/close-cap"))
    assertEquals(kit.wallet.open(player, "EUR"), Vector.empty)

  test("the pending selection's window expires it pending_expired, with no wallet command"):
    val (id, player) = fresh()
    open(id, player, RewardStatus.Pending, back(Windows.PendingDays))
    val deadline = reward(id).expiresAt.get
    val s = ended(id, RewardStatus.Expired)
    assertEquals(s.terminationReason, Some("pending_expired"))
    onTime(s, deadline)
    assertEquals(kit.wallet.received.filter(_.path.contains(s"/wallets/$player/")), Vector.empty)

  test("a deadline reached by the wallet's record not being open moves the reward all the same, moving no money"):
    val (id, player) = fresh()
    entity(id).call(Reward.open).invoke(Open(opening(player), RewardStatus.Active, back(Windows.WageringDays), Some(WalletAward(Some(id), 0L)))): Unit
    assertEquals(ended(id, RewardStatus.Expired).terminationReason, Some("claim_expired"))
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector(s"$id/forfeit"))

  test("a terminal reward cancels its timers, and a timer due on it anyway does nothing"):
    val (id, player) = fresh()
    open(id, player, RewardStatus.Active, kit.now())
    val deadline = reward(id).expiresAt.get
    kit.eventually("the claim timer")(Option.when(kit.timerExists(Clock.Claim.name(id)))(()))
    entity(id).call(Reward.forfeit).invoke(Forfeit(ForfeitReason.BonusExhausted, Some(1000L), kit.now())): Unit
    kit.eventually("the claim timer cancelled")(Option.when(!kit.timerExists(Clock.Claim.name(id)))(()))
    kit.scheduler.createSingleTimer(s"stray-$id", 0.seconds, ClockTimer.fire.deferred(Due(id, Clock.Claim, deadline)))
    kit.eventually("the stray timer fired")(Option.when(!kit.timerExists(s"stray-$id"))(()))
    assertEquals((reward(id).status, reward(id).terminationReason), (RewardStatus.Forfeited, Some("bonus_exhausted")))
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector.empty)

  test("a timer for a deadline the reward no longer holds does nothing"):
    val (id, player) = fresh()
    open(id, player, RewardStatus.Active, kit.now())
    val stale = reward(id).expiresAt.get.minus(1, ChronoUnit.DAYS)
    kit.scheduler.createSingleTimer(s"stale-$id", 0.seconds, ClockTimer.fire.deferred(Due(id, Clock.Claim, stale)))
    kit.eventually("the stale timer fired")(Option.when(!kit.timerExists(s"stale-$id"))(()))
    assertEquals(reward(id).status, RewardStatus.Active)
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector.empty)
