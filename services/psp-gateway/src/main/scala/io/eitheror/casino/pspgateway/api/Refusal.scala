package io.eitheror.casino.pspgateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, Respond}
import io.eitheror.casino.pspgateway.{Json, Settings}
import io.eitheror.casino.pspgateway.providers.Reply

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * What every endpoint is given: the settings, how an operator's token is verified (ankka-auth-oidc in
 * a deployment, a stand-in in the suites), the clock, and the service's clients.
 */
final class Ctx(val settings: Settings, val authenticate: () => Acl, val now: () => Instant, val clients: EndpointClients):
  val components = clients.componentClient
  val views      = clients.viewClient

/** The refusals of contracts/merchant.md and contracts/payouts.md: `{"error", "message"?, "provider"?}`. */
object Refusal:
  def json(status: Int, body: JsonNode): Respond[Bytes] = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status)
  def ok(body: JsonNode): Respond[Bytes]                = json(200, body)

  def apply(status: Int, error: String, message: Option[String] = None, provider: Option[String] = None): Respond[Bytes] =
    json(status, Json.obj("error" -> error, "message" -> message, "provider" -> provider))

  val notYet: Respond[Bytes] = apply(501, "not_implemented")

  /** A provider's webhook answer, as each provider is answered (research R9). */
  def reply(r: Reply): Respond[Bytes] = Respond(Bytes(r.contentType, r.body.getBytes(UTF_8)), r.status)

  /** An entity's refusal as a route answers it. */
  def fromError(e: CommandError): Respond[Bytes] = e.code match
    case ErrorCode.NotFound   => apply(404, "not_found")
    case ErrorCode.Conflict   => apply(409, "conflict", Option(e.message))
    case ErrorCode.BadRequest => apply(400, "bad_request", Option(e.message))
    case _                    => apply(503, "unavailable")

  def guarded(f: => Respond[Bytes]): Respond[Bytes] =
    try f
    catch case e: CommandError => fromError(e)
