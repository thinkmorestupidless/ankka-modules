package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.KycRuntime
import io.eitheror.casino.kyc.policy.{Policy, PolicyEntity}
import io.eitheror.casino.kyc.record.{CountDeposit, KycRecord}

/**
 * `deposit_confirmed` into the player's lifetime deposits (R6): per currency, counted once by the
 * wallet's `depositOrdinal`, so a redelivery changes nothing. The record raises FR-038's large-deposit flag
 * when the deposit alone, in the KYC currency, exceeds the deposit threshold. A deposit for a player with
 * no record starts one.
 */
final class DepositsConsumer(client: ComponentClient) extends Consumer[Option[MoneyTransactionsFact], Nothing]:
  def onMessage(fact: Option[MoneyTransactionsFact]): Effect = fact match
    case Some(f: DepositConfirmed) =>
      val policy = client.forEventSourcedEntity(EntityId(KycRuntime.settings.brand)).call(PolicyEntity.get).invoke()
      client.forEventSourcedEntity(EntityId(f.playerId)).call(KycRecord.countDeposit).invoke(DepositsConsumer.command(f, policy)): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(MoneyTransactions.name, messageContext.subject)
      effects.ignore()

object DepositsConsumer extends Consumer.Companion[DepositsConsumer, Option[MoneyTransactionsFact], Nothing](
      ComponentId("kyc-deposits-consumer"), Read.from(MoneyTransactions)):
  def create(ctx: ConsumerContext) = new DepositsConsumer(ctx.componentClient)

  def command(f: DepositConfirmed, policy: Policy): CountDeposit =
    CountDeposit(f.playerId, f.currency.code, f.amountCents.value, f.depositOrdinal, policy, f.occurredAt)
