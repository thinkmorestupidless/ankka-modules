package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.record.{Close, KycRecord}

/**
 * `player_closed` into `Close` (FR-031): the record's open flags closed and, through `RecordTimers`, the
 * window's timer cancelled. A redelivery changes nothing.
 */
final class ClosureConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(f: PlayerClosed) =>
      client.forEventSourcedEntity(EntityId(f.playerId)).call(KycRecord.close).invoke(ClosureConsumer.command(f)): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()

object ClosureConsumer extends Consumer.Companion[ClosureConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("kyc-closure-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new ClosureConsumer(ctx.componentClient)

  def command(f: PlayerClosed): Close = Close(f.occurredAt)
