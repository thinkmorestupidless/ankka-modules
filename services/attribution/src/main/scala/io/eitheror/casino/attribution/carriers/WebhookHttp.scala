package io.eitheror.casino.attribution.carriers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.control.NonFatal

/** How a delivery posts a fact (contracts/protocol.md *A webhook post*): the status, or why there was none. */
trait WebhookHttp:
  def post(address: String, body: String, headers: Map[String, String]): Either[String, Int]

object WebhookHttp:
  val ProtocolVersion = "1"

  /** The hex HMAC-SHA256 of the body under the subscription's secret. */
  def signature(body: String, secret: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** The four headers of a post, and its content type. */
  def headers(fact: FeedFact, secret: String): Map[String, String] = Map(
    "Content-Type"            -> "application/json",
    "X-Attribution-Protocol"  -> ProtocolVersion,
    "X-Attribution-Fact-Id"   -> fact.factId,
    "X-Attribution-Kind"      -> fact.kind,
    "X-Attribution-Signature" -> s"sha256=${signature(fact.json, secret)}"
  )

  /** Posts with `WEBHOOK_TIMEOUT`, following no redirect: a redirect is a status like any other non-2xx. */
  def client(timeout: Duration): WebhookHttp =
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    new WebhookHttp:
      def post(address: String, body: String, headers: Map[String, String]): Either[String, Int] =
        try
          val builder = HttpRequest.newBuilder(URI.create(address)).timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          headers.foreach((k, v) => builder.header(k, v))
          Right(http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
        catch
          case e: InterruptedException => Thread.currentThread().interrupt(); Left(e.toString)
          case NonFatal(e)             => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
