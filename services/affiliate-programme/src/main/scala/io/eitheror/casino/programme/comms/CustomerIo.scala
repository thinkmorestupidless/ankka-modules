package io.eitheror.casino.programme.comms

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import io.eitheror.casino.programme.ProgrammeCodecs

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.Try

/** Customer.io's transactional send (`S/AffiliateInviteEmailService.scala:311-391`): the status it answered, or why there was none. */
trait CustomerIo:
  def send(apiKey: String, region: String, transactionalId: String, to: String, data: Map[String, String]): Either[String, Int]

object CustomerIo:
  final case class Identifiers(email: String)
  final case class SendBody(transactional_message_id: String, to: String, identifiers: Identifiers, message_data: Map[String, String])
  given JsonValueCodec[SendBody] = ProgrammeCodecs.wire[SendBody]

  /** The app API's base for a region: `us`, or `eu` (the default, as the original's). */
  def base(region: String): String = if region == "us" then "https://api.customer.io" else "https://api-eu.customer.io"

  def body(transactionalId: String, to: String, data: Map[String, String]): String =
    writeToString(SendBody(transactionalId, to, Identifiers(to), data))

  def http(timeout: Duration, baseOf: String => String = base): CustomerIo = new CustomerIo:
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    def send(apiKey: String, region: String, transactionalId: String, to: String, data: Map[String, String]): Either[String, Int] =
      val request = HttpRequest.newBuilder(URI.create(s"${baseOf(region)}/v1/send/email")).timeout(timeout)
        .header("Authorization", s"Bearer $apiKey").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body(transactionalId, to, data))).build()
      Try(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode).toEither.left
        .map(e => s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
