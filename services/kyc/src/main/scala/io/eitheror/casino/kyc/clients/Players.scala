package io.eitheror.casino.kyc.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.kyc.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Try

/**
 * `players`' suspend route (contracts/routes.md *Calls out*), the one call this service makes, from the
 * window's timer only (R10). `Right` when `players` answered 2xx; anything else, a failed call included,
 * is `Left` naming it, and the timer throws so it runs again.
 */
trait Players:
  def suspend(playerId: String, deadline: Instant): Either[String, Unit]

object Players:
  val Service = "players"
  val Reason  = "kyc_window_expired"

  def http(services: ServiceClients, brand: String): Players = HttpPlayers(services, s"/v1/casino/brands/$brand/players")

private final class HttpPlayers(services: ServiceClients, prefix: String) extends Players:
  private def path(playerId: String) = s"$prefix/${URLEncoder.encode(playerId, UTF_8).replace("+", "%20")}/suspend"

  def suspend(playerId: String, deadline: Instant): Either[String, Unit] =
    val body = Json.render(Json.obj("reason" -> Players.Reason, "deadline" -> deadline.toString)).getBytes(UTF_8)
    Try(services(Players.Service).request("POST", path(playerId), Some(body), Some("application/json"))).toEither.left
      .map(e => s"players could not be reached: ${e.getClass.getSimpleName}")
      .flatMap(r => if r.status / 100 == 2 then Right(()) else Left(s"players answered ${r.status}"))
