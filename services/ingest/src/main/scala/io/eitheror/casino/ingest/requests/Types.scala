package io.eitheror.casino.ingest.requests

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.ingest.Json

/** The record's word for how a request ended (data-model.md). */
object Outcome:
  val Served          = "served"
  val Repeated        = "repeated"
  val Refused         = "refused"
  val Unanswered      = "unanswered"
  val LostAnswer      = "lost_answer"
  val KeyMissing      = "key_missing"
  val KeyUnknown      = "key_unknown"
  val KeyDisabled     = "key_disabled"
  val Rate            = "rate"
  val BodyTooLarge    = "body_too_large"
  val NotJson         = "not_json"
  val InvalidField    = "invalid_field"
  val RequestIdReused = "request_id_reused"
  val NotFound        = "not_found"

  /** The refusals that are audit records (FR-019): the key's and the rate's. */
  val Audited: Set[String] = Set(KeyMissing, KeyUnknown, KeyDisabled, Rate)

/** The public route a request came to. */
object Route:
  val Create  = "create"
  val Submit  = "submit"
  val Abandon = "abandon"
  val Read    = "read"

/**
 * What `merchant` answered: its status and its body as it sent it, returned to the caller unchanged
 * (FR-011) and, for a create, kept as the request id's answer (FR-010).
 */
final case class Answer(status: Int, body: String):
  def ok: Boolean                 = status / 100 == 2
  def json: Option[JsonNode]      = Json.parse(body)
  def text(field: String): Option[String] = json.flatMap(Json.text(_)(field))
