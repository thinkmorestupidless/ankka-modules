package io.eitheror.casino.audit.ingest

import io.eitheror.casino.audit.ids.RegistryKeyBytes

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.util.Try

/** A quarantined message's bytes, sealed with AES-256-GCM: base64 of the 12-byte nonce and the ciphertext. */
object Sealing:
  private val NonceBytes = 12
  private val TagBits    = 128
  private val random     = SecureRandom()

  def seal(key: RegistryKeyBytes, plain: Array[Byte]): String =
    val nonce = new Array[Byte](NonceBytes)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TagBits, nonce))
    Base64.getEncoder.encodeToString(nonce ++ cipher.doFinal(plain))

  /** The bytes, or nothing when they were sealed under another key or changed since. */
  def unseal(key: RegistryKeyBytes, text: String): Option[Array[Byte]] =
    Try {
      val all    = Base64.getDecoder.decode(text)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TagBits, all.take(NonceBytes)))
      cipher.doFinal(all.drop(NonceBytes))
    }.toOption
