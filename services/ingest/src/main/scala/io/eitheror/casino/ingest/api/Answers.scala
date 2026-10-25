package io.eitheror.casino.ingest.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.requests.Answer

import java.nio.charset.StandardCharsets.UTF_8

/**
 * How the routes answer (contracts/routes.md): a refusal is `{"error", "field"?, "transactionId"?,
 * "retryAfterSeconds"?, "message"?}`, with `Retry-After` where the route names it; `merchant`'s answer is
 * passed through with its status and body as it sent them (FR-011).
 */
object Answers:
  type Reply = Respond[Bytes]

  /** The seconds a caller told to try again waits (FR-013). */
  val TryAgainSeconds = 5L

  def json(status: Int, body: JsonNode, headers: Vector[(String, String)] = Vector.empty): Reply =
    Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, headers)

  def ok(body: JsonNode): Reply = json(200, body)
  val noContent: Reply = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def refusal(status: Int, error: String, field: Option[String] = None, transactionId: Option[String] = None,
      retryAfterSeconds: Option[Long] = None, message: Option[String] = None): Reply =
    json(status, Json.obj("error" -> error, "field" -> field, "transactionId" -> transactionId,
      "retryAfterSeconds" -> retryAfterSeconds, "message" -> message),
      retryAfterSeconds.toVector.map(s => "Retry-After" -> s.toString))

  def passThrough(a: Answer): Reply = Respond(Bytes("application/json", a.body.getBytes(UTF_8)), a.status)

  val bodyTooLarge: Reply    = refusal(413, "body_too_large")
  val notJson: Reply         = refusal(400, "not_json")
  val unauthenticated: Reply = refusal(401, "UNAUTHENTICATED")
  val invalidKey: Reply      = refusal(401, "INVALID_MERCHANT_KEY")
  val sessionNotFound: Reply = refusal(404, "SESSION_NOT_FOUND")
  val paymentNotFound: Reply = refusal(404, "PAYMENT_NOT_FOUND")
  val tryAgain: Reply        = refusal(503, "TRY_AGAIN", retryAfterSeconds = Some(TryAgainSeconds))

  def tooMany(waitSeconds: Long): Reply   = refusal(429, "TOO_MANY_REQUESTS", retryAfterSeconds = Some(waitSeconds))
  def invalid(field: String): Reply       = refusal(400, "INVALID_REQUEST", field = Some(field))
  def reused(requestId: String): Reply    = refusal(409, "REQUEST_ID_REUSED", transactionId = Some(requestId))
