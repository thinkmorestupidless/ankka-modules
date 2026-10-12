package io.eitheror.casino.simulator.st8

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Answers and request bodies as the stand-in's Go handlers write and read them
 * (contracts/st8-wire.md, "Conventions"): `writeJSON` is `json.NewEncoder(w).Encode`, `http.Error`
 * is plain text with a newline and `nosniff`, and a body is decoded into a struct of declared fields,
 * refusing a field of the wrong type and ignoring fields it does not declare.
 */
object Http:

  type Answer = Respond[Bytes]

  private val mapper = ObjectMapper()

  def json(status: Int, value: GoJson.Value): Answer =
    Respond(Bytes("application/json", GoJson.encode(value).getBytes("UTF-8")), status)

  /** `http.Error(w, message, status)`. */
  def error(status: Int, message: String): Answer =
    Respond(Bytes("text/plain; charset=utf-8", (message + "\n").getBytes("UTF-8")), status, Vector("X-Content-Type-Options" -> "nosniff"))

  def html(markup: String): Answer = Respond(Bytes("text/html; charset=utf-8", markup.getBytes("UTF-8")), 200)

  /** A brand's answer passed through verbatim, its content type forced to JSON as the stand-in does. */
  def proxied(status: Int, body: Array[Byte]): Answer = Respond(Bytes("application/json", body), status)

  /** Raw bytes with no trailing newline (the stand-in's `/health`). */
  def raw(status: Int, contentType: String, text: String): Answer = Respond(Bytes(contentType, text.getBytes("UTF-8")), status)

  val badRequest: Answer       = error(400, "bad request")
  val methodNotAllowed: Answer = error(405, "method not allowed")

  /**
   * A body decoded as Go decodes into a struct: an object (or `null`), whose declared string fields
   * are strings or `null`, declared string-array fields arrays of strings, declared booleans
   * booleans. Anything else is `bad request`.
   */
  final case class Body(node: ObjectNode):
    def string(field: String): String = Option(node.get(field)).filter(_.isTextual).map(_.asText()).getOrElse("")
    def strings(field: String): Vector[String] =
      Option(node.get(field)).filter(_.isArray).map(_.elements().asScala.toVector.map(_.asText())).getOrElse(Vector.empty)
    /** `flexInt`: a JSON number or a string of digits. */
    def flexInt(field: String): Int =
      Option(node.get(field)).filter(!_.isNull).map(n => if n.isNumber then n.asInt() else n.asText().toInt).getOrElse(0)
    def raw: JsonNode = node

  def read(text: String, strings: Set[String] = Set.empty, stringArrays: Set[String] = Set.empty, booleans: Set[String] = Set.empty, flexInts: Set[String] = Set.empty): Option[Body] =
    Try(mapper.readTree(text)).toOption.flatMap {
      case o: ObjectNode =>
        def ok(field: String, check: JsonNode => Boolean) = Option(o.get(field)).forall(n => n.isNull || check(n))
        val valid =
          strings.forall(ok(_, _.isTextual)) &&
            stringArrays.forall(ok(_, n => n.isArray && n.elements().asScala.forall(_.isTextual))) &&
            booleans.forall(ok(_, _.isBoolean)) &&
            flexInts.forall(ok(_, n => n.isIntegralNumber || (n.isTextual && n.asText().matches("-?[0-9]+"))))
        Option.when(valid)(Body(o))
      case n if n != null && n.isNull => Some(Body(mapper.createObjectNode()))
      case _                          => None
    }

  def resource(path: String): String =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(throw IllegalStateException(s"$path is not on the classpath"))
    try String(stream.readAllBytes(), "UTF-8") finally stream.close()
