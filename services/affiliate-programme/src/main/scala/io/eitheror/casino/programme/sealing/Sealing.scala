package io.eitheror.casino.programme.sealing

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Try

/**
 * Everything personal about an affiliate (research R9): only ever stored sealed. The payment method is
 * here because it holds bank details.
 */
final case class Identity(
    name: String,
    displayName: Option[String] = None,
    email: String,
    companyName: Option[String] = None,
    website: Option[String] = None,
    trafficSource: Option[String] = None,
    paymentMethod: Option[String] = None
)

object Identity:
  given JsonValueCodec[Identity] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))

/**
 * An identity sealed under its affiliate's data key: AES-256-GCM, a fresh 96-bit nonce per seal, the
 * affiliate id as associated data, so a sealed value moved to another account does not open.
 * `keyVersion` 0 is the erased value, which holds nothing and opens to nothing.
 */
final case class Sealed(ciphertext: String, nonce: String, keyVersion: Int = 1):
  def isErased: Boolean = keyVersion == 0

object Sealed:
  val erased: Sealed = Sealed("", "", 0)

object Sealing:
  private val random   = SecureRandom()
  private val TagBits  = 128
  private val NonceLen = 12

  /** A fresh 256-bit data key. */
  def newKey(): Array[Byte] =
    val key = new Array[Byte](32)
    random.nextBytes(key)
    key

  def seal(identity: Identity, key: Array[Byte], affiliate: String): Sealed =
    val nonce = new Array[Byte](NonceLen)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
    cipher.updateAAD(affiliate.getBytes(UTF_8))
    val enc = Base64.getEncoder
    Sealed(enc.encodeToString(cipher.doFinal(writeToArray(identity))), enc.encodeToString(nonce))

  /** The identity, or why it cannot be opened: erased, another key, another affiliate, a changed byte. */
  def open(value: Sealed, key: Array[Byte], affiliate: String): Either[String, Identity] =
    if value.isErased then Left("erased")
    else
      Try {
        val dec    = Base64.getDecoder
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, dec.decode(value.nonce)))
        cipher.updateAAD(affiliate.getBytes(UTF_8))
        readFromArray[Identity](cipher.doFinal(dec.decode(value.ciphertext)))
      }.toEither.left.map(_ => "the sealed identity does not open")
