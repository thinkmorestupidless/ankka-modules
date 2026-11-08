package io.eitheror.casino.engagement.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.clients.Reply as Neighbour

import java.nio.charset.StandardCharsets.UTF_8

/**
 * How the routes answer (contracts/routes.md): a body, or a refusal as `{"error": <code>, …named fields}` with the
 * status the route's table gives. The webhook's own refusals keep the original's literal messages, which Customer.io's
 * campaign logs show; a neighbour's answer to a relayed action is passed through as it came. The 401s and 403s of a
 * caller the ACL refuses are the ACL's, written before a handler runs.
 */
object Answers:
  type Answer = Respond[Bytes]

  def json(status: Int, body: JsonNode): Answer = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Answer                = json(200, body)

  /** `{error, …fields}`; a field of `None` is left out. */
  def refusal(status: Int, error: String, fields: (String, Any)*): Answer =
    json(status, Json.obj((("error" -> error) +: fields)*))

  /** 400 `missing_fields` naming them, in the order the route reads them. */
  def missingFields(fields: Seq[String]): Answer = refusal(400, "missing_fields", "fields" -> fields.toVector)

  val badBody: Answer    = refusal(400, "invalid_body", "message" -> "The body is not a JSON object")
  val wrongBrand: Answer = refusal(404, "wrong_brand", "message" -> "This service serves another brand")

  /** A neighbour that did not answer (contracts/routes.md): 503, naming it. */
  def unavailable(service: String): Answer =
    refusal(503, "service_unavailable", "service" -> service, "message" -> s"$service did not answer; try again")

  /** The webhook's literal refusals of a missing field, as the original's (`CustomerIOWebhookRoutes.scala:1158-1218`). */
  object Webhook:
    val grantBonus: Answer    = refusal(422, "player_id and offer_id required")
    val sendMessage: Answer   = refusal(422, "player_id required")
    val targetOffer: Answer   = refusal(422, "player_id and role required")
    val deliveryId: Answer    = refusal(422, "delivery_id_required")
    val unknownAction: Answer = refusal(422, "unknown_action")

  /** A neighbour's answer as it came: its status and its body; a body that is not JSON is passed as text. */
  def relayed(reply: Neighbour): Answer = reply match
    case Neighbour.Answered(status, text) =>
      val kind = if Json.parse(text).isDefined then "application/json" else "text/plain"
      Respond(Bytes(kind, text.getBytes(UTF_8)), status, Vector.empty)
    case Neighbour.Unreachable(who) => unavailable(who)
