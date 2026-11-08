package io.eitheror.casino.engagement.clients

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.profile.Attributes

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import scala.util.Try

/** What a call to Customer.io came to, as a `Send` step acts on it (research R5). */
enum CrmAnswer:
  case Accepted(status: Int)
  /** A connection failure, a timeout, a 429 or a 5xx: the step tries again. */
  case Retryable(status: Option[Int], why: String)
  /** Any other answer: recorded, never retried. */
  case Refused(status: Int, body: String)

/** The brand's site and keys for one call; the keys are read from the secret store at each attempt. */
final case class CrmCredentials(siteId: String, region: String, trackKey: Option[String], appKey: Option[String])

/** The CRM as the steps, the seed and erasure call it (contracts/routes.md, *The calls out*). */
trait Crm:
  def identify(c: CrmCredentials, playerId: String, attributes: Vector[(String, String)]): CrmAnswer
  def track(c: CrmCredentials, playerId: String, event: String, attributes: Vector[(String, String)]): CrmAnswer
  def send(c: CrmCredentials, transactionalId: String, to: String, data: Vector[(String, String)]): CrmAnswer
  def delete(c: CrmCredentials, playerId: String): CrmAnswer

/**
 * Customer.io over HTTP: the Track API (`/api/v2/entity`, basic `siteId:trackKey`) for the person and their
 * events, the App API (`/v1/send/email`, bearer `appKey`) for a transactional email, at the region's hosts unless
 * the settings name others (the stub's). Every call has `timeout`. Bodies and answers are never logged: both name
 * the player.
 */
final class CustomerIo(trackUrl: Option[String], appUrl: Option[String], timeout: Duration) extends Crm:
  private val http = HttpClient.newBuilder().connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build()

  def identify(c: CrmCredentials, playerId: String, attributes: Vector[(String, String)]): CrmAnswer =
    entity(c, CustomerIo.identifyBody(playerId, attributes))

  def track(c: CrmCredentials, playerId: String, event: String, attributes: Vector[(String, String)]): CrmAnswer =
    entity(c, CustomerIo.trackBody(playerId, event, attributes))

  def delete(c: CrmCredentials, playerId: String): CrmAnswer = entity(c, CustomerIo.deleteBody(playerId))

  def send(c: CrmCredentials, transactionalId: String, to: String, data: Vector[(String, String)]): CrmAnswer =
    c.appKey match
      case None      => CrmAnswer.Refused(401, "no app key is configured")
      case Some(key) =>
        post(s"${appUrl.getOrElse(CustomerIo.appHost(c.region))}/v1/send/email", s"Bearer $key",
          CustomerIo.sendBody(transactionalId, to, data))

  private def entity(c: CrmCredentials, body: ObjectNode): CrmAnswer =
    c.trackKey match
      case None      => CrmAnswer.Refused(401, "no track key is configured")
      case Some(key) =>
        val basic = Base64.getEncoder.encodeToString(s"${c.siteId}:$key".getBytes(UTF_8))
        post(s"${trackUrl.getOrElse(CustomerIo.trackHost(c.region))}/api/v2/entity", s"Basic $basic", body)

  private def post(url: String, authorization: String, body: ObjectNode): CrmAnswer =
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
      .header("Content-Type", "application/json").header("Authorization", authorization)
      .POST(HttpRequest.BodyPublishers.ofString(Json.render(body))).build()
    Try(http.send(request, HttpResponse.BodyHandlers.ofString())).fold(
      e => CrmAnswer.Retryable(None, e.getClass.getSimpleName),
      r => CustomerIo.answer(r.statusCode)
    )

object CustomerIo:
  def trackHost(region: String): String = if region == "eu" then "https://track-eu.customer.io" else "https://track.customer.io"
  def appHost(region: String): String   = if region == "eu" then "https://api-eu.customer.io" else "https://api.customer.io"

  /**
   * The answer by its status alone. A refusal's body is not kept: Customer.io's can name the value it rejected (an
   * address, an attribute), and the send's state is not sealed (SC-006).
   */
  def answer(status: Int): CrmAnswer =
    if status / 100 == 2 then CrmAnswer.Accepted(status)
    else if status == 429 || status >= 500 then CrmAnswer.Retryable(Some(status), s"Customer.io answered $status")
    else CrmAnswer.Refused(status, s"Customer.io refused $status")

  /** The attributes as the original wrote them: FR-003's nine as numbers where they read as one, the rest strings. */
  def attributes(values: Vector[(String, String)]): ObjectNode =
    val o = Json.obj()
    values.foreach { (k, v) =>
      Attributes.number(k, v).fold(o.put(k, v))(n => o.put(k, n.bigDecimal)): Unit
    }
    o

  private def person(playerId: String, action: String) =
    Json.obj("type" -> "person", "identifiers" -> Json.obj("id" -> playerId), "action" -> action)

  def identifyBody(playerId: String, values: Vector[(String, String)]): ObjectNode =
    val o = person(playerId, "identify")
    o.set[ObjectNode]("attributes", attributes(values))
    o

  def trackBody(playerId: String, event: String, values: Vector[(String, String)]): ObjectNode =
    val o = person(playerId, "event")
    o.put("name", event)
    o.set[ObjectNode]("attributes", attributes(values))
    o

  def deleteBody(playerId: String): ObjectNode = person(playerId, "delete")

  /** `message_data` is all strings, as the original sent it. */
  def sendBody(transactionalId: String, to: String, data: Vector[(String, String)]): ObjectNode =
    val messageData = Json.obj()
    data.foreach((k, v) => messageData.put(k, v): Unit)
    Json.obj("transactional_message_id" -> transactionalId, "to" -> to, "identifiers" -> Json.obj("email" -> to),
      "message_data" -> messageData)
