package io.eitheror.casino.merchant.security

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/**
 * The provider's signature on a capture (FR-018), as the original verifies it (`CashierRoutes`
 * capture, `verifyCaptureHmac`): hex HMAC-SHA256 of the raw body under the credential's
 * `webhookSecret`, carried in `X-PSP-Signature` with the Unix seconds it was made in `X-PSP-Timestamp`,
 * accepted within 300 seconds either way and compared in constant time. A provider with no secret is
 * accepted unsigned, as the simulator is. The body is the request's text: ankka hands an endpoint a
 * `String` body as the raw request, and JSON is UTF-8, so the bytes are the ones signed (spec 003 R3).
 */
object Hmac:
  val SignatureHeader = "X-PSP-Signature"
  val TimestampHeader = "X-PSP-Timestamp"
  val Window: Long    = 300L

  enum Outcome:
    /** No secret is configured and nothing was signed: the simulator's path. */
    case Unsigned
    case Verified
    /** A client error the provider must not retry: the status and the original's code and message. */
    case Refused(status: Int, error: String, message: String)
    /** The signature cannot be judged: answered 503 so the provider retries. */
    case Unavailable(error: String, message: String)

  private val missing = Outcome.Refused(401, "missing_token", "PSP signature header required")

  /**
   * Whether a capture may proceed. `secret` is the credential's `webhookSecret`, or none when the
   * provider has none configured; a store that could not be read is the caller's to answer 503
   * before asking this.
   */
  def verify(body: String, signature: Option[String], timestamp: Option[String], secret: Option[String], now: Instant): Outcome =
    (signature.filter(_.nonEmpty), timestamp.filter(_.nonEmpty)) match
      case (None, None) => if secret.isEmpty then Outcome.Unsigned else missing
      case (None, _) | (_, None) => missing
      case (Some(sig), Some(ts)) =>
        Try(ts.trim.toLong).toOption match
          case None => missing
          case Some(at) if math.abs(now.getEpochSecond - at) > Window =>
            Outcome.Refused(403, "invalid_token", "Request timestamp outside replay window")
          case Some(_) =>
            secret match
              case None => Outcome.Unavailable("webhook_secret_not_configured", "Webhook endpoint unavailable: secret not configured")
              case Some(s) =>
                if safeEquals(sign(s, body), sig) then Outcome.Verified
                else Outcome.Refused(403, "invalid_token", "PSP signature verification failed")

  /** Hex HMAC-SHA256 of the text's UTF-8 bytes under the secret. */
  def sign(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(body.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  private def safeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(UTF_8), b.trim.toLowerCase.getBytes(UTF_8))
