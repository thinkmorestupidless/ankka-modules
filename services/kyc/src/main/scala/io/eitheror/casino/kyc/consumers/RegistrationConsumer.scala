package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.KycRuntime
import io.eitheror.casino.kyc.policy.{Policy, PolicyEntity}
import io.eitheror.casino.kyc.record.{KycRecord, SetDeadline}

/**
 * `player_registered` into the player's deadline (R10, FR-025): registration plus the window of the policy
 * in force when it is read, so a window changed later applies only to players registered after. A policy
 * with no window sets none; a redelivery changes nothing, since the record sets a deadline once.
 */
final class RegistrationConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(f: PlayerRegistered) =>
      val policy = client.forEventSourcedEntity(EntityId(KycRuntime.settings.brand)).call(PolicyEntity.get).invoke()
      client.forEventSourcedEntity(EntityId(f.playerId)).call(KycRecord.setDeadline).invoke(RegistrationConsumer.command(f, policy)): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()

object RegistrationConsumer extends Consumer.Companion[RegistrationConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("kyc-registration-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new RegistrationConsumer(ctx.componentClient)

  def command(f: PlayerRegistered, policy: Policy): SetDeadline = SetDeadline(f.playerId, f.registeredAt, policy.windowDays, f.occurredAt)
