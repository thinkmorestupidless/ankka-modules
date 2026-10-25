package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, Respond}
import io.eitheror.casino.players.{Json, Neighbours, PlayersRuntime, Settings}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * How the routes answer (contracts/routes.md, contracts/wire.md): a body as the original wrote it, a
 * refusal as `{"error", "message", ...}` with the original's texts. The 401s of an unauthenticated
 * caller are the ACL's (spec 001 FR-020), written before a handler runs.
 */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode, headers: Vector[(String, String)] = Vector.empty): Reply =
    Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, headers)

  def ok(body: JsonNode): Reply = json(200, body)

  /** A refusal: the code, the message, and the fields the route names (`reason`, `until`, `retry_after_seconds`, …). */
  def refusal(status: Int, error: String, message: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((Seq[(String, Any)]("error" -> error, "message" -> message) ++ fields)*))

  /** A route declared and not yet filled by its story: `501`, naming it. */
  def notYet(route: String): Reply = refusal(501, "not_implemented", s"$route is not served yet")

/** What every endpoint is built with: the service's clients, the authenticator, and the settings. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings          = PlayersRuntime.settings
  def slug: String                = settings.brand
  lazy val neighbours: Neighbours = Neighbours(clients.services, clients.secrets)
