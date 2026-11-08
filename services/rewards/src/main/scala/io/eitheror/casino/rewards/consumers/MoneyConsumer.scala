package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.player.{DepositSeen, Flows}
import io.eitheror.casino.rewards.reward.{CloseReason, ForfeitReason, Reward}
import io.eitheror.casino.rewards.reward

/**
 * `money.transactions` into this service (data-model.md *Consumers*): `deposit_confirmed` to the player's record, which
 * may claim their pending selection (FR-012); the reward families to the reward the wallet's record is named by
 * (`awardId`, the reward id, FR-018): a conversion, a forfeit by a loss or an adjustment, a close (FR-014). A forfeit
 * for `forfeit`, `cancel` or `hold` is this service's own command, or payouts', recorded from the step's answer, and is
 * not read here. A record not of this service names no reward, and the reward answers it as not found, writing nothing.
 */
final class MoneyConsumer(client: ComponentClient, services: ServiceClients) extends Consumer[Option[MoneyTransactionsMessage], Nothing]:
  def onMessage(fact: Option[MoneyTransactionsMessage]): Effect = fact match
    case None =>
      Read.unreadable(MoneyTransactions.name, messageContext.subject)
      effects.ignore()
    case Some(f) =>
      MoneyConsumer.change(f) match
        case None => effects.ignore()
        case Some(MoneyConsumer.Change.Deposit(playerId, d)) =>
          Flows.deposit(client, RewardsRuntime.wallet(services), RewardsRuntime.settings.brand, d, playerId): Unit
          effects.done()
        case Some(MoneyConsumer.Change.ToReward(rewardId, command)) =>
          val r = client.forEventSourcedEntity(EntityId(rewardId))
          command match
            case c: reward.Convert => r.call(Reward.convert).invoke(c): Unit
            case c: reward.Forfeit => r.call(Reward.forfeit).invoke(c): Unit
            case c: reward.Close   => r.call(Reward.close).invoke(c): Unit
          effects.done()

object MoneyConsumer extends Consumer.Companion[MoneyConsumer, Option[MoneyTransactionsMessage], Nothing](
      ComponentId("rewards-money-consumer"), Read.from(MoneyTransactions)):
  def create(ctx: ConsumerContext) = new MoneyConsumer(ctx.componentClient, ctx.services)

  /** What a fact asks of this service. */
  enum Change:
    case Deposit(playerId: String, deposit: DepositSeen)
    case ToReward(rewardId: String, command: reward.Convert | reward.Forfeit | reward.Close)

  /** The wallet's forfeit reasons this service reads (FR-014); the others are its own commands' or payouts'. */
  def forfeitReason(reason: String): Option[ForfeitReason] = reason match
    case "loss"       => Some(ForfeitReason.BonusExhausted)
    case "adjustment" => Some(ForfeitReason.AdminAdjustEmptied)
    case _            => None

  /** W5's reasons for a pending cap the wallet closed (FR-014); the free rounds' finalisation is this service's own. */
  def closeReason(reason: String): Option[CloseReason] =
    CloseReason.from(reason).filterNot(_ == CloseReason.FreeSpinsFinalised)

  def change(fact: MoneyTransactionsMessage): Option[Change] = fact match
    case f: DepositConfirmed =>
      Some(Change.Deposit(f.playerId,
        DepositSeen(f.amountCents.value, f.currency.code, f.depositOrdinal, f.paymentReference, f.occurredAt)))
    case f: RestrictedFundsConverted => Some(Change.ToReward(f.awardId, reward.Convert(f.occurredAt)))
    case f: RestrictedFundsForfeited =>
      forfeitReason(f.reason).map(r => Change.ToReward(f.awardId, reward.Forfeit(r, Some(f.amountCents.value), f.occurredAt)))
    case f: RestrictedFundsClosed => closeReason(f.reason).map(r => Change.ToReward(f.awardId, reward.Close(r, f.occurredAt)))
    case _                        => None
