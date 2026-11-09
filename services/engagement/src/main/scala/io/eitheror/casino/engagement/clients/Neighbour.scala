package io.eitheror.casino.engagement.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceResponse}
import io.eitheror.casino.engagement.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** A neighbour's answer, passed to the webhook's caller as it is (contracts/routes.md), or that it did not answer. */
enum Reply:
  case Answered(status: Int, text: String)
  case Unreachable(who: String)

/**
 * Calls to another service of this project as this service, by name and identity. No body or path is logged: both
 * may name the player.
 */
final class Neighbour(services: ServiceClients, name: String):
  def post(path: String, body: JsonNode): Reply =
    Try(services(name).request("POST", path, Some(Json.render(body).getBytes(UTF_8)), Some("application/json"), Nil))
      .fold(_ => Reply.Unreachable(name), answered)

  private def answered(r: ServiceResponse): Reply = Reply.Answered(r.status, String(r.body, UTF_8))

object Neighbour:
  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
