package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.player.{DepositSeen, RewardChanged}
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.{CloseReason, ForfeitReason, RewardEvent, RewardState, RewardStatus}

import java.time.Instant

/** data-model.md's `MoneyConsumer` and `IndexConsumer`: what each fact or event asks, and what is not read. */
class ConsumersSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")
  private def m(v: Long) = MinorUnits.unsafe(v)

  private def forfeited(reason: String) =
    RestrictedFundsForfeited("p-1", Eur, "t-1", "r-1", m(400), reason, m(0), SignedMinorUnits(0), m(0), at)

  test("deposit_confirmed is the player's deposit, the wallet's ordinal its number"):
    val f = DepositConfirmed("p-1", Eur, "t-1", m(5000), "pay-1", 2, SignedMinorUnits(5000), m(0), at)
    assertEquals(MoneyConsumer.change(f), Some(MoneyConsumer.Change.Deposit("p-1", DepositSeen(5000L, "EUR", 2, "pay-1", at))))

  test("the reward families go to the reward named by the award id"):
    assertEquals(MoneyConsumer.change(RestrictedFundsConverted("p-1", Eur, "t-1", "r-1", m(900), SignedMinorUnits(900), m(0), at)),
      Some(MoneyConsumer.Change.ToReward("r-1", reward.Convert(at))))
    assertEquals(MoneyConsumer.change(forfeited("loss")),
      Some(MoneyConsumer.Change.ToReward("r-1", reward.Forfeit(ForfeitReason.BonusExhausted, Some(400L), at))))
    assertEquals(MoneyConsumer.change(forfeited("adjustment")),
      Some(MoneyConsumer.Change.ToReward("r-1", reward.Forfeit(ForfeitReason.AdminAdjustEmptied, Some(400L), at))))
    assertEquals(MoneyConsumer.change(RestrictedFundsClosed("p-1", Eur, "t-1", "r-1", "cap_exhausted", SignedMinorUnits(0), m(0), at)),
      Some(MoneyConsumer.Change.ToReward("r-1", reward.Close(CloseReason.CapExhausted, at))))

  test("every close reason of W5 closes the reward with it"):
    for reason <- CloseReason.values.toVector.filterNot(_ == CloseReason.FreeSpinsFinalised) do
      assertEquals(MoneyConsumer.change(RestrictedFundsClosed("p-1", Eur, "t-1", "r-1", reason.wire, SignedMinorUnits(0), m(0), at)),
        Some(MoneyConsumer.Change.ToReward("r-1", reward.Close(reason, at))), reason.wire)

  test("an operator's adjustment is not read: only the forfeit it leaves at zero moves the reward"):
    val adjusted = AdminAdjusted("p-1", Eur, "t-1", SignedMinorUnits(-200), "restricted", "goodwill", "op-1", SignedMinorUnits(0), m(800), at)
    assertEquals(MoneyConsumer.change(adjusted), None)
    assertEquals(MoneyConsumer.change(forfeited("adjustment")).collect { case MoneyConsumer.Change.ToReward(_, f: reward.Forfeit) => f.reason },
      Some(ForfeitReason.AdminAdjustEmptied))

  test("a forfeit this service or payouts asked for, a close it does not know, and other families are not read"):
    for reason <- Vector("forfeit", "cancel", "hold") do assertEquals(MoneyConsumer.change(forfeited(reason)), None, reason)
    for reason <- Vector("mystery", CloseReason.FreeSpinsFinalised.wire) do
      assertEquals(MoneyConsumer.change(RestrictedFundsClosed("p-1", Eur, "t-1", "r-1", reason, SignedMinorUnits(0), m(0), at)), None, reason)
    assertEquals(MoneyConsumer.change(BetPlaced("p-1", Eur, "t-1", "rd-1", "g-1", m(100), "real", SignedMinorUnits(0), m(0), at)), None)

  test("a fact that cannot be read, and a family not read, are ignored"):
    val kit = ConsumerTestKit.of(MoneyConsumer)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(Some(forfeited("hold"))).effect, ConsumerEffect.Ignore)

  test("the index reports the reward as it stands: provider failed, an award refused; nothing for a reward not opened"):
    val s = RewardState(opened = true, rewardId = "r-1", playerId = "p-1", offerId = "o-1", status = RewardStatus.Pending)
    assertEquals(IndexConsumer.report(s, RewardEvent.AwardRefused("BONUS_ALREADY_ACTIVE", at), at),
      Some(RewardChanged("r-1", "o-1", RewardStatus.Pending, false, awardRefused = true, at)))
    val failed = s.copy(status = RewardStatus.Cancelled, terminationReason = Some("provider_failed"))
    assertEquals(IndexConsumer.report(failed, RewardEvent.Cancelled(reward.CancelReason.ProviderFailed, 0L, at), at),
      Some(RewardChanged("r-1", "o-1", RewardStatus.Cancelled, providerFailed = true, awardRefused = false, at)))
    assertEquals(IndexConsumer.report(RewardState(), RewardEvent.Deselected(at), at), None)
