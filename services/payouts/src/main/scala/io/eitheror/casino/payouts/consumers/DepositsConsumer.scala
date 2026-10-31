package io.eitheror.casino.payouts.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.payouts.player.{PlayerPayouts, SeeDeposit}

/**
 * `deposit_confirmed` into the gateways and schemes the player has deposited through, which order the
 * eligible terminals (R4). A deposit that names no gateway says nothing about affinity and is ignored; one
 * already recorded, as a redelivery is, writes nothing.
 */
final class DepositsConsumer(client: ComponentClient) extends Consumer[Option[MoneyTransactionsMessage], Nothing]:
  def onMessage(fact: Option[MoneyTransactionsMessage]): Effect = fact match
    case Some(f: DepositConfirmed) =>
      DepositsConsumer.command(f) match
        case Some(c) =>
          client.forEventSourcedEntity(EntityId(f.playerId)).call(PlayerPayouts.seeDeposit).invoke(c): Unit
          effects.done()
        case None => effects.ignore()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(MoneyTransactions.name, messageContext.subject)
      effects.ignore()

object DepositsConsumer extends Consumer.Companion[DepositsConsumer, Option[MoneyTransactionsMessage], Nothing](
      ComponentId("payouts-deposits-consumer"), Read.from(MoneyTransactions)):
  def create(ctx: ConsumerContext) = new DepositsConsumer(ctx.componentClient)

  def command(f: DepositConfirmed): Option[SeeDeposit] =
    f.psp.map(_.trim).filter(_.nonEmpty).map(SeeDeposit(_, f.scheme.map(_.trim).filter(_.nonEmpty), f.occurredAt))
