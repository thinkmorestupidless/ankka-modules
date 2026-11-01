package io.eitheror.casino.rewards.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.offers.{OfferBook, OfferEvent}
import io.eitheror.casino.rewards.reward.{Reward, RewardEvent, RewardState}

/** The reward as it stands: its opening's fixed part (player, offer, amount, currency, source) is what a fact reads. */
private def rewardOf(client: ComponentClient, rewardId: String): RewardState =
  client.forEventSourcedEntity(EntityId(rewardId)).call(Reward.get).invoke()

/**
 * `Reward`'s families on `casino.rewards`, keyed by the player, `ce-id` `<reward>/<seq>` (contracts/facts.md).
 *
 * `bonus_code_entered` comes from `PlayerRewards`' code entries through `CodesPublisher`.
 */
final class RewardsPublisher(client: ComponentClient) extends Consumer[RewardEvent, CasinoRewardsFact]:
  def onMessage(event: RewardEvent): Effect =
    val id = messageContext.subject
    val published = if Facts.publishes(event) then Facts.reward(id, messageContext.sequenceNumber, event, rewardOf(client, id)) else None
    published.fold(effects.ignore())(p =>
      effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Metadata.empty.set("ce-id", p.id)))))

object RewardsPublisher extends Consumer.Companion[RewardsPublisher, RewardEvent, CasinoRewardsFact](
      ComponentId("rewards-publisher"), ChangeSource.eventsOf(Reward)):
  override val outputSerializer: Option[Serializer[CasinoRewardsFact]] = Some(CasinoRewards.serializer)
  override val produceTo: Option[String]                               = Some(CasinoRewards.name)
  override val produces: Option[Publication] = Some(Publication(CasinoRewards.name, Some(CasinoRewards.contract), None))
  def create(ctx: ConsumerContext) = new RewardsPublisher(ctx.componentClient)

/**
 * An operator's actions on a reward on `platform.audit` (`Facts.audit`). The offer book's records are a second audit
 * consumer over `OfferBook`'s events, `OfferAuditPublisher`.
 */
final class AuditPublisher(client: ComponentClient) extends AuditConsumer[RewardEvent]:
  protected def audit(change: RewardEvent): Option[AuditRecord] = change match
    case _: RewardEvent.Activated | _: RewardEvent.Revoked =>
      val id = messageContext.subject
      Facts.audit(RewardsRuntime.settings.brand, id, messageContext.sequenceNumber, change, rewardOf(client, id)).map(_.fact)
    case _ => None

object AuditPublisher extends AuditCompanion[AuditPublisher, RewardEvent]("rewards-audit-publisher", ChangeSource.eventsOf(Reward)):
  def create(ctx: ConsumerContext) = new AuditPublisher(ctx.componentClient)

/** The offer book's changes on `platform.audit` (`Facts.offerAudit`): every `OfferBook` event is an operator's. */
final class OfferAuditPublisher extends AuditConsumer[OfferEvent]:
  protected def audit(change: OfferEvent): Option[AuditRecord] =
    Some(Facts.offerAudit(RewardsRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change).fact)

object OfferAuditPublisher
    extends AuditCompanion[OfferAuditPublisher, OfferEvent]("rewards-offer-audit-publisher", ChangeSource.eventsOf(OfferBook)):
  def create(ctx: ConsumerContext) = new OfferAuditPublisher
