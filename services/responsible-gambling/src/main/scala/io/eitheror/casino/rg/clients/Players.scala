package io.eitheror.casino.rg.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Try

/**
 * `players`' exclusion route (spec 008 FR-032), the one call this service makes, from the sync timer only
 * and never on a check (R4). `true` when `players` answered 2xx; anything else, a failed call included, is
 * `false` and the timer tries again.
 */
trait Players:
  def exclude(playerId: String, at: Instant, until: Option[Instant]): Boolean
  def reinstate(playerId: String): Boolean

object Players:
  val Service = "players"

  def http(services: ServiceClients, brand: String): Players = HttpPlayers(services, s"/v1/casino/brands/$brand/players")

private final class HttpPlayers(services: ServiceClients, prefix: String) extends Players:
  private def path(playerId: String) = s"$prefix/${URLEncoder.encode(playerId, UTF_8).replace("+", "%20")}/exclusion"

  private def ok(method: String, playerId: String, body: Option[String]): Boolean =
    Try(services(Players.Service).request(method, path(playerId), body.map(_.getBytes(UTF_8)), body.map(_ => "application/json")))
      .toOption.exists(r => r.status / 100 == 2)

  def exclude(playerId: String, at: Instant, until: Option[Instant]): Boolean =
    ok("PUT", playerId, Some(until.fold(s"""{"at":"$at"}""")(u => s"""{"at":"$at","until":"$u"}""")))

  def reinstate(playerId: String): Boolean = ok("DELETE", playerId, None)
