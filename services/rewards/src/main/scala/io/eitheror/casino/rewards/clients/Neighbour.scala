package io.eitheror.casino.rewards.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceResponse}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** A neighbour's answer: its status and body, or that it could not be reached at all. */
enum Reply:
  case Answered(status: Int, body: Option[JsonNode], text: String)
  case Unreachable(reason: String)

/**
 * What a call came to, as the caller acts on it: done with what it answered, refused with the neighbour's code
 * (a 4xx naming one), or not answered at all (unreachable, a 5xx, or a 4xx with no code), which a workflow's step
 * retries (research R5).
 */
enum Called[+A]:
  case Done(value: A)
  case Refused(status: Int, code: String, message: String)
  case Unreachable(reason: String)

object Called:
  /**
   * The reading every client shares: a 2xx is read by `read` (an empty body as `{}`), a 4xx with an `error` code
   * is refused with it, anything else is not answered. `read` answering none is a body that cannot be read.
   */
  def of[A](reply: Reply, who: String)(read: JsonNode => Option[A]): Called[A] = reply match
    case Reply.Answered(s, body, _) if s / 100 == 2 =>
      read(body.getOrElse(Json.obj())).fold(Called.Unreachable(s"$who answered $s with a body it cannot read"))(Called.Done(_))
    case Reply.Answered(s, Some(b), _) if s / 100 == 4 && b.text("error").isDefined =>
      Called.Refused(s, b.text("error").get, b.textOr("message", ""))
    case Reply.Answered(s, _, _) => Called.Unreachable(s"$who answered $s")
    case Reply.Unreachable(why)  => Called.Unreachable(why)

/**
 * Calls to another service of this project as this service, by name and identity (no secret). Every call answers a
 * `Reply` rather than throwing, so a refusal is told apart from a neighbour that is down. No body or path is logged:
 * both may name the player.
 */
final class Neighbour(services: ServiceClients, name: String):
  def get(path: String): Reply = call("GET", path, None)

  def post(path: String, body: JsonNode): Reply = call("POST", path, Some(Json.render(body)))

  private def call(method: String, path: String, body: Option[String]): Reply =
    Try(services(name).request(method, path, body.map(_.getBytes(UTF_8)), body.map(_ => "application/json"), Nil))
      .fold(e => Reply.Unreachable(s"$name unreachable: ${e.getClass.getSimpleName}"), answered)

  private def answered(r: ServiceResponse): Reply =
    val text = String(r.body, UTF_8)
    Reply.Answered(r.status, Json.parse(text).filter(_ => text.trim.nonEmpty), text)

object Neighbour:
  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")
