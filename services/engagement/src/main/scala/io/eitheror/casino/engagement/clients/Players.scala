package io.eitheror.casino.engagement.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.engagement.{Json, Settings}

/** `players`' CRM message route, the webhook's `send_message` (spec 008; contracts/routes.md). */
trait Players:
  def message(playerId: String, deliveryId: String, subject: String, body: String): Reply

object Players:
  def http(services: ServiceClients, settings: Settings): Players = Http(Neighbour(services, settings.players), settings.brand)

  final class Http(players: Neighbour, brand: String) extends Players:
    def message(playerId: String, deliveryId: String, subject: String, body: String): Reply =
      players.post(s"/v1/casino/brands/${Neighbour.segment(brand)}/players/${Neighbour.segment(playerId)}/messages",
        Json.obj("deliveryId" -> deliveryId, "category" -> "marketing", "subject" -> subject, "body" -> body))
