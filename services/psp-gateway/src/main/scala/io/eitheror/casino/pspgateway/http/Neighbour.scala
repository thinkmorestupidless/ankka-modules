package io.eitheror.casino.pspgateway.http

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceResponse}
import io.eitheror.casino.pspgateway.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** A neighbour's answer: its status and body, or that it could not be reached at all. */
enum Reply:
  case Answered(status: Int, body: Option[JsonNode], text: String)
  case Unreachable(reason: String)

/**
 * Calls to another service as this service (merchant's `gateway.Http`): one of this project by name,
 * or one of another project (a brand's `payouts`, under the grant it made) by project and name. Every
 * call answers a `Reply` rather than throwing, so a refusal is told apart from a neighbour that is down.
 */
final class Neighbour(services: ServiceClients, name: String, project: Option[String] = None):
  def get(path: String): Reply = call("GET", path, None, Nil)

  def post(path: String, body: String, headers: Seq[(String, String)] = Nil): Reply =
    call("POST", path, Some(body), headers)

  private def call(method: String, path: String, body: Option[String], headers: Seq[(String, String)]): Reply =
    Try(project.fold(services(name))(services(_, name))
      .request(method, path, body.map(_.getBytes(UTF_8)), body.map(_ => "application/json"), headers.toList))
      .fold(e => Reply.Unreachable(s"$name unreachable: ${e.getClass.getSimpleName}"), answered)

  private def answered(r: ServiceResponse): Reply =
    val text = String(r.body, UTF_8)
    Reply.Answered(r.status, Json.parse(text).filter(_ => text.trim.nonEmpty), text)

object Neighbour:
  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
