package io.eitheror.casino.backoffice

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.backoffice.session.Session

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** How the routes answer (contracts/routes.md): JSON bodies, refusals as `{"error", "message"}`. */
object Answers:
  type Reply = Respond[Bytes]

  val mapper: ObjectMapper = ObjectMapper()

  def obj(): ObjectNode = mapper.createObjectNode()

  def json(status: Int, body: JsonNode, headers: Vector[(String, String)] = Vector.empty): Reply =
    Respond(Bytes("application/json", mapper.writeValueAsBytes(body)), status, headers)

  def refusal(status: Int, error: String, message: String, headers: Vector[(String, String)] = Vector.empty): Reply =
    json(status, obj().put("error", error).put("message", message), headers)

  val noContent: Reply = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def parse(text: String): Option[JsonNode] = Try(mapper.readTree(text.getBytes(UTF_8))).toOption.filter(n => n != null && n.isObject)

  /** A realm subject a path may name: one segment, and `operator/<it>` a data subject. */
  def validSubject(subject: String): Boolean =
    subject.nonEmpty && !subject.contains('/') && DataSubject.from(s"${Session.SubjectPrefix}$subject").isRight
