package io.eitheror.casino.engagement.api

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FR-017: Customer.io's webhook signature, checked in the original's order (`CustomerIOWebhookRoutes`):
 * the configuration, the signing key, the timestamp's window, then the signature. Reading the body
 * (`could_not_read_body`) comes before and parsing it after, at the endpoint.
 */
object Signature:

  enum Refusal(val status: Int, val code: String):
    case BrandNotConfigured   extends Refusal(401, "brand_not_configured")
    case WebhookNotConfigured extends Refusal(401, "webhook_not_configured")
    case TimestampInvalid     extends Refusal(401, "timestamp_invalid")
    case InvalidSignature     extends Refusal(401, "invalid_signature")

  /** The window either side of the service's clock, in seconds; a timestamp this far off is refused. */
  val WindowSeconds: Long = 300

  def verify(
      configured: Boolean,
      signingKey: Option[String],
      timestamp: Option[String],
      signature: Option[String],
      body: Array[Byte],
      now: Instant
  ): Either[Refusal, Unit] =
    val key = signingKey.filter(_.nonEmpty)
    if !configured then Left(Refusal.BrandNotConfigured)
    else
      key.fold(Left(Refusal.WebhookNotConfigured)) { k =>
        val ts = timestamp.getOrElse("")
        if !inWindow(ts, now) then Left(Refusal.TimestampInvalid)
        else
          val expected = sign(k, ts, body).getBytes(UTF_8)
          val received = signature.getOrElse("").getBytes(UTF_8)
          if MessageDigest.isEqual(expected, received) then Right(()) else Left(Refusal.InvalidSignature)
      }

  /** Lowercase hex HMAC-SHA256, under the signing key, of `v0:<timestamp>:<body>`. */
  def sign(signingKey: String, timestamp: String, body: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(signingKey.getBytes(UTF_8), "HmacSHA256"))
    mac.update(s"v0:$timestamp:".getBytes(UTF_8))
    mac.doFinal(body).map(b => f"${b & 0xff}%02x").mkString

  // Compared without subtracting, so a timestamp near Long's ends cannot wrap into the window.
  private def inWindow(timestamp: String, now: Instant): Boolean =
    val nowSeconds = now.getEpochSecond
    timestamp.toLongOption.exists(ts => ts > nowSeconds - WindowSeconds && ts < nowSeconds + WindowSeconds)
