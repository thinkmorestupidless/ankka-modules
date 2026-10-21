package io.eitheror.casino.pspgateway.delivery

import io.eitheror.casino.pspgateway.providers.Digests

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * The signature on a capture or a fail sent to `merchant` (research R13): hex HMAC-SHA256 of the body's
 * UTF-8 bytes under the credential's `webhookSecret` in `X-PSP-Signature`, with the Unix seconds in
 * `X-PSP-Timestamp`. The inverse of 004's `security/Hmac.verify`.
 */
object Signature:
  val SignatureHeader = "X-PSP-Signature"
  val TimestampHeader = "X-PSP-Timestamp"

  def sign(body: String, secret: String, now: Instant): Vector[(String, String)] =
    Vector(
      SignatureHeader -> Digests.hex(Digests.hmacSha256(secret, body.getBytes(UTF_8))),
      TimestampHeader -> now.getEpochSecond.toString
    )
