package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.money.AmountNotWhole
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.offers.Refusal
import io.eitheror.casino.rewards.reward.Refused

import java.nio.charset.StandardCharsets.UTF_8

/**
 * How the routes answer (contracts/routes.md): a body, or a refusal as `{"error": <code>, …named fields}` with the
 * status its endpoint's table gives, and the original's own shapes where its frontend reads them
 * (`{status: "ineligible", reason}`, `{valid: false, error}`). Every amount in a body is a whole number of minor units
 * (FR-032). The 401s and 403s of a caller the ACL refuses are the ACL's, written before a handler runs.
 */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode): Reply = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Reply                = json(200, body)
  def created(body: JsonNode): Reply           = json(201, body)
  val okTrue: Reply                            = ok(Json.obj("ok" -> true))

  /** `{error, …fields}`; a field of `None` is left out. */
  def refusal(status: Int, error: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((("error" -> error) +: fields)*))

  /** An offer's create or edit refused (`OfferRules`), with the fields it names. */
  def refused(r: Refusal): Reply = refusal(r.status, r.error, r.detail*)

  /** A reward's refusal, with its message, at the status the endpoint's table gives its code. */
  def refused(r: Refused, table: Table): Reply = refusal(table.status(r.code), r.code, "message" -> r.message)

  /** The evaluator's decline, as the original answered it (contracts/routes.md, 409). */
  def ineligible(reason: String): Reply = json(409, Json.obj("status" -> "ineligible", "reason" -> reason))

  /** A code preview that matches nothing: 200, as the original's (contracts/routes.md, `POST …/codes/preview`). */
  val codeNotFound: Reply = ok(Json.obj("valid" -> false, "error" -> "code_not_found"))

  val badBody: Reply     = refusal(400, "invalid_body", "message" -> "The body is not a JSON object")
  val wrongBrand: Reply  = refusal(404, "wrong_brand", "message" -> "This service serves another brand")
  val notYours: Reply    = refusal(403, "player_id_mismatch", "message" -> "The player id is not the caller's")
  val forbidden: Reply   = refusal(403, "forbidden", "message" -> "The caller's role may not do this")
  val unavailable: Reply = refusal(503, "service_unavailable", "message" -> "A neighbour did not answer in time; try again")

  /** The roles that may act on offers and rewards as an operator (FR-031). */
  val AdminRoles: Set[String] = Set("brand_admin", "resource_admin", "platform_admin")

  // ── amounts (FR-032) ──────────────────────────────────────────────────────

  /** The refusal of an amount that is not a whole, non-negative number of minor units, naming the field. */
  val NotWhole = "amount_not_minor_units"

  def notWhole(field: String, raw: String): Reply =
    refusal(400, NotWhole, "field" -> field, "message" -> AmountNotWhole(raw).message)

  /**
   * The field as minor units: absent or `null` is none; a JSON integer of zero or more is the amount; a decimal, a
   * string, a negative number or one beyond a `Long` is refused naming the field. Strings are refused, as the
   * contract's own reader refuses them, so `"10.00"` never passes for a thousand minor units.
   */
  def amount(body: JsonNode, field: String): Either[Reply, Option[Long]] =
    Option(body.get(field)).filterNot(_.isNull) match
      case None                                                                   => Right(None)
      case Some(n) if n.isIntegralNumber && n.canConvertToLong && n.asLong >= 0 => Right(Some(n.asLong))
      case Some(n)                                                                => Left(notWhole(field, n.asText))

  /** Every named amount of the body, the first that is not whole refused. */
  def amounts(body: JsonNode, fields: String*): Either[Reply, Map[String, Long]] =
    fields.foldLeft[Either[Reply, Map[String, Long]]](Right(Map.empty)) { (acc, field) =>
      acc.flatMap(m => amount(body, field).map(_.fold(m)(v => m + (field -> v))))
    }

  // ── the status tables of contracts/routes.md, one per endpoint ────────────

  /** An endpoint's refusals by code; a code the table does not name takes the endpoint's default. */
  final case class Table(statuses: Map[String, Int], default: Int):
    def status(code: String): Int = statuses.getOrElse(code, Common.getOrElse(code, default))
    def refuse(code: String, fields: (String, Any)*): Reply = refusal(status(code), code, fields*)

  /** What every endpoint answers alike. */
  private val Common: Map[String, Int] = Map(
    "invalid_body" -> 400, NotWhole -> 400, "wrong_brand" -> 404, "player_id_mismatch" -> 403, "forbidden" -> 403,
    "service_unavailable" -> 503, "INVALID_TRANSITION" -> 409
  )

  /** `RewardsEndpoint`: a decline, or the wallet's code (`ALREADY_CLAIMED` on a repeat), is 409. */
  val Rewards: Table = Table(Map(
    "offerId required" -> 400, "code is required" -> 400, "grantRef_required" -> 400,
    "offer_not_visible" -> 404, "grant_not_found" -> 404, "no_eligible_grant" -> 404,
    "invalid_code" -> 422,
    "no_active_bonus" -> 409, "switch_failed" -> 409, "ALREADY_CLAIMED" -> 409,
    "cancel_failed" -> 503
  ), default = 409)

  /** `OffersEndpoint`: create and edit refusals carry their own status (`OfferRules`); the provider's checks are 400. */
  val Offers: Table = Table(Map(
    "offer_not_found" -> 404, "role_already_assigned" -> 409, "duplicate_code_in_request" -> 409, "code_already_in_use" -> 409,
    "spin_value_not_offered" -> 400, "game_not_eligible" -> 400,
    "provider_unavailable" -> 502
  ), default = 400)

  /** `RewardAdminEndpoint`: the award's currency and provider checks are 422; FR-002's free-rounds codes 400. */
  val Grants: Table = Table(Map(
    "pageSize" -> 400, "INVALID_MODE" -> 400, "bonusAmount and wager are required" -> 400,
    "CLAIM_NOT_FOUND" -> 404, "offer_not_found" -> 404,
    "NO_ACTIVE_BONUS" -> 409, "MISSING_BASELINE" -> 409, "FREE_SPINS_FINISHED" -> 409,
    "currency_not_configured" -> 422, "player_currency_unresolved" -> 422, "game_not_eligible" -> 422,
    "spin_value_not_offered" -> 422,
    "provider_rejected" -> 502,
    "PROVIDER_UNAVAILABLE" -> 503
  ), default = 400)

  /** `CampaignEndpoint`: every refusal of the target and the award is 422, but the two below. */
  val Campaigns: Table = Table(Map(
    "ELIGIBILITY_CHECK_UNAVAILABLE" -> 503, "provider_rejected" -> 502
  ), default = 422)
