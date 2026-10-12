package io.eitheror.casino.gameprovider.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceResponse}
import io.eitheror.casino.gameprovider.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** A neighbour's answer: its status and body, or that it could not be reached at all. */
enum Reply:
  case Answered(status: Int, body: Option[JsonNode], text: String)
  case Unreachable(reason: String)

  def ok: Boolean = this match
    case Answered(s, _, _) => s / 100 == 2
    case _                 => false

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
