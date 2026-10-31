package io.eitheror.casino.exchange.ports

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceResponse}
import io.eitheror.casino.exchange.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

// Copied from services/game-provider/…/clients/Http.scala (research R3): the contract holds no
// clients today, and a third copy is the time to move them there.

/** A neighbour's answer: its status and body, or that it could not be reached at all. */
enum Reply:
  case Answered(status: Int, body: Option[JsonNode], text: String)
  case Unreachable(reason: String)

/**
 * Calls to another service of the project as this service, with no token (spec 001 FR-020, service
 * class), through ankka's service clients. Every call answers a `Reply` rather than throwing, so a
 * refusal's code is read and a neighbour that is down is told apart from one that said no.
 */
final class Http(services: ServiceClients, name: String):
  def get(path: String): Reply = call("GET", path, None)

  def post(path: String, body: JsonNode): Reply = call("POST", path, Some(body))

  private def call(method: String, path: String, body: Option[JsonNode]): Reply =
    Try(services(name).request(method, path, body.map(b => Json.render(b).getBytes(UTF_8)), body.map(_ => "application/json"), Nil))
      .fold(e => Reply.Unreachable(s"$name unreachable: ${e.getClass.getSimpleName}: ${e.getMessage}"), answered)

  private def answered(r: ServiceResponse): Reply =
    val text = String(r.body, UTF_8)
    Reply.Answered(r.status, Json.parse(text).filter(_ => text.trim.nonEmpty), text)

object Http:
  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
