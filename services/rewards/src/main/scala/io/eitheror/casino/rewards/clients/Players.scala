package io.eitheror.casino.rewards.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.rewards.Settings
import io.eitheror.casino.rewards.Json.*

/** The part of the brand's configuration rewards reads: the duplicate detector's switch (research R9). */
final case class BrandConfig(multiAccountDetection: Boolean)

/** `players`' configuration read, a service route (spec 008 `GET /config/{name}`). */
trait Players:
  def brandConfig(): Called[BrandConfig]

object Players:
  def http(services: ServiceClients, settings: Settings): Players = Http(Neighbour(services, settings.players), settings.brand)

  final class Http(players: Neighbour, brand: String) extends Players:
    /** Absent, the switch is on, as `players`' own default. */
    def brandConfig(): Called[BrandConfig] =
      Called.of(players.get(s"/v1/casino/brands/${Neighbour.segment(brand)}/players/config/brand-config"), "players") { b =>
        Some(BrandConfig(b.bool("multiAccountDetection").getOrElse(true)))
      }
