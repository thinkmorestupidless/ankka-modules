package io.eitheror.casino.support.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceUnanswered}
import io.eitheror.casino.support.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

/** What a neighbour's call came to: its answer, whatever the status, or that it did not answer. */
enum Called:
  case Answered(status: Int, text: String)
  /** No answer within the timeout, twice. */
  case TimedOut
  /** Not reachable: the name does not resolve, or the identity does not match. */
  case Unavailable

  def ok: Boolean = this match
    case Answered(s, _) => s >= 200 && s < 300
    case _              => false

  def json: Option[JsonNode] = this match
    case Answered(_, t) => Json.parse(t)
    case _              => None

  /** A refusal's code, as the neighbours answer it (`error`, or the original's `code`). */
  def refusal: Option[String] = this match
    case Answered(s, t) if s >= 400 =>
      Some(Json.parse(t).flatMap(n => Option(n.get("error")).orElse(Option(n.get("code")))).filter(_.isTextual).map(_.asText).getOrElse(s"HTTP_$s"))
    case _ => None

/**
 * A call to another service of the brand's project, as `support-agent`, by name and identity (029 FR-010). Each
 * attempt waits `ankka.service-client.timeout` (5 s, application.conf); a timeout is tried once more, a refusal
 * never (FR-012, R15). A write is safe to try twice because it carries its key. No path, body or answer is logged:
 * each may name the player (FR-020).
 */
final class Neighbour(services: ServiceClients, name: String):
  def get(path: String): Called                   = call("GET", path, None)
  def post(path: String, body: JsonNode): Called  = call("POST", path, Some(body))
  def put(path: String, body: JsonNode): Called   = call("PUT", path, Some(body))

  private def call(method: String, path: String, body: Option[JsonNode]): Called =
    attempt(method, path, body) match
      case Called.TimedOut => attempt(method, path, body)
      case other           => other

  private def attempt(method: String, path: String, body: Option[JsonNode]): Called =
    try
      val r = services(name).request(method, path, body.map(b => Json.render(b).getBytes(UTF_8)), body.map(_ => "application/json"), Nil)
      Called.Answered(r.status, String(r.body, UTF_8))
    catch
      case _: ServiceUnanswered => Called.TimedOut
      case NonFatal(_)          => Called.Unavailable

object Neighbour:
  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
