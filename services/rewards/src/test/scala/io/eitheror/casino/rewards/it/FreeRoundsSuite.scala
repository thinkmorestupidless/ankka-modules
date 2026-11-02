package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{BonusCancelled, CasinoRewards}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.steps.{CancelRounds, FreeRounds, FreeRoundsFlow, FreeRoundsRequest}

import java.time.Duration
import java.util.UUID

/**
 * `FreeRounds` (research R8; SC-005) against `FakeGameProvider`: the player made known, the create sent once under the
 * reward id with the expiry as its duration, a processing bonus polled until it is confirmed or the polls run out, and
 * every failure cancelling the reward `provider_failed` with no wallet command.
 */
class FreeRoundsSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur = Currency.unsafe("EUR")
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()
  private def flow(id: String)   = kit.components.forWorkflow(EntityId(id))
  private def state(id: String)  = flow(id).call(FreeRounds.status).invoke()

  /** Polls every `every`, as the original's 30 s shortened. */
  private def pollEvery(every: Duration): Unit = RewardsRuntime.wire(kit.settings.copy(pollEvery = every), () => kit.now())

  /** Live free rounds of a new player on the reward, not yet asked of the provider: (reward, player). */
  private def opened(count: Int = 10): (String, String) =
    val (id, player) = (unique("fr-r"), unique("fr-p"))
    val terms   = FreeRoundsTerms("g-1", "st8", count, 20L, Destination.RealBalance)
    val opening = Opening(player, unique("o"), "Spins", "FREE_SPINS", PayloadType.FreeSpins, Decision(0L, 0L, 0L, 0L, Eur),
      source = Source.OperatorManual, freeRounds = Some(terms))
    entity(id).call(Reward.open).invoke(Open(opening, RewardStatus.Active, kit.now())): Unit
    (id, player)

  private def start(id: String, player: String, count: Int = 10): FreeRoundsFlow =
    flow(id).call(FreeRounds.start).invoke(FreeRoundsRequest(id, player, "EUR", "g-1", count, 20L, 30))

  private def until(id: String, what: String)(p: FreeRoundsFlow => Boolean): FreeRoundsFlow =
    kit.eventually(s"$id $what", scala.concurrent.duration.Duration(60, "s"))(Some(state(id)).filter(p))

  private def noWallet(player: String): Unit =
    assertEquals(kit.wallet.received.filter(_.path.contains(s"/wallets/$player/")), Vector.empty)

  test("confirmed: the player made known, one create under the reward id with the expiry as its duration, the status recorded"):
    val (id, player) = opened()
    start(id, player)
    until(id, "live")(_.phase == FreeRoundsFlow.Live)
    assertEquals(kit.provider.knownCalls.count(_.path.contains(s"/players/$player/")), 1)
    val created = kit.provider.createCalls.flatMap(_.json).filter(_.text("grantId").contains(id))
    assertEquals(created.map(b => (b.long("count"), b.text("spinValue"), b.text("currency"), b.long("durationSeconds"))),
      Vector((Some(10L), Some("0.20"), Some("EUR"), Some(30L * 86400L))))
    assertEquals(created.flatMap(_.strings("gameCodes")), Vector("g-1"))
    assertEquals((reward(id).status, reward(id).freeRounds.flatMap(_.providerStatus)), (RewardStatus.Active, Some("active")))
    assertEquals(kit.provider.polls(id), Vector.empty)

  test("a start repeated answers the first state and sends no second create"):
    val (id, player) = opened()
    start(id, player)
    until(id, "live")(_.phase == FreeRoundsFlow.Live)
    assertEquals(start(id, player).created, Some("active"))
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(id)), 1)

  test("a player who cannot be made known is created all the same"):
    val (id, player) = opened()
    kit.provider.knownAnswer = Some(Answer(503, """{"error":"down"}"""))
    start(id, player)
    until(id, "live")(_.phase == FreeRoundsFlow.Live)
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(id)), 1)

  test("processing, then active: polled until confirmed, the create never sent again"):
    pollEvery(Duration.ofMillis(200))
    val (id, player) = opened()
    kit.provider.createStatus(id, "processing")
    assertEquals(start(id, player).request.map(_.rewardId), Some(id))
    until(id, "polled twice")(s => s.phase == FreeRoundsFlow.Polling && s.polls >= 2)
    assertEquals(reward(id).freeRounds.flatMap(_.providerStatus), Some("processing"))
    kit.provider.statusFor(id, "active")
    until(id, "live")(_.phase == FreeRoundsFlow.Live)
    assertEquals(reward(id).freeRounds.flatMap(_.providerStatus), Some("active"))
    assertEquals(reward(id).status, RewardStatus.Active)
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(id)), 1)

  test("rejected at the create: the reward cancelled provider_failed, nothing at the wallet"):
    val (id, player) = opened()
    kit.provider.createStatus(id, "rejected")
    start(id, player)
    until(id, "failed")(_.phase == FreeRoundsFlow.Failed)
    val s = reward(id)
    assertEquals((s.status, s.terminationReason, s.freeRounds.flatMap(_.providerStatus)),
      (RewardStatus.Cancelled, Some("provider_failed"), Some("rejected")))
    val fact = kit.eventually("bonus_cancelled")(kit.facts(CasinoRewards).collectFirst { case f: BonusCancelled if f.bonusId == id => f })
    assertEquals((fact.reason, fact.amountCents.value), (Some("provider_failed"), 0L))
    noWallet(player)

  test("a create the provider does not answer is failed, as the original's"):
    val (id, player) = opened()
    kit.provider.createFor(id, Answer(503, """{"error":"down"}"""))
    start(id, player)
    until(id, "failed")(_.phase == FreeRoundsFlow.Failed)
    assertEquals(reward(id).terminationReason, Some("provider_failed"))

  test("SC-005: 50 polls unconfirmed, then CANCELLED provider_failed with no wallet command"):
    pollEvery(Duration.ofMillis(100))
    val (id, player) = opened()
    kit.provider.createStatus(id, "processing")
    start(id, player)
    until(id, "failed")(_.phase == FreeRoundsFlow.Failed)
    assertEquals(kit.provider.polls(id).size, 50)
    assertEquals(state(id).polls, 50)
    val s = reward(id)
    assertEquals((s.status, s.terminationReason, s.freeRounds.flatMap(_.providerStatus)),
      (RewardStatus.Cancelled, Some("provider_failed"), Some("failed")))
    noWallet(player)
    Thread.sleep(500)
    assertEquals(kit.provider.polls(id).size, 50)

  test("a poll answering a failure cancels at once"):
    pollEvery(Duration.ofMillis(200))
    val (id, player) = opened()
    kit.provider.createStatus(id, "processing")
    start(id, player)
    until(id, "polling")(_.phase == FreeRoundsFlow.Polling)
    kit.provider.statusFor(id, "failed")
    until(id, "failed")(_.phase == FreeRoundsFlow.Failed)
    assertEquals(reward(id).status, RewardStatus.Cancelled)

  test("mid-poll, where the poll stands is the workflow's journalled state: a start again neither restarts it nor creates again"):
    // ankka 0.13's kit keeps no `ankka-pause-…` row in `ankka_timers` for a workflow's pause, so a restart is not
    // driven here; what a restart resumes from, the phase and the polls made, is the state, read back as journalled.
    pollEvery(Duration.ofMillis(300))
    val (id, player) = opened()
    kit.provider.createStatus(id, "processing")
    start(id, player)
    val mid = until(id, "polled three times")(s => s.phase == FreeRoundsFlow.Polling && s.polls >= 3)
    val again = start(id, player)
    assertEquals(again.phase, FreeRoundsFlow.Polling)
    assert(again.polls >= mid.polls, s"${again.polls} < ${mid.polls}")
    kit.provider.statusFor(id, "active")
    val live = until(id, "live")(_.phase == FreeRoundsFlow.Live)
    assert(live.polls > mid.polls)
    assertEquals(kit.provider.polls(id).size, live.polls)
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(_.text("grantId").contains(id)), 1)

  test("a reward no longer active ends the poll silently"):
    pollEvery(Duration.ofMillis(200))
    val (id, player) = opened()
    kit.provider.createStatus(id, "processing")
    start(id, player)
    until(id, "polling")(_.phase == FreeRoundsFlow.Polling)
    entity(id).call(Reward.expire).invoke(Expire(ExpireReason.FreeSpinsExpiredUnused, kit.now())): Unit
    until(id, "ended")(_.phase == FreeRoundsFlow.Ended)
    assertEquals(reward(id).status, RewardStatus.Expired)
    assertEquals(kit.provider.cancelCalls(id), Vector.empty)

  test("a cancel redirects a polling workflow to the provider's cancel; one not answered may be asked again"):
    pollEvery(Duration.ofSeconds(5))
    val (id, player) = opened()
    kit.provider.createStatus(id, "processing")
    start(id, player)
    until(id, "polling")(_.phase == FreeRoundsFlow.Polling)
    kit.provider.cancelFor(id, Answer(503, """{"error":"down"}"""))
    flow(id).call(FreeRounds.cancel).invoke(CancelRounds(id, player)): Unit
    assertEquals(until(id, "cancel answered")(_.cancelled.isDefined).cancelled, Some(FreeRoundsFlow.Unavailable))
    kit.provider.cancelFor(id, FakeGameProvider.status("cancelled"))
    flow(id).call(FreeRounds.cancel).invoke(CancelRounds(id, player)): Unit
    assertEquals(until(id, "cancelled")(_.cancelled.contains("cancelled")).phase, FreeRoundsFlow.Cancelled)
    assertEquals(kit.provider.cancelCalls(id).size, 2)
    flow(id).call(FreeRounds.cancel).invoke(CancelRounds(id, player)): Unit
    assertEquals(kit.provider.cancelCalls(id).size, 2)

  test("a cancel of rounds already live reaches the provider"):
    val (id, player) = opened()
    start(id, player)
    until(id, "live")(_.phase == FreeRoundsFlow.Live)
    flow(id).call(FreeRounds.cancel).invoke(CancelRounds(id, player)): Unit
    assertEquals(until(id, "cancelled")(_.phase == FreeRoundsFlow.Cancelled).cancelled, Some("cancelled"))
    assertEquals(kit.provider.cancelCalls(id).size, 1)
