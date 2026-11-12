package io.eitheror.casino.publicapi.keys

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** A key as parsed from `Authorization: Bearer …`: its id, which names its entity, and the whole key. */
final case class Presented(keyId: String, whole: String)

/**
 * An access key's raw value, minted once and shown once, and its digest, kept instead (research R4), as
 * `services/merchant/.../keys/Keys.scala` keeps cashier keys. The format is `ak_<keyId>_<secret>`: `keyId` is 16
 * lower-case base32 characters (80 bits) and `secret` is 32 bytes base64url without padding (43 characters).
 */
object RawKeys:
  private val random   = SecureRandom()
  private val Alphabet = "abcdefghijklmnopqrstuvwxyz234567"
  private val Shape    = "ak_([a-z2-7]{16})_([A-Za-z0-9_-]{43})".r

  /** A new key id and the whole key. */
  def mint(): (String, String) =
    val id     = keyId()
    val secret = new Array[Byte](32)
    random.nextBytes(secret)
    (id, s"ak_${id}_${Base64.getUrlEncoder.withoutPadding.encodeToString(secret)}")

  private def keyId(): String =
    val bytes = new Array[Byte](10)
    random.nextBytes(bytes)
    // 80 bits as 16 groups of 5, most significant first.
    val bits = BigInt(1, bytes)
    (15 to 0 by -1).map(i => Alphabet(((bits >> (i * 5)) & 31).toInt)).mkString

  /** The key, or `None` for any other shape: a malformed key is refused without a lookup. */
  def parse(text: String): Option[Presented] = text match
    case Shape(id, _) => Some(Presented(id, text))
    case _            => None

  /** "The start of the key" shown in lists. */
  def prefix(keyId: String): String = s"ak_$keyId"

  /** Hex SHA-256 of the whole key: what the entity keeps. */
  def digest(whole: String): String =
    MessageDigest.getInstance("SHA-256").digest(whole.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** Constant time, so a wrong secret learns nothing from how long the refusal took. */
  def same(a: String, b: String): Boolean = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))
