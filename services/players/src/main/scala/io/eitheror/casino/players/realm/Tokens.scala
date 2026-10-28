package io.eitheror.casino.players.realm

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/**
 * The signed links the service emails (FR-005, FR-026, FR-027): verified with nothing stored, as the
 * original's `EmailVerificationTokenStore` does. A token is the base64url (unpadded) of
 * `{"b","p","e","x","t"}` (brand, player, address, expiry in epoch milliseconds, purpose), a dot, and
 * the base64url HMAC-SHA256 of that text under the `players-email-token` secret. A token of one purpose
 * is refused for another, so a verification link cannot change a password.
 */
object Tokens:

  enum Purpose(val wire: String):
    case EmailVerify    extends Purpose("email_verify")
    case EmailAdd       extends Purpose("email_add")
    case EmailChange    extends Purpose("email_change")
    case PasswordChange extends Purpose("password_change")

  /** What a token says: whose, which address, until when, and what for. */
  final case class Claims(brand: String, playerId: String, address: String, expiresAt: Instant, purpose: Purpose)

  enum Refused:
    case Invalid, Expired

  private final case class Payload(b: String, p: String, e: String, x: Long, t: String)
  private given JsonValueCodec[Payload] = JsonCodecMaker.make

  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  def mint(secret: String, claims: Claims): String =
    val payload = encoder.encodeToString(writeToArray(Payload(claims.brand, claims.playerId, claims.address,
      claims.expiresAt.toEpochMilli, claims.purpose.wire)))
    s"$payload.${sign(secret, payload)}"

  /** The token's claims when its signature holds, it has not expired at `now` and it is for `purpose`. */
  def verify(secret: String, token: String, purpose: Purpose, now: Instant): Either[Refused, Claims] =
    token.split('.') match
      case Array(payload, signature) if MessageDigest.isEqual(signature.getBytes(UTF_8), sign(secret, payload).getBytes(UTF_8)) =>
        Try(readFromArray[Payload](decoder.decode(payload))).toOption
          .flatMap(p => Purpose.values.find(_.wire == p.t).map(t => Claims(p.b, p.p, p.e, Instant.ofEpochMilli(p.x), t)))
          .filter(_.purpose == purpose)
          .toRight(Refused.Invalid)
          .flatMap(c => if now.isBefore(c.expiresAt) then Right(c) else Left(Refused.Expired))
      case _ => Left(Refused.Invalid)

  private def sign(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    encoder.encodeToString(mac.doFinal(payload.getBytes(UTF_8)))
