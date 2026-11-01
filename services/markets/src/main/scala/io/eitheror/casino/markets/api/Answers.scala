package io.eitheror.casino.markets.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.predictions.markets.components.Refused
import io.eitheror.casino.markets.Json

import java.nio.charset.StandardCharsets.UTF_8

/** How the routes answer (contracts/routes.md): a body, or a refusal as `{"error", "message", ...named fields}`. */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode): Reply = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Reply             = json(200, body)

  /** A value in the library's own JSON, as its serializer writes it. */
  def ok[A](value: A)(using s: Serializer[A]): Reply = Respond(Bytes("application/json", s.toBytes(value)), 200, Vector.empty)

  def refusal(status: Int, error: String, message: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((Seq[(String, Any)]("error" -> error, "message" -> message) ++ fields)*))

  val badBody: Reply  = refusal(400, "invalid_body", "The body is not a JSON object")
  val notFound: Reply = refusal(404, "market_not_found", "No such market")

  /** A field of the body that is missing or cannot be read, named as the library names its fields. */
  def field(name: String): Reply = refusal(400, "field", s"$name is missing or cannot be read", "field" -> name)

  /**
   * A refusal of the library's, by its reason and the names it gives (`state`, `field`, `outcome`, ...):
   * 403 for the resolution port's, 409 for state and order, 400 for the rest. Any other error is not a
   * refusal and is thrown on, so ankka answers it as the failure it is.
   */
  def refused(e: CommandError): Reply = Refused.unapply(e) match
    case None => throw e
    case Some(r) =>
      val status = e.code match
        case ErrorCode.Forbidden => 403
        case ErrorCode.Conflict  => 409
        case _                   => 400
      refusal(status, r.reason, (r.reason +: r.names.toSeq.sorted.map((k, v) => s"$k $v")).mkString(": "), r.names.toSeq.sorted*)

  /** The entity's answer, or its refusal. */
  def answer[A](call: => A)(reply: A => Reply): Reply =
    try reply(call)
    catch case e: CommandError => refused(e)
