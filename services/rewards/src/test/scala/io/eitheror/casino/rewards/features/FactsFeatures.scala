package io.eitheror.casino.rewards.features

import io.eitheror.casino.contract.facts.BonusGranted
import io.eitheror.casino.rewards.it.{ClaimPaths, RewardFact, RewardFacts}
import io.eitheror.casino.rewards.reward.*

import java.util.UUID

/**
 * features/rewards/facts.feature (US7; SC-004; research R11). The activation is the player's own: a code entered
 * through `/rewards` selects a deposit match, and the wallet's `deposit_confirmed` on the kit's broker activates it.
 * The transitions are what `RewardsPublisher` reads, so each is applied to `Reward` itself, the way the routes, the
 * wallet's facts and the timers apply it: "a reward of the player" is a reward of funds held active, given by an
 * affiliate's code and tag; the free-rounds transitions use a free-rounds reward of the same player.
 */
class FactsFeatures extends RewardsFeatures("facts.feature"):
  private def paths = ClaimPaths(kit)
  private def facts = RewardFacts(kit)

  private var rewardId = ""
  private var offerId  = ""
  private var code     = ""
  private var opening: Option[Opening] = None
  private var family   = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    rewardId = ""; offerId = ""; code = ""; opening = None; family = ""

  private def now = kit.now()

  /** The reward's facts, once the one awaited is published, and a second for any late repeat to arrive. */
  private def settled(family: String): Vector[RewardFact] =
    facts.awaitFamily(rewardId, family): Unit
    Thread.sleep(1000)
    facts.of(rewardId)

  private def refusedNot(a: RewardAnswer): Unit = assert(a.refusal.isEmpty, s"$rewardId: ${a.refusal}")

  private def apply(transition: String): Unit =
    val r = facts.reward(rewardId)
    val answer = transition match
      case "cancelled" => r.call(Reward.cancel).invoke(Cancel(CancelReason.PlayerCancel, 0L, now))
      case "forfeited" => r.call(Reward.forfeit).invoke(Forfeit(ForfeitReason.BonusExhausted, Some(1000L), now))
      case "revoked"   => r.call(Reward.revoke).invoke(Revoke(RevokeMode.Reclaim, "op-1", None, 0L, now))
      case "converted" => r.call(Reward.convert).invoke(Convert(now))
      case "completed" => r.call(Reward.close).invoke(Close(CloseReason.RealBalanceZero, now))
      case "expired"   => r.call(Reward.expire).invoke(Expire(ExpireReason.WageringWindowExpired, now))
      case other       => throw AssertionError(s"no transition $other")
    refusedNot(answer)

  // ── background ──────────────────────────────────────────────────────────────────────────────

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // ── the activation ──────────────────────────────────────────────────────────────────────────

  Given("a pending reward of the player") { () =>
    code = s"facts${UUID.randomUUID().toString.filter(_.isDigit).take(8)}"
    paths.freeCode(code)
    offerId = paths.depositMatch(paths.codes(code))
    last = paths.enterCode(player, code)
    assertEquals(last.status, 200, last.text)
    val r = paths.rewardOf(player, offerId)
    assertEquals((r.status, r.source, r.code), (RewardStatus.Pending, Source.PlayerCode, Some(code)))
    rewardId = r.rewardId
  }

  When("it activates") { () =>
    paths.deposit(player, 3000L): Unit
    paths.until(rewardId, "active")(_.status == RewardStatus.Active): Unit
  }

  Then("a {string} fact carries the reward, the offer, the amount, the currency, the source and the code when one was used") {
    (kind: String) =>
      val all = settled(kind)
      assertEquals(all.map(_.family), Vector(kind))
      val g = all.head.fact match
        case g: BonusGranted => g
        case other           => fail(s"not bonus_granted: $other")
      val r = facts.reward(rewardId).call(Reward.get).invoke()
      assertEquals((g.playerId, g.bonusId, g.offerId, g.bonusName, g.offerType), (player, rewardId, offerId, r.offerName, "DEPOSIT_MATCH"))
      assertEquals((g.amountCents.value, g.currency.code), (3000L, "EUR"))
      assertEquals((g.source, g.code, g.bonusCode, g.tag), (Some(Source.PlayerCode.wire), Some(code), Some(code), None))
      assertEquals(all.head.ceId.map(_.takeWhile(_ != '/')), Some(rewardId))
  }

  // ── each transition ─────────────────────────────────────────────────────────────────────────

  Given("a reward of the player") { () =>
    val o = facts.funds(player)
    opening = Some(o)
    rewardId = facts.open(o, RewardStatus.Active)
  }

  for transition <- Vector("cancelled", "forfeited", "revoked", "converted", "completed", "expired") do
    When(s"it is $transition") { () => apply(transition) }

  When("it is given as free rounds") { () =>
    val o = facts.freeRounds(player)
    opening = Some(o)
    rewardId = facts.open(o, RewardStatus.Available)
    refusedNot(facts.reward(rewardId).call(Reward.activate).invoke(Activate(None, now)))
  }

  When("it is finalised as free rounds") { () =>
    val o = facts.freeRounds(player, count = 1)
    opening = Some(o)
    rewardId = facts.open(o, RewardStatus.Active)
    // The one round completes the count: the reward is finalised with what it won.
    refusedNot(facts.reward(rewardId).call(Reward.roundSettled).invoke(RoundSettled("rd-1", 250L, now)))
  }

  Then("a {string} fact is published") { (kind: String) =>
    val all = settled(kind)
    val one = all.filter(_.family == kind)
    assertEquals(one.size, 1, all.toString)
    val o = opening.get
    assertEquals(RewardFacts.origin(one.head.fact), (Some(o.source.wire), o.code, o.tag))
    assertEquals(one.head.fact.playerId, player)
  }

  // ── a repeat ────────────────────────────────────────────────────────────────────────────────

  When("the same transition is applied twice") { () =>
    family = "bonus_cancelled"
    apply("cancelled")
    apply("cancelled")
  }

  Then("one fact is published") { () =>
    val all = settled(family)
    assertEquals(all.map(_.family), Vector("bonus_granted", family))
    assertEquals(all.flatMap(_.ceId).distinct.size, 2, all.toString)
  }
