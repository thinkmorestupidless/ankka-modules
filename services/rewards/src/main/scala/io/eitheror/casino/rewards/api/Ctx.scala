package io.eitheror.casino.rewards.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.rewards.{RewardsRuntime, Settings}
import io.eitheror.casino.rewards.clients.{GameProvider, Wallet}
import io.eitheror.casino.rewards.offers.{OfferBook, OfferBookState}
import io.eitheror.casino.rewards.player.{PlayerRewards, PlayerRewardsState}
import io.eitheror.casino.rewards.reward.{Reward, RewardState}
import io.eitheror.casino.rewards.steps.{StepState, WalletStep}

import java.time.Instant

/** What every endpoint is built with, as payouts': the service's clients and the authenticator; the settings, clock and neighbours. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings           = RewardsRuntime.settings
  def slug: String                 = settings.brand
  def now(): Instant               = RewardsRuntime.now()
  def wallet: Wallet               = RewardsRuntime.wallet(clients.services)
  def gameProvider: GameProvider   = RewardsRuntime.gameProvider(clients.services)

  def components = clients.componentClient

  def book                         = components.forEventSourcedEntity(EntityId(slug))
  def offers: OfferBookState       = book.call(OfferBook.get).invoke()
  def player(id: String)           = components.forEventSourcedEntity(EntityId(id))
  def playerState(id: String): PlayerRewardsState = player(id).call(PlayerRewards.get).invoke()
  def reward(id: String)           = components.forEventSourcedEntity(EntityId(id))
  def rewardState(id: String): RewardState = reward(id).call(Reward.get).invoke()
  def step(id: String): StepState  = components.forWorkflow(EntityId(id)).call(WalletStep.status).invoke()

  /**
   * The programme's codes for the brand, against which a code must be unique (FR-003). The code document is read with
   * `CodeDocument` (task T043); until then there is none.
   */
  def programmeCodes: Set[String] = Set.empty
