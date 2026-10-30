package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, Respond}
import io.eitheror.casino.kyc.{Json, KycRuntime, Settings}
import io.eitheror.casino.kyc.documents.Store
import io.eitheror.casino.kyc.policy.{Policy, PolicyEntity}
import io.eitheror.casino.kyc.record.{KycRecord, KycState, Refusal}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * How the routes answer (contracts/routes.md): a body, or a refusal as `{"error", "message", ...named
 * fields}`. The 401s and 403s of a caller the ACL refuses are the ACL's, written before a handler runs.
 */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode): Reply = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Reply             = json(200, body)

  def refusal(status: Int, error: String, message: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((Seq[(String, Any)]("error" -> error, "message" -> message) ++ fields)*))

  val badBody: Reply            = refusal(400, "invalid_body", "The body is not a JSON object")
  val storageUnavailable: Reply = refusal(503, "storage_unavailable", "The document store cannot be reached; try again")

  /** The status and message of each refusal code in contracts/routes.md. */
  private val Statuses: Map[String, (Int, String)] = Map(
    "player_id_mismatch"        -> (403, "The player id is not the caller's"),
    "forbidden"                 -> (403, "The caller's role may not do this"),
    "invalid_category"          -> (400, "Unknown document category"),
    "file_rejected"             -> (400, "The file is not accepted"),
    "document_locked"           -> (409, "A document of this category is awaiting review or approved"),
    "account_closed"            -> (409, "The account is closed"),
    "document_not_found"        -> (404, "No such document"),
    "upload_not_found"          -> (404, "Nothing was uploaded for this document"),
    "upload_expired"            -> (410, "The upload link has expired; ask for a new one"),
    "document_erased"           -> (410, "The document was erased"),
    "reason_required"           -> (400, "A reason is required"),
    "invalid_rejection_reason"  -> (400, "A rejection needs one of the reason codes"),
    "document_already_reviewed" -> (409, "The document was already reviewed"),
    "invalid_mode"              -> (400, "The mode is automatic or manual"),
    "invalid_status"            -> (400, "Not a status this route sets"),
    "profile_incomplete"        -> (409, "The player's profile is incomplete"),
    "flag_not_found"            -> (404, "No open flag of that reason"),
    "note_required"             -> (400, "A note is required"),
    "invalid_filter"            -> (400, "Unknown filter"),
    "player_not_found"          -> (404, "No such player"),
    "invalid_policy"            -> (400, "A policy field is not valid"),
    "currency_required"         -> (400, "A threshold needs the KYC currency"),
    "profile_required"          -> (400, "A profile is required"),
    "unknown_profile"           -> (400, "Unknown licence profile")
  )

  /** A refusal of the record, with the fields it names: the missing profile fields, or the rule a file broke. */
  def refused(r: Refusal): Reply =
    val (status, message) = Statuses.getOrElse(r.code, (400, r.code))
    val named: Seq[(String, Any)] = r.code match
      case "profile_incomplete" => Seq("missingProfileFields" -> Json.arr(r.fields*))
      case "file_rejected"      => r.fields.headOption.map("rule" -> _).toSeq
      case "invalid_category"   => r.fields.headOption.map("category" -> _).toSeq
      case _                    => Seq.empty
    refusal(status, r.code, message, named*)

  def refused(code: String, fields: (String, Any)*): Reply =
    val (status, message) = Statuses.getOrElse(code, (400, code))
    refusal(status, code, message, fields*)

/** What every endpoint is built with: the service's clients, the authenticator, the settings, the clock and the store. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings = KycRuntime.settings
  def slug: String       = settings.brand
  def now(): Instant     = KycRuntime.now()
  def store: Store       = KycRuntime.store()

  def record(playerId: String) = clients.componentClient.forEventSourcedEntity(EntityId(playerId))
  def state(playerId: String): KycState = record(playerId).call(KycRecord.get).invoke()
  def policyEntity               = clients.componentClient.forEventSourcedEntity(EntityId(slug))
  def policy: Policy             = policyEntity.call(PolicyEntity.get).invoke()
