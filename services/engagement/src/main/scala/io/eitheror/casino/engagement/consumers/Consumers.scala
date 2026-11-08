package io.eitheror.casino.engagement.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.engagement.EngagementRuntime
import io.eitheror.casino.engagement.channel.{AgentChannel, Channel, ChannelCommand, Frames, Origin, PlayerOnline}
import io.eitheror.casino.engagement.profile.{Applied, MoneyFact, PaymentsFact, PlayersFact, Profile, ProfileState}

/**
 * The brand's facts into this service (data-model.md *Consumers*): each applied to the player's `Profile`, where it
 * changes one, then its sends claimed (`Sends`). Each topic is read at least once; the profile's per-field times and
 * the send's claim make a repeat change nothing and call nothing.
 */
private def profile(client: ComponentClient, playerId: String) = client.forKeyValueEntity(EntityId(playerId))

/**
 * `casino.players`: the profile, its events and identifies (contracts/facts.md); an inbox item created, read or deleted
 * is a signal on the player's channel (R10), which holds it only while a connection is open; a login is also told to
 * the brand's agent stream.
 */
final class PlayersConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  private def signal(playerId: String, messageId: String, origin: String): Effect =
    client.forEventSourcedEntity(EntityId(playerId)).call(Channel.signal)
      .invoke(ChannelCommand.Raise(Frames.InboxChanged, messageId, origin, EngagementRuntime.now())): Unit
    effects.done()

  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()
    case Some(m: PlayerMessageCreated) => signal(m.playerId, m.messageId, Origin.Created)
    case Some(m: PlayerMessageRead)    => signal(m.playerId, m.messageId, Origin.Read)
    case Some(m: PlayerMessageDeleted) => signal(m.playerId, m.messageId, Origin.Deleted)
    case Some(l: PlayerLoggedIn) =>
      val done = applied(l)
      // The backoffice's agent stream is told who came online, with the profile's email prefix (US7).
      val email = ProfileState.reveal(profile(client, l.playerId).call(Profile.get).invoke().email)
      client.forEventSourcedEntity(EntityId(EngagementRuntime.settings.brand)).call(AgentChannel.signal)
        .invoke(PlayerOnline.of(l.playerId, email, l.sessionId, l.occurredAt)): Unit
      done
    case Some(f) => applied(f)

  private def applied(f: CasinoPlayersFact): Effect =
    val applied = profile(client, f.playerId).call(Profile.players).invoke(PlayersFact(f))
    Sends.start(client, Sends.of(f.playerId, Sends.factId(CasinoPlayers, f), f, applied))
    effects.done()

object PlayersConsumer extends Consumer.Companion[PlayersConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("engagement-players-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new PlayersConsumer(ctx.componentClient)

/** `money.transactions`: the balances and the deposit count; `deposit_confirmed` and the first deposit; else an identify. */
final class MoneyConsumer(client: ComponentClient) extends Consumer[Option[MoneyTransactionsMessage], Nothing]:
  def onMessage(fact: Option[MoneyTransactionsMessage]): Effect = fact match
    case None =>
      Read.unreadable(MoneyTransactions.name, messageContext.subject)
      effects.ignore()
    case Some(f: MoneyTransactionsFact) =>
      val applied = profile(client, f.playerId).call(Profile.money).invoke(MoneyFact(f))
      Sends.start(client, Sends.of(f.playerId, Sends.factId(MoneyTransactions, f), f, applied))
      effects.done()
    case Some(_) => effects.ignore()

object MoneyConsumer extends Consumer.Companion[MoneyConsumer, Option[MoneyTransactionsMessage], Nothing](
      ComponentId("engagement-money-consumer"), Read.from(MoneyTransactions)):
  def create(ctx: ConsumerContext) = new MoneyConsumer(ctx.componentClient)

/** `payments.payments`: an initiated payment remembered for its failure's data; a failure or expiry `payment_failed`. */
final class PaymentsConsumer(client: ComponentClient) extends Consumer[Option[PaymentsPaymentsFact], Nothing]:
  def onMessage(fact: Option[PaymentsPaymentsFact]): Effect = fact match
    case None =>
      Read.unreadable(PaymentsPayments.name, messageContext.subject)
      effects.ignore()
    case Some(f) =>
      val applied = profile(client, f.playerId).call(Profile.payments).invoke(PaymentsFact(f))
      Sends.start(client, Sends.of(f.playerId, Sends.factId(PaymentsPayments, f), f, applied))
      effects.done()

object PaymentsConsumer extends Consumer.Companion[PaymentsConsumer, Option[PaymentsPaymentsFact], Nothing](
      ComponentId("engagement-payments-consumer"), Read.from(PaymentsPayments)):
  def create(ctx: ConsumerContext) = new PaymentsConsumer(ctx.componentClient)

/**
 * A topic whose facts change no profile attribute and only make events: `casino.sessions`, `casino.withdrawals` and
 * `casino.rewards`.
 */
abstract class EventsConsumer[F <: Fact](client: ComponentClient, topic: Topic[F], playerId: F => String)
    extends Consumer[Option[F], Nothing]:
  def onMessage(fact: Option[F]): Effect = fact match
    case None =>
      Read.unreadable(topic.name, messageContext.subject)
      effects.ignore()
    case Some(f) =>
      Sends.start(client, Sends.of(playerId(f), Sends.factId(topic, f), f, Applied(changed = false)))
      effects.done()

final class SessionsConsumer(client: ComponentClient)
    extends EventsConsumer[CasinoSessionsFact](client, CasinoSessions, _.playerId)

object SessionsConsumer extends Consumer.Companion[SessionsConsumer, Option[CasinoSessionsFact], Nothing](
      ComponentId("engagement-sessions-consumer"), Read.from(CasinoSessions)):
  def create(ctx: ConsumerContext) = new SessionsConsumer(ctx.componentClient)

final class WithdrawalsConsumer(client: ComponentClient)
    extends EventsConsumer[CasinoWithdrawalsFact](client, CasinoWithdrawals, _.playerId)

object WithdrawalsConsumer extends Consumer.Companion[WithdrawalsConsumer, Option[CasinoWithdrawalsFact], Nothing](
      ComponentId("engagement-withdrawals-consumer"), Read.from(CasinoWithdrawals)):
  def create(ctx: ConsumerContext) = new WithdrawalsConsumer(ctx.componentClient)

final class RewardsConsumer(client: ComponentClient)
    extends EventsConsumer[CasinoRewardsFact](client, CasinoRewards, _.playerId)

object RewardsConsumer extends Consumer.Companion[RewardsConsumer, Option[CasinoRewardsFact], Nothing](
      ComponentId("engagement-rewards-consumer"), Read.from(CasinoRewards)):
  def create(ctx: ConsumerContext) = new RewardsConsumer(ctx.componentClient)
