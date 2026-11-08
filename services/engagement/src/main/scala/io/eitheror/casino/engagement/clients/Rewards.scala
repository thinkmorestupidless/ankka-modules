package io.eitheror.casino.engagement.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.engagement.{Json, Settings}

/** `rewards`' campaign routes, the webhook's `target_offer` and `grant_bonus` (spec 011 R7; contracts/routes.md). */
trait Rewards:
  def target(playerId: String, role: String, context: Option[JsonNode]): Reply
  def award(playerId: String, offerId: String, role: Option[String], currency: Option[String]): Reply

object Rewards:
  def http(services: ServiceClients, settings: Settings): Rewards = Http(Neighbour(services, settings.rewards), settings.brand)

  final class Http(rewards: Neighbour, brand: String) extends Rewards:
    private def path(playerId: String, action: String) =
      s"/v1/casino/brands/${Neighbour.segment(brand)}/reward-campaigns/${Neighbour.segment(playerId)}/$action"

    def target(playerId: String, role: String, context: Option[JsonNode]): Reply =
      rewards.post(path(playerId, "target"), Json.obj("role" -> role, "context" -> context))

    def award(playerId: String, offerId: String, role: Option[String], currency: Option[String]): Reply =
      rewards.post(path(playerId, "award"), Json.obj("offerId" -> offerId, "role" -> role, "currency" -> currency))
