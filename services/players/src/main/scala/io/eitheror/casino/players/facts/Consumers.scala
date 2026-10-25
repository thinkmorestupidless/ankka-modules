package io.eitheror.casino.players.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.PlayersRuntime
import io.eitheror.casino.players.inbox.{MessageAt, MessageEntity}
import io.eitheror.casino.players.player.{At, PlayerEntity, SetSessionLimit, Withdrawal}
import io.eitheror.casino.players.sessions.SessionLimits
import org.slf4j.LoggerFactory

/**
 * What this service does with other services' facts (data-model.md's consumers). Each reads its topic
 * from `StartFrom.Latest` through the lenient serializer: a fact it cannot read is logged and skipped,
 * never retried. Every command it sends is idempotent on the entity, so a redelivery changes nothing.
 */
private[facts] object Read:
  val log = LoggerFactory.getLogger("io.eitheror.casino.players.facts")

  def from[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, Lenient.of(topic), StartFrom.Latest, TopicOptions(Some(topic.contract), None, false))

  def player(client: ComponentClient, playerId: String) = client.forEventSourcedEntity(EntityId(playerId))

  def unreadable(topic: String, subject: String): Unit =
    log.warn("a {} fact this service cannot read was skipped (subject {})", topic, subject)

/**
 * `rg_limit_applied` for `SESSION_MINUTES` (FR-016, R5): the player's limit set, and each open game
 * session's timer rescheduled by name to the minutes it has left, so a session already over the new
 * limit ends at once. Any other limit is responsible-gambling's alone.
 */
final class RgLimitsConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(f: RgLimitApplied) if f.limitType == RgLimitsConsumer.SessionMinutes =>
      val minutes    = f.value.map(_.toInt).filter(_ > 0)
      val remainders = Read.player(client, f.playerId).call(PlayerEntity.setSessionLimit).invoke(SetSessionLimit(minutes, PlayersRuntime.now()))
      val timers     = PlayersRuntime.timers()
      remainders.open.foreach((sessionId, left) => SessionLimits.schedule(timers, sessionId, f.playerId, left))
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()

object RgLimitsConsumer extends Consumer.Companion[RgLimitsConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("rg-limits-consumer"), Read.from(CasinoPlayers)):
  val SessionMinutes = "SESSION_MINUTES"
  def create(ctx: ConsumerContext) = new RgLimitsConsumer(ctx.componentClient)

/** `kyc`'s verdict (spec 009): a verified player's date of birth is locked until the verification is withdrawn. */
final class KycFactsConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(f: PlayerKycVerified) =>
      Read.player(client, f.playerId).call(PlayerEntity.kycVerified).invoke(At(f.occurredAt)): Unit
      effects.done()
    case Some(f: PlayerKycRejected) =>
      Read.player(client, f.playerId).call(PlayerEntity.kycWithdrawn).invoke(At(f.occurredAt)): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()

object KycFactsConsumer extends Consumer.Companion[KycFactsConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("kyc-facts-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new KycFactsConsumer(ctx.componentClient)

/** A withdrawal in flight holds an erasure back (FR-037): requested opens it; completed, cancelled or failed settles it. */
final class WithdrawalsConsumer(client: ComponentClient) extends Consumer[Option[CasinoWithdrawalsFact], Nothing]:
  def onMessage(fact: Option[CasinoWithdrawalsFact]): Effect =
    def player(id: String) = Read.player(client, id)
    def settled(playerId: String, payoutId: String, at: java.time.Instant) =
      player(playerId).call(PlayerEntity.withdrawalSettled).invoke(Withdrawal(payoutId, at)): Unit
      effects.done()
    fact match
      case Some(f: WithdrawalRequested) =>
        player(f.playerId).call(PlayerEntity.withdrawalOpened).invoke(Withdrawal(f.payoutId, f.occurredAt)): Unit
        effects.done()
      case Some(f: WithdrawalCompleted) => settled(f.playerId, f.payoutId, f.occurredAt)
      case Some(f: WithdrawalCancelled) => settled(f.playerId, f.payoutId, f.occurredAt)
      case Some(f: WithdrawalFailed)    => settled(f.playerId, f.payoutId, f.occurredAt)
      case Some(_) => effects.ignore()
      case None =>
        Read.unreadable(CasinoWithdrawals.name, messageContext.subject)
        effects.ignore()

object WithdrawalsConsumer extends Consumer.Companion[WithdrawalsConsumer, Option[CasinoWithdrawalsFact], Nothing](
      ComponentId("withdrawals-consumer"), Read.from(CasinoWithdrawals)):
  def create(ctx: ConsumerContext) = new WithdrawalsConsumer(ctx.componentClient)

/** `push_delivered` (spec 014): the message whose push it was, marked delivered. Its correlation id is the message id. */
final class PushDeliveredConsumer(client: ComponentClient) extends Consumer[Option[CasinoPushFact], Nothing]:
  def onMessage(fact: Option[CasinoPushFact]): Effect = fact match
    case Some(f: PushDelivered) if f.correlationId.nonEmpty && f.connections > 0 =>
      client.forEventSourcedEntity(EntityId(f.correlationId)).call(MessageEntity.delivered).invoke(MessageAt(f.occurredAt)): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoPush.name, messageContext.subject)
      effects.ignore()

object PushDeliveredConsumer extends Consumer.Companion[PushDeliveredConsumer, Option[CasinoPushFact], Nothing](
      ComponentId("push-delivered-consumer"), Read.from(CasinoPush)):
  def create(ctx: ConsumerContext) = new PushDeliveredConsumer(ctx.componentClient)

/** `bonus_code_entered` (spec 011): the code a registration carried has been taken, so the sign-in answer stops naming it. */
final class RewardsConsumer(client: ComponentClient) extends Consumer[Option[CasinoRewardsFact], Nothing]:
  def onMessage(fact: Option[CasinoRewardsFact]): Effect = fact match
    case Some(f: BonusCodeEntered) =>
      Read.player(client, f.playerId).call(PlayerEntity.clearPendingBonusCode).invoke(At(f.occurredAt)): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoRewards.name, messageContext.subject)
      effects.ignore()

object RewardsConsumer extends Consumer.Companion[RewardsConsumer, Option[CasinoRewardsFact], Nothing](
      ComponentId("rewards-consumer"), Read.from(CasinoRewards)):
  def create(ctx: ConsumerContext) = new RewardsConsumer(ctx.componentClient)
