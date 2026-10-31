package io.eitheror.casino.payouts.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.request.{Field, Refusal}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * How the routes answer (contracts/routes.md): a body, or a refusal as `{"error", "message", ...named
 * fields}`. The 401s and 403s of a caller the ACL refuses are the ACL's, written before a handler runs.
 */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode): Reply = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Reply                = json(200, body)
  def created(body: JsonNode): Reply           = json(201, body)
  val noContent: Reply                         = Respond(Bytes("application/json", Array.emptyByteArray), 204, Vector.empty)

  def refusal(status: Int, error: String, message: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((Seq[(String, Any)]("error" -> error, "message" -> message) ++ fields)*))

  /** A refusal of the request path, with the values it names (the limit's figures, the field, the coins). */
  def refused(r: Refusal): Reply =
    refusal(r.status, r.code, r.message, r.fields.map((name, value) => name -> field(value))*)

  private def field(f: Field): Any = f match
    case Field.Num(v)    => v
    case Field.Text(v)   => v
    case Field.Texts(vs) => Json.arr(vs*)

  val badBody: Reply      = refusal(400, "invalid_body", "The body is not a JSON object")
  val wrongBrand: Reply   = refusal(404, "wrong_brand", "This service serves another brand")
  val notYours: Reply     = refusal(403, "player_id_mismatch", "The player id is not the caller's")
  val forbidden: Reply    = refusal(403, "forbidden", "The caller's role may not do this")
  val unavailable: Reply  = refusal(503, "SERVICE_UNAVAILABLE", "the wallet did not answer in time; try again")

  /** The roles that may act on withdrawals as an operator (R6). */
  val FinanceRoles: Set[String] = Set("brand_admin", "brand_finance", "resource_admin", "resource_finance", "platform_admin")
