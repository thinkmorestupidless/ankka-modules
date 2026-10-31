package io.eitheror.casino.payouts.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.payouts.{PayoutsRuntime, Settings}
import io.eitheror.casino.payouts.clients.{Gateway, Wallet}
import io.eitheror.casino.payouts.limits.{LimitsState, WithdrawalLimits}
import io.eitheror.casino.payouts.player.{PlayerPayouts, PlayerState}
import io.eitheror.casino.payouts.queue.{QueueRow, WithdrawalQueue}
import io.eitheror.casino.payouts.request.{RequestState, WithdrawalRequest}
import io.eitheror.casino.payouts.workflow.{Flow, WithdrawalWorkflow}

import java.time.Instant

/** What every endpoint is built with: the service's clients and the authenticator; the settings, the clock and the neighbours. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings = PayoutsRuntime.settings
  def slug: String       = settings.brand
  def now(): Instant     = PayoutsRuntime.now()
  def wallet: Wallet     = PayoutsRuntime.wallet(clients.services)
  def gateway: Gateway   = PayoutsRuntime.gateway(clients.services)

  def components = clients.componentClient

  def player(id: String)   = components.forEventSourcedEntity(EntityId(id))
  def request(id: String)  = components.forEventSourcedEntity(EntityId(id))
  def workflow(id: String) = components.forWorkflow(EntityId(id))

  def playerState(id: String): PlayerState   = player(id).call(PlayerPayouts.get).invoke()
  def requestState(id: String): RequestState = request(id).call(WithdrawalRequest.get).invoke()
  def flow(id: String): Flow                 = workflow(id).call(WithdrawalWorkflow.status).invoke()
  def limits: LimitsState                    = components.forEventSourcedEntity(EntityId(slug)).call(WithdrawalLimits.get).invoke()

  def queue = clients.viewClient.forView(WithdrawalQueue)
  def byPlayer(playerId: String): Vector[QueueRow] = WithdrawalQueue.byPlayer(queue, playerId)

