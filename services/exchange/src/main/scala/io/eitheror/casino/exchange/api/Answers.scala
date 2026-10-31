package io.eitheror.casino.exchange.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.predictions.exchange.Refusal
import com.thinkmorestupidless.predictions.exchange.components.Refused
import io.eitheror.casino.exchange.Json

import java.nio.charset.StandardCharsets.UTF_8

/** How the routes answer (contracts/routes.md): a body, or a refusal as `{"error", "message", ...named fields}`. */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode): Reply = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Reply             = json(200, body)

  def refusal(status: Int, error: String, message: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((Seq[(String, Any)]("error" -> error, "message" -> message) ++ fields)*))

  val badBody: Reply = refusal(400, "invalid_body", "The body is not a JSON object")

  def field(name: String): Reply = refusal(400, "field", s"$name is missing or cannot be read", "field" -> name)

  /**
   * A refusal of the library's or of a port, by its reason: a gate's (`account: …`, `play gate: …`,
   * `bet gate: …`) is 403, the wallet's `insufficient` is `insufficient funds` 409, state, order and
   * id 409, the rest 400. The names are its fields.
   */
  def refused(r: Refusal): Reply =
    val names  = r.names.toSeq.sorted
    val reason = if r.reason == "insufficient" then "insufficient funds" else r.reason
    val status =
      if Answers.gate(reason) then 403
      else if Set("insufficient funds", "state", "out of order", "id", "key")(reason) then 409
      else 400
    refusal(status, reason, (reason +: names.map((k, v) => s"$k $v")).mkString(": "), names*)

  private def gate(reason: String) = Seq("account:", "play gate:", "bet gate:").exists(reason.startsWith)

  /** An entity's answer, or its refusal; any other error is thrown on, as the failure it is. */
  def answer[A](call: => A)(reply: A => Reply): Reply =
    try reply(call)
    catch case e: CommandError => Refused.unapply(e).fold(throw e)(refused)

