package io.eitheror.casino.rewards.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{AdminAdjusted, MoneyTransactions, MoneyTransactionsMessage, RestrictedFundsConverted, RestrictedFundsForfeited}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*

import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * features/rewards/lifecycle.feature (US4; FR-014 to FR-017). The background's reward is a no-deposit offer the player
 * selected through `/rewards`, so the wallet's record is `FakeWallet`'s; the wallet's reports are its facts on the
 * kit's broker; a deadline passing is its timer made due. A reward the background cannot make (a second open one, one
 * with a wagering window apart from its claim deadline, free rounds) is opened on `Reward` itself with its record
 * awarded at the fake, as the scene.
 */
class LifecycleFeatures extends RewardsFeatures("lifecycle.feature"):
  private val Eur = Currency.unsafe("EUR")
  private val WageringDays = 7
  private val MaxConvertible = 5000L

  private var rewardId = ""
  private var subject  = ""
  private var second   = ""
  private var expected: Option[String] = None
  private var realBefore = 0L
  private var reported   = false

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    rewardId = ""; subject = ""; second = ""; expected = None; realBefore = 0L; reported = false

  private def unique(prefix: String) = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  private def noDeposit(fields: (String, Any)*): String =
    offer((Vector[(String, Any)]("name" -> unique("Free"), "type" -> "NO_DEPOSIT", "noDepositRequired" -> true,
      "maxBonusValue" -> 1000L, "wageringMultiplier" -> 10L, "currency" -> "EUR", "wageringWindowDays" -> WageringDays) ++ fields)*)

  /** The offer made visible to `who` and selected: the reward active at the wallet, its id. */
  private def activeReward(who: String, offerId: String): String =
    Scene.target(kit, who, offerId): Unit
    last = kit.json("POST", kit.rewardsPath(s"/$who/select"), json("offerId" -> offerId), kit.asPlayer(who))
    assertEquals(last.status, 200, last.text)
    val r = eventually("the reward active")(Some(rewardOf(offerId, who)).filter(_.status == RewardStatus.Active))
    r.rewardId

  /** A reward opened `ACTIVE` on the entity, its record awarded at the fake under its id at `priority`. */
  private def openedActive(who: String, priority: Long, wagering: Option[java.time.Instant] = None,
      freeRounds: Option[FreeRoundsTerms] = None): String =
    val id = unique("rw")
    val opening = Opening(who, unique("o"), "Scene", "MANUAL", PayloadType.BonusFunds, Decision(1000L, 10000L, 1000L, 10L, Eur),
      priority = Some(priority), source = Source.OperatorManual, freeRounds = freeRounds, by = Some("op-1"))
    kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.open)
      .invoke(Open(opening, RewardStatus.Active, kit.now(), Some(WalletAward(Some(id), 10000L)), wagering)): Unit
    if freeRounds.isEmpty then
      RewardsRuntime.wallet(kit.services).award(who, Eur, id, AwardTerms(opening.offerId, 1000L, 10L, 1000L, Some(priority), None)): Unit
    id

  private def state(id: String = rewardId) = reward(id)
  private def until(status: RewardStatus, id: String = rewardId): RewardState =
    eventually(s"$id ${status.wire}")(Some(state(id)).filter(_.status == status))
  private def timer(name: String): Unit = eventually(s"timer $name")(Option.when(kit.timerExists(name))(()))

  private def m(v: Long) = MinorUnits.unsafe(v)
  private def report(f: MoneyTransactionsMessage): Unit = kit.publish(MoneyTransactions, subject, f)
  private def converted(): Unit =
    report(RestrictedFundsConverted(subject, Eur, unique("t"), rewardId, m(900), SignedMinorUnits(900), m(0), kit.now()))
  private def forfeited(reason: String): Unit =
    report(RestrictedFundsForfeited(subject, Eur, unique("t"), rewardId, m(1000), reason, m(0), SignedMinorUnits(0), m(0), kit.now()))

  private def cancel(grantRef: Option[String]): Unit =
    val body = grantRef.fold(json())(g => json("grantRef" -> g))
    last = kit.json("POST", kit.rewardsPath(s"/$subject/cancel"), body, kit.asPlayer(subject))

  private def revoke(fields: (String, Any)*): Unit =
    last = kit.json("POST", kit.grantsPath(s"/$rewardId/revoke"), json(fields*), operator)

  private def onWallet(route: String) = kit.wallet.calls(route).filter(_.path.contains(s"/wallets/$subject/"))

  // ── background ──────────────────────────────────────────────────────────────────────────────

  Given("a player of brand {string} with an active reward") { (slug: String) =>
    assertEquals(kit.slug, slug)
    subject = player
    rewardId = activeReward(subject, noDeposit("maxConvertibleAmount" -> MaxConvertible))
  }

  Given("the reward is active") { () => assertEquals(state().status, RewardStatus.Active) }

  // ── conversion, forfeit, close ──────────────────────────────────────────────────────────────

  Given("the reward's restricted funds record has a maximum convertible amount") { () =>
    assertEquals(state().maxConvertible, MaxConvertible)
  }

  Given("the reward's restricted funds record has no maximum convertible amount") { () =>
    // The offer sets none, so the wallet's maximum is the amount (research R5, `EE:453-455`).
    subject = newPlayer()
    rewardId = activeReward(subject, noDeposit())
    assertEquals(state().maxConvertible, state().faceValue)
  }

  When("the wallet reports the record converted") { () =>
    converted()
    reported = true
  }

  // A scene (Given) and an outcome (Then) in the same words: as a scene the conversion is reported here.
  Given("the reward is converted pending cap") { () =>
    if !reported then converted()
    until(RewardStatus.ConvertedPendingCap): Unit
    kit.wallet.pendingCap(subject, "EUR", rewardId)
  }

  Then("a cap timer is set for the cap window's days") { () =>
    val s = state()
    assertEquals(s.capExpiresAt, s.convertedAt.map(_.plus(WageringDays.toLong, ChronoUnit.DAYS)))
    timer(Clock.Cap.name(rewardId))
  }

  When("the wallet reports the reward's record taken to zero by a losing round") { () => forfeited("loss") }

  When("the wallet reports the reward's record adjusted to zero by an operator") { () => forfeited("adjustment") }

  When("the wallet reports the reward's record adjusted above zero by an operator") { () =>
    report(AdminAdjusted(subject, Eur, unique("t"), SignedMinorUnits(-200), "restricted", "goodwill", "op-1", SignedMinorUnits(0), m(800),
      kit.now()))
    // A later fact of the player, read in order, shows the adjustment was read: the conversion of another record.
    val marker = openedActive(subject, priority = 1)
    report(RestrictedFundsConverted(subject, Eur, unique("t"), marker, m(1), SignedMinorUnits(1), m(0), kit.now()))
    until(RewardStatus.ConvertedPendingCap, marker): Unit
  }

  Then("the reward is forfeited") { () => until(RewardStatus.Forfeited): Unit }

  Then("the reward stays active") { () => assertEquals(state().status, RewardStatus.Active) }

  When("the cap deadline passes") { () =>
    timer(Clock.Cap.name(rewardId))
    kit.fireTimer(Clock.Cap.name(rewardId))
  }

  Then("the wallet closes the pending cap") { () =>
    eventually("the close")(Option.when(kit.wallet.keys("awards/close-pending-cap").contains(s"$rewardId/close-cap"))(()))
    assertEquals(kit.wallet.open(subject, "EUR").map(_.grantRef), Vector.empty)
  }

  Then("the reward is closed") { () => assertEquals(until(RewardStatus.Closed).terminationReason, Some("cap_expired")) }

  // ── deadlines ───────────────────────────────────────────────────────────────────────────────

  Given("the reward is past its claim deadline") { () =>
    expected = Some(ExpireReason.ClaimExpired.wire)
    timer(Clock.Claim.name(rewardId))
  }

  Given("the reward is past its wagering window") { () =>
    // A wagering window apart from the claim deadline is free-rounds winnings' (research R10): opened as the scene.
    rewardId = openedActive(subject, priority = 1, wagering = Some(kit.now().plus(1, ChronoUnit.DAYS)))
    expected = Some(ExpireReason.WageringWindowExpired.wire)
    timer(Clock.Wagering.name(rewardId))
  }

  When("the deadline passes") { () =>
    val clock = if expected.contains(ExpireReason.ClaimExpired.wire) then Clock.Claim else Clock.Wagering
    kit.fireTimer(clock.name(rewardId))
  }

  Then("the wallet forfeits the reward's record") { () =>
    eventually("the forfeit")(Option.when(kit.wallet.keys("awards/forfeit").contains(s"$rewardId/forfeit"))(()))
    assert(!kit.wallet.open(subject, "EUR").exists(_.grantRef == rewardId))
  }

  Then("the reward is expired") { () => assertEquals(until(RewardStatus.Expired).terminationReason, expected) }

  // ── switch ──────────────────────────────────────────────────────────────────────────────────

  Given("a second open reward of the player") { () =>
    second = openedActive(subject, priority = -1)
  }

  When("the player switches the second to primary") { () =>
    last = kit.json("POST", kit.rewardsPath(s"/$subject/switch"), json("grantRef" -> second), kit.asPlayer(subject))
  }

  Then("the wallet reorders the records") { () =>
    assertEquals((last.status, last.json.bool("ok")), (200, Some(true)), last.text)
    assertEquals(onWallet("awards/switch").flatMap(_.json.flatMap(_.text("key"))), Vector(s"$second/switch-1"))
    assertEquals(kit.wallet.open(subject, "EUR").maxBy(_.priority).grantRef, second)
  }

  Then("the reward records the switch") { () =>
    eventually("the switch recorded")(Some(state(second)).filter(_.switches == 1)): Unit
  }

  // ── the player's cancel ─────────────────────────────────────────────────────────────────────

  Given("the wallet's record has a baseline from its award") { () =>
    kit.wallet.realFor(subject, "EUR", 5000L)
    kit.wallet.winningsSince(subject, "EUR", rewardId, 1200L)
  }

  Given("the wallet's record has no baseline") { () =>
    kit.wallet.realFor(subject, "EUR", 5000L)
    kit.wallet.winningsSince(subject, "EUR", rewardId, 1200L)
    kit.wallet.noBaseline(subject, "EUR", rewardId)
  }

  Given("a reward id the player does not hold") { () => rewardId = openedActive(newPlayer(), priority = 1) }

  Given("the player's reward is no longer active") { () =>
    forfeited("loss")
    until(RewardStatus.Forfeited): Unit
  }

  When("the player cancels it") { () => cancel(Some(rewardId)) }

  Then("the wallet forfeits the record") { () =>
    assertEquals((last.status, last.str("status")), (200, "canceled"), last.text)
    assertEquals(onWallet("awards/cancel").flatMap(_.json.flatMap(_.text("grantRef"))), Vector(rewardId))
    assertEquals(kit.wallet.open(subject, "EUR"), Vector.empty)
    until(RewardStatus.Cancelled): Unit
  }

  Then("the wallet's clawback is the real winnings since the award less the deposits since, floored at zero") { () =>
    // The computation is the wallet's (spec 002 FR-017); the answer is what it took, and the real balance after it.
    assertEquals((last.json.long("clawbackAmount"), last.json.long("newRealBalance")), (Some(1200L), Some(3800L)), last.text)
  }

  Then("the wallet's clawback is {string}") { (amount: String) =>
    assertEquals((last.status, last.json.long("clawbackAmount")), (200, Some(amount.toLong)), last.text)
    until(RewardStatus.Cancelled): Unit
  }

  Then("it is refused as {string}") { (code: String) =>
    assertEquals(last.str("error"), code, last.text)
    assert(last.status >= 400 && last.status < 500, last.text)
  }

  // ── the operator's revoke ───────────────────────────────────────────────────────────────────

  When("an operator revokes it as {string}") { (mode: String) =>
    realBefore = kit.wallet.realOf(subject, "EUR")
    revoke("mode" -> mode, "reason" -> "abuse of terms")
  }

  When("an operator revokes them as {string}") { (mode: String) => revoke("mode" -> mode) }

  When("an operator revokes it with no mode") { () => revoke("reason" -> "no mode") }

  Then("the record is forfeited") { () =>
    assertEquals((last.status, last.str("status")), (200, "revoked"), last.text)
    assert(!kit.wallet.open(subject, "EUR").exists(_.grantRef == rewardId))
    assertEquals(until(RewardStatus.Revoked).revoke.map(_.by), Some("op-1"))
  }

  Then("the real balance is untouched") { () =>
    assertEquals(onWallet("awards/cancel"), Vector.empty)
    assertEquals(onWallet("awards/forfeit").flatMap(_.json.flatMap(_.text("grantRef"))), Vector(rewardId))
    assertEquals((last.json.long("clawbackApplied"), kit.wallet.realOf(subject, "EUR")), (Some(0L), realBefore))
  }

  Then("the clawback is applied too") { () =>
    assertEquals(onWallet("awards/cancel").flatMap(_.json.flatMap(_.bool("requireBaseline"))), Vector(true))
    assertEquals((last.json.long("clawbackApplied"), last.json.long("newRealBalance")), (Some(1200L), Some(3800L)), last.text)
  }

  Given("live free rounds of the player") { () =>
    rewardId = openedActive(subject, priority = 1, freeRounds = Some(FreeRoundsTerms("g-1", "st8", 10, 20L, Destination.RealBalance)))
  }

  Given("free rounds of the player already played out") { () =>
    rewardId = openedActive(subject, priority = 1, freeRounds = Some(FreeRoundsTerms("g-1", "st8", 1, 20L, Destination.RealBalance)))
    kit.components.forEventSourcedEntity(EntityId(rewardId)).call(Reward.roundSettled).invoke(RoundSettled("rd-1", 0L, kit.now())): Unit
    until(RewardStatus.Closed): Unit
  }

  Then("the game provider is asked to cancel them") { () =>
    assertEquals((last.status, last.json.bool("freeSpins")), (200, Some(true)), last.text)
    assertEquals(kit.provider.cancelCalls(rewardId).size, 1)
  }

  Then("the reward is revoked") { () => until(RewardStatus.Revoked): Unit }
