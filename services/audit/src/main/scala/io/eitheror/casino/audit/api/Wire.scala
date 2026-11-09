package io.eitheror.casino.audit.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.audit.AuditCodecs.given
import io.eitheror.casino.audit.stored.Stored

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * How the routes answer (contracts/routes.md): a body, or a refusal as `{"code", "message"}` with a 4xx.
 * A refusal the domain gives as `code: message` (`Query.parse`, `Retention.raise`) is split there.
 */
object Wire:
  type Answer = Respond[Bytes]

  def json(status: Int, body: String): Answer = Respond(Bytes("application/json", body.getBytes(UTF_8)), status)

  private given stringCodec: JsonValueCodec[String] = JsonCodecMaker.make[String]

  def text(v: String): String = writeToString(v)

  def obj(fields: (String, Option[String])*): String =
    fields.collect { case (k, Some(v)) => s"${text(k)}:$v" }.mkString("{", ",", "}")

  def refuse(status: Int, code: String, message: String): Answer =
    json(status, obj("code" -> Some(text(code)), "message" -> Some(text(message))))

  /** `code: message` as a refusal of `status`. */
  def refused(status: Int, problem: String): Answer =
    problem.split(": ", 2) match
      case Array(code, message) => refuse(status, code, message)
      case _                    => refuse(status, problem, problem)

  def page(rows: Vector[Stored], next: Option[String], retainedFrom: Option[Instant]): Answer =
    json(200, obj(
      "records"      -> Some(rows.map(writeToString(_)).mkString("[", ",", "]")),
      "next"         -> Some(next.fold("null")(text)),
      "retainedFrom" -> retainedFrom.map(i => text(i.toString))
    ))
