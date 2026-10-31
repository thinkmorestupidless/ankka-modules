package io.eitheror.casino.payouts.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceResponse}
import io.eitheror.casino.payouts.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** A neighbour's answer: its status and body, or that it could not be reached at all. */
enum Reply:
  case Answered(status: Int, body: Option[JsonNode], text: String)
  case Unreachable(reason: String)

/**
 * Calls to another service as this service, by identity (no secret): the wallet of this project by name,
 * or `payments`/`psp-gateway` under the grant `payments` makes (psp-gateway's `http.Neighbour`). Every
 * call answers a `Reply` rather than throwing, so a refusal is told apart from a neighbour that is down.
 * No body or path is logged: both may name the player.
 */
final class Neighbour(services: ServiceClients, name: String, project: Option[String] = None):
  def get(path: String): Reply = call("GET", path, None)

  def post(path: String, body: JsonNode): Reply = call("POST", path, Some(Json.render(body)))

  private def call(method: String, path: String, body: Option[String]): Reply =
    Try(project.fold(services(name))(services(_, name))
      .request(method, path, body.map(_.getBytes(UTF_8)), body.map(_ => "application/json"), Nil))
      .fold(e => Reply.Unreachable(s"$name unreachable: ${e.getClass.getSimpleName}"), answered)

  private def answered(r: ServiceResponse): Reply =
    val text = String(r.body, UTF_8)
    Reply.Answered(r.status, Json.parse(text).filter(_ => text.trim.nonEmpty), text)

object Neighbour:
  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
