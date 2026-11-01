package io.eitheror.casino.rewards.player

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.offers.{Offer, OfferType}
import io.eitheror.casino.rewards.player.PlayerEvent.*
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Instant
import java.time.temporal.ChronoUnit.{DAYS, HOURS}

/**
 * data-model.md's `PlayerRewards`: each command evaluates over the record and reserves or declines in one event, a
 * repeat writes nothing, and two claims at once see each other.
 */
class PlayerRewardsSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")
  private type Kit = EventSourcedTestKit[PlayerRewards, PlayerRewardsState, PlayerEvent]

  private def record: Kit = EventSourcedTestKit.of(PlayerRewards, "p-1")

  private val matchOffer = Offer(id = "o-match", name = "Welcome", offerType = OfferType.DepositMatch, bonusPercentage = 10000L,
    maxBonusValue = Some(50000L), minDepositAmount = Some(2000L), wageringMultiplier = 30L, claimWindowDays = Some(3), currency = Some(Eur),
    createdAt = at, updatedAt = at)
  private val freeBonus = Offer(id = "o-free", name = "Free", offerType = OfferType.NoDeposit, noDepositRequired = true,
    maxBonusValue = Some(1000L), wageringMultiplier = 10L, currency = Some(Eur), maxConcurrentGrants = Some(1), createdAt = at,
    updatedAt = at)

  private def target(kit: Kit, o: Offer, source: Source = Source.CustomerioWebhook): String =
    kit.call(PlayerRewards.target)(Target(o, source, at = at)).replyValue.outcome match
      case Outcome.Visible(id, _) => id
      case other                  => fail(s"not visible: $other")

  private def select(kit: Kit, o: Offer, open: Int = 0) = kit.call(PlayerRewards.select)(Select(o, open, Eur, at))

  private def pendingOn(kit: Kit, o: Offer = matchOffer): String =
    val id = target(kit, o)
    select(kit, o): Unit
    id

  private def deposit(amount: Long, ref: String = "pay-1", ordinal: Int = 1, when: Instant = at.plus(1, HOURS)) =
    DepositSeen(amount, "EUR", ordinal, ref, when)

  private def confirm(kit: Kit, d: DepositSeen, offer: Option[Offer] = Some(matchOffer)) =
    val pending = kit.currentState.pending.map((id, _) => PendingOffer(id, offer))
    kit.call(PlayerRewards.depositConfirmed)(DepositConfirmed(d, pending, 0, d.at))

  test("a target reserves a reward AVAILABLE with how it was given; a repeat answers the same reward and writes nothing"):
    val kit = record
    val r   = kit.call(PlayerRewards.target)(Target(matchOffer, Source.AffiliateCode, Some("spring"), Some("aff-1"), at))
    val Outcome.Visible(id, true) = r.replyValue.outcome: @unchecked
    assertEquals(r.events.toVector, Vector(OfferMadeVisible(id, "o-match", Source.AffiliateCode, Some("spring"), Some("aff-1"), at)))
    assertEquals(kit.currentState.rewards(id).status, RewardStatus.Available)
    val again = kit.call(PlayerRewards.target)(Target(matchOffer, Source.AffiliateCode, at = at))
    assertEquals((again.replyValue.outcome, again.events.toVector), (Outcome.Visible(id, fresh = false), Vector.empty))

  test("an offer the player holds no reward of is offer_not_visible"):
    assertEquals(select(record, matchOffer).replyValue.outcome, Outcome.Refused("offer_not_visible"))

  test("a deposit offer selected is pending, unevaluated; selected again it answers the same and writes nothing"):
    val kit = record
    val id  = target(kit, matchOffer)
    val r   = select(kit, matchOffer)
    assertEquals((r.replyValue.outcome, r.events.toVector), (Outcome.Selected(id), Vector(RewardSelected(id, at))))
    assertEquals(kit.currentState.rewards(id).selectedAt, Some(at))
    assertEquals(select(kit, matchOffer).events.toVector, Vector.empty)

  test("a no-deposit offer selected is claimed at once with its fixed amount, the source kept from targeting"):
    val kit = record
    val id  = target(kit, freeBonus, Source.PlayerCode)
    val r   = select(kit, freeBonus)
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertEquals((claim.rewardId, claim.decision.amount, claim.decision.wageringRequirement, claim.source, claim.paymentRef),
      (id, 1000L, 10000L, Source.PlayerCode, None))
    assertEquals(r.events.toVector, Vector(Claimed(claim)))
    assert(kit.currentState.rewards(id).claiming)
    assertEquals(select(kit, freeBonus).replyValue.outcome, Outcome.Refused("ALREADY_CLAIMED"))

  test("two selections at once: the second sees the first's claim as open and is ACTIVE_BONUS_CONFLICT"):
    val kit    = record
    val second = freeBonus.copy(id = "o-free-2")
    target(kit, freeBonus): Unit
    target(kit, second): Unit
    select(kit, freeBonus): Unit
    val r = select(kit, second)
    assertEquals((r.replyValue.outcome.getClass.getSimpleName, r.events.toVector), ("Declined", Vector.empty))
    assertEquals(r.replyValue.outcome.asInstanceOf[Outcome.Declined].reason, "ACTIVE_BONUS_CONFLICT")

  test("the wallet's open records count too (EE:164-165)"):
    val kit = record
    target(kit, freeBonus): Unit
    assertEquals(select(kit, freeBonus, open = 1).replyValue.outcome.asInstanceOf[Outcome.Declined].reason, "ACTIVE_BONUS_CONFLICT")

  test("a deselect returns every pending selection to available"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = kit.call(PlayerRewards.deselect)(Deselect(at))
    assertEquals((r.replyValue.outcome, r.events.toVector), (Outcome.Deselected(Vector(id)), Vector(RewardsDeselected(Vector(id), at))))
    assertEquals(kit.currentState.rewards(id).status, RewardStatus.Available)
    assertEquals(kit.call(PlayerRewards.deselect)(Deselect(at)).events.toVector, Vector.empty)

  test("a confirming deposit claims the pending selection with the matched amount; reported again it writes nothing and answers the claim"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = confirm(kit, deposit(5000L))
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertEquals((claim.rewardId, claim.decision.amount, claim.decision.wageringRequirement, claim.paymentRef),
      (id, 5000L, 150000L, Some("pay-1")))
    assertEquals(r.events.toVector, Vector(DepositRecorded(deposit(5000L)), Claimed(claim)))
    val again = confirm(kit, deposit(5000L))
    assertEquals((again.events.toVector, again.replyValue.outcome), (Vector.empty, Outcome.Claimed(claim)))

  test("a deposit below the minimum is recorded and the selection stays pending with the reason"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = confirm(kit, deposit(1000L))
    assertEquals(r.replyValue.outcome, Outcome.Declined(Some(id), "BELOW_MIN_DEPOSIT"))
    assertEquals(r.events.toVector.last, DeclineRecorded(id, "BELOW_MIN_DEPOSIT", at.plus(1, HOURS)))
    assertEquals((kit.currentState.rewards(id).status, kit.currentState.pending.map(_._1)), (RewardStatus.Pending, Some(id)))
    val next = confirm(kit, deposit(3000L, "pay-2", 2))
    assertEquals(next.replyValue.outcome.asInstanceOf[Outcome.Claimed].claim.decision.amount, 3000L)

  test("a deposit past the selection's window, or for an offer no longer live, expires the selection"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = confirm(kit, deposit(5000L, when = at.plus(3, DAYS).plus(1, HOURS)))
    assertEquals((r.replyValue.outcome, r.events.toVector.last), (Outcome.Expired(id), PendingExpired(id, at.plus(3, DAYS).plus(1, HOURS))))
    val other = record
    val id2   = pendingOn(other)
    assertEquals(confirm(other, deposit(5000L), Some(matchOffer.copy(active = false))).replyValue.outcome, Outcome.Expired(id2))
    val gone = record
    val id3  = pendingOn(gone)
    assertEquals(confirm(gone, deposit(5000L), None).replyValue.outcome, Outcome.Expired(id3))

  test("a deposit with nothing pending is recorded only, and sets the player's currency"):
    val kit = record
    val r   = confirm(kit, deposit(5000L))
    assertEquals((r.replyValue.outcome, r.events.toVector), (Outcome.Idle, Vector(DepositRecorded(deposit(5000L)))))
    assertEquals((kit.currentState.currency, kit.currentState.lastDeposit.map(_.amount)), (Some("EUR"), Some(5000L)))

  test("the reward's state reported: a claim ends when the reward moves or the wallet refuses; a report changing nothing writes nothing"):
    val kit = record
    val id  = target(kit, freeBonus)
    select(kit, freeBonus): Unit
    val refused = kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-free", RewardStatus.Available, false, awardRefused = true, at))
    assertEquals(refused.events.size, 1)
    assert(!kit.currentState.rewards(id).claiming)
    select(kit, freeBonus): Unit
    kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-free", RewardStatus.Active, false, false, at)): Unit
    assertEquals((kit.currentState.rewards(id).status, kit.currentState.rewards(id).claiming), (RewardStatus.Active, false))
    assertEquals(kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-free", RewardStatus.Active, false, false, at)).events.toVector,
      Vector.empty)

  test("a reward the record never made is added when reported"):
    val kit = record
    kit.call(PlayerRewards.rewardChanged)(RewardChanged("r-x", "o-x", RewardStatus.Active, false, false, at)): Unit
    assertEquals(kit.currentState.rewards("r-x").offerId, "o-x")
    assertEquals(kit.currentState.snapshot.rewards.map(_.offerId), Vector("o-x"))

  test("a claim on another path reserves a new reward with it, or claims the one the player may select"):
    val kit = record
    val r   = kit.call(PlayerRewards.claim)(ClaimOffer(freeBonus, operator = false, None, Source.CustomerioWebhook, None, None, 0, Eur, at))
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertEquals(r.events.toVector.map(_.getClass.getSimpleName), Vector("OfferMadeVisible", "Claimed"))
    assertEquals(kit.currentState.rewards(claim.rewardId).source, Source.CustomerioWebhook)
    val id = target(kit, matchOffer)
    val c2 = kit.call(PlayerRewards.claim)(ClaimOffer(matchOffer, operator = true, None, Source.OperatorManual, None, None, 5, Eur, at))
    assertEquals(c2.replyValue.outcome.asInstanceOf[Outcome.Claimed].claim.rewardId, id)
    assertEquals(c2.events.toVector.map(_.getClass.getSimpleName), Vector("Claimed"))

  test("the provisional opening is the offer's fixed amount in the currency given"):
    val o = Openings.provisional(matchOffer, "p-1", Eur, Source.PlayerCode, None, None)
    assertEquals((o.decision.amount, o.decision.currency, o.offerId, o.playerId), (50000L, Eur, "o-match", "p-1"))
