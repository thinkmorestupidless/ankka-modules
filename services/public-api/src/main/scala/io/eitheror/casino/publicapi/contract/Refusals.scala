package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * A refusal of the public contract: one of the served `Error` enum's codes with the original's status
 * (contracts/amendments.md, the error table), answered as `{"error"}`, with `"message"` only where the original sets
 * one (original `server.ts:226-235`: a message equal to the code is left out).
 */
final case class Refusal(status: Int, code: String, message: Option[String] = None, headers: Vector[(String, String)] = Vector.empty):
  def respond: Respond[Bytes] = Refusals.json(status, Refusals.body(code, message.filterNot(_ == code)), headers)

object Refusals:
  private val mapper = new ObjectMapper()

  /** Every code the served contract's `Error` enum holds, in its order. */
  val Codes: Vector[String] = Vector("invalid_request", "limit_exceeded", "key_missing", "key_invalid", "key_expired", "key_disabled",
    "not_found", "snapshot_expired", "quota_exceeded", "unavailable")

  def invalidRequest(message: String): Refusal = Refusal(400, "invalid_request", Some(message))
  def limitExceeded(max: Int): Refusal         = Refusal(400, "limit_exceeded", Some(s"limit may not exceed $max"))
  val keyMissing: Refusal                      = Refusal(401, "key_missing")
  val keyInvalid: Refusal                      = Refusal(401, "key_invalid")
  val keyExpired: Refusal                      = Refusal(401, "key_expired")
  val keyDisabled: Refusal                     = Refusal(403, "key_disabled")
  val notFound: Refusal                        = Refusal(404, "not_found")
  val onlyGet: Refusal                         = Refusal(405, "invalid_request", Some("Only GET is supported"))
  val snapshotExpired: Refusal =
    Refusal(410, "snapshot_expired", Some("The named snapshot has been retired; start again from page 1 without snapshot"))
  def quotaExceeded(retryAfterSeconds: Long): Refusal =
    Refusal(429, "quota_exceeded", headers = Vector("Retry-After" -> math.max(1L, retryAfterSeconds).toString))
  val unavailable: Refusal = Refusal(503, "unavailable")

  /** A path id that is not a valid identifier (original `router.ts:146-149`). */
  def notAnIdentifier(name: String): Refusal = invalidRequest(s"$name is not a valid identifier")

  def body(code: String, message: Option[String]): String =
    val node = mapper.createObjectNode().put("error", code)
    message.foreach(node.put("message", _))
    mapper.writeValueAsString(node)

  def json(status: Int, text: String, headers: Vector[(String, String)] = Vector.empty): Respond[Bytes] =
    Respond(Bytes("application/json", text.getBytes(UTF_8)), status, headers)
