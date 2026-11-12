package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f

/**
 * The six intake consumers of data-model.md's *Consumers*: each reads its topic from the earliest offset with a
 * lenient serializer and applies each fact once, by its `FactId`, to the records it changes.
 */
object Intake:
  def descriptors: Seq[ComponentDescriptor] = Seq(
    PlayersIntake.descriptor, SessionsIntake.descriptor, RoundsIntake.descriptor, MoneyIntake.descriptor,
    WithdrawalsIntake.descriptor, RewardsIntake.descriptor)

  private[records] def skipped(topic: String, ctx: String): Unit = Records.unreadable(topic, ctx)

final class PlayersIntakeConsumer(client: ComponentClient) extends Consumer[Option[f.CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[f.CasinoPlayersFact]): Effect = fact match
    case None => Intake.skipped(f.CasinoPlayers.name, messageContext.subject); effects.ignore()
    case Some(x) if !PlayerFold.changes(x) => effects.ignore()
    case Some(x) =>
      client.forKeyValueEntity(EntityId(x.playerId)).call(PlayerRecord.record).invoke(PlayerFact(FactId.of(f.CasinoPlayers, x), x)): Unit
      effects.done()

object PlayersIntake extends Consumer.Companion[PlayersIntakeConsumer, Option[f.CasinoPlayersFact], Nothing](
      com.thinkmorestupidless.ankka.core.ComponentId("players-intake"), Lenient.from(f.CasinoPlayers)):
  def create(ctx: ConsumerContext) = new PlayersIntakeConsumer(ctx.componentClient)

final class SessionsIntakeConsumer(client: ComponentClient) extends Consumer[Option[f.CasinoSessionsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoSessionsFact]): Effect = fact match
    case None => Intake.skipped(f.CasinoSessions.name, messageContext.subject); effects.ignore()
    case Some(x) =>
      SessionFold.sessionId(x).fold(effects.ignore()) { id =>
        client.forKeyValueEntity(EntityId(id)).call(SessionRecord.record).invoke(SessionFact(FactId.of(f.CasinoSessions, x), x)): Unit
        effects.done()
      }

object SessionsIntake extends Consumer.Companion[SessionsIntakeConsumer, Option[f.CasinoSessionsFact], Nothing](
      com.thinkmorestupidless.ankka.core.ComponentId("sessions-intake"), Lenient.from(f.CasinoSessions)):
  def create(ctx: ConsumerContext) = new SessionsIntakeConsumer(ctx.componentClient)

final class RoundsIntakeConsumer(client: ComponentClient) extends Consumer[Option[f.CasinoRoundsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoRoundsFact]): Effect = fact match
    case None => Intake.skipped(f.CasinoRounds.name, messageContext.subject); effects.ignore()
    case Some(x) =>
      client.forKeyValueEntity(EntityId(Records.roundId(RoundFold.key(x)))).call(RoundRecord.record)
        .invoke(RoundFact(FactId.of(f.CasinoRounds, x), x)): Unit
      effects.done()

object RoundsIntake extends Consumer.Companion[RoundsIntakeConsumer, Option[f.CasinoRoundsFact], Nothing](
      com.thinkmorestupidless.ankka.core.ComponentId("rounds-intake"), Lenient.from(f.CasinoRounds)):
  def create(ctx: ConsumerContext) = new RoundsIntakeConsumer(ctx.componentClient)

/**
 * `money.transactions` into transactions, bets, the round keys' bets and the bonus awards' wagering. A win, rollback
 * or loss changes the status of the round key's bets and their BET rows, and a rollback or loss takes its game from them.
 */
final class MoneyIntakeConsumer(client: ComponentClient) extends Consumer[Option[f.MoneyTransactionsMessage], Nothing]:
  def onMessage(message: Option[f.MoneyTransactionsMessage]): Effect = message match
    case None => Intake.skipped(f.MoneyTransactions.name, messageContext.subject); effects.ignore()
    case Some(m) =>
      val id     = FactId.of(f.MoneyTransactions, m)
      val change = MoneyFold(m)
      val at     = m match { case x: f.MoneyTransactionsFact => Some(x); case _ => None }
      val key    = change.roundKey.map { k =>
        val index = client.forKeyValueEntity(EntityId(k))
        change.bet.fold(index.call(BetsByRoundKey.get).invoke())(b => index.call(BetsByRoundKey.add).invoke(AddBet(b.id, b.gameId)))
      }
      change.bet.foreach(b => client.forKeyValueEntity(EntityId(b.id)).call(BetRecord.put).invoke(BetPut(id, b)): Unit)
      change.rows.foreach { r =>
        val row = r.copy(gameId = r.gameId.orElse(key.flatMap(_.gameId)))
        client.forKeyValueEntity(EntityId(row.id)).call(TransactionRecord.put).invoke(TxPut(id, row)): Unit
      }
      for status <- change.betsBecome; k <- key; bet <- k.betIds do
        client.forKeyValueEntity(EntityId(bet)).call(BetRecord.status).invoke(BetStatus(id, status)): Unit
        client.forKeyValueEntity(EntityId(bet)).call(TransactionRecord.status).invoke(TxStatus(id, status)): Unit
      change.payout.foreach { payout =>
        val p = client.forKeyValueEntity(EntityId(payout)).call(WithdrawalsByRequest.add).invoke(AddHold(change.rows.head.id))
        if p.completed then client.forKeyValueEntity(EntityId(change.rows.head.id)).call(TransactionRecord.status).invoke(TxStatus(s"$id/completed", "completed")): Unit
      }
      at.foreach { x =>
        change.award.foreach(a =>
          client.forKeyValueEntity(EntityId(a.awardId)).call(BonusAwardRecord.money)
            .invoke(BonusMoney(s"$id/award", x.playerId, Some(a.requirementCents), None, None, x.occurredAt)): Unit)
        change.progress.foreach(p =>
          client.forKeyValueEntity(EntityId(p.awardId)).call(BonusAwardRecord.money)
            .invoke(BonusMoney(s"$id/${p.awardId}", x.playerId, Some(p.requirementCents.value), Some(p.progressCents.value), Some(p.status),
              x.occurredAt)): Unit)
      }
      effects.done()

object MoneyIntake extends Consumer.Companion[MoneyIntakeConsumer, Option[f.MoneyTransactionsMessage], Nothing](
      com.thinkmorestupidless.ankka.core.ComponentId("money-intake"), Lenient.from(f.MoneyTransactions)):
  def create(ctx: ConsumerContext) = new MoneyIntakeConsumer(ctx.componentClient)

/** `withdrawal_completed` completes the payout's WITHDRAWAL rows, now or when `hold_placed` arrives after it. */
final class WithdrawalsIntakeConsumer(client: ComponentClient) extends Consumer[Option[f.CasinoWithdrawalsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoWithdrawalsFact]): Effect = fact match
    case None => Intake.skipped(f.CasinoWithdrawals.name, messageContext.subject); effects.ignore()
    case Some(x: f.WithdrawalCompleted) =>
      val id = FactId.of(f.CasinoWithdrawals, x)
      val p  = client.forKeyValueEntity(EntityId(x.payoutId)).call(WithdrawalsByRequest.complete).invoke(Complete(x.payoutId))
      p.txIds.foreach(tx => client.forKeyValueEntity(EntityId(tx)).call(TransactionRecord.status).invoke(TxStatus(id, "completed")): Unit)
      effects.done()
    case Some(_) => effects.ignore()

object WithdrawalsIntake extends Consumer.Companion[WithdrawalsIntakeConsumer, Option[f.CasinoWithdrawalsFact], Nothing](
      com.thinkmorestupidless.ankka.core.ComponentId("withdrawals-intake"), Lenient.from(f.CasinoWithdrawals)):
  def create(ctx: ConsumerContext) = new WithdrawalsIntakeConsumer(ctx.componentClient)

final class RewardsIntakeConsumer(client: ComponentClient) extends Consumer[Option[f.CasinoRewardsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoRewardsFact]): Effect = fact match
    case None => Intake.skipped(f.CasinoRewards.name, messageContext.subject); effects.ignore()
    case Some(x) =>
      BonusFold.bonusId(x).fold(effects.ignore()) { id =>
        client.forKeyValueEntity(EntityId(id)).call(BonusAwardRecord.reward).invoke(BonusFact(FactId.of(f.CasinoRewards, x), x)): Unit
        effects.done()
      }

object RewardsIntake extends Consumer.Companion[RewardsIntakeConsumer, Option[f.CasinoRewardsFact], Nothing](
      com.thinkmorestupidless.ankka.core.ComponentId("rewards-intake"), Lenient.from(f.CasinoRewards)):
  def create(ctx: ConsumerContext) = new RewardsIntakeConsumer(ctx.componentClient)
