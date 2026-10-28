package io.eitheror.casino.attribution.tag

import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPairGenerator, Signature}
import java.util.Base64
import scala.util.Try

/**
 * Ed25519 through the JDK (research R2), no library. A public key travels as a key document's `x`: the 32
 * raw bytes, base64url without padding. A private key is kept in the store as its PKCS #8 encoding,
 * base64url, and never leaves the service.
 */
object Signing:
  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder

  /** The X.509 prefix of an Ed25519 public key: the algorithm's OID and the bit string's header. */
  private val X509Prefix: Array[Byte] = hex("302a300506032b6570032100")

  /** A new key pair: (public `x`, private PKCS #8), each base64url without padding. */
  def generate(): (String, String) =
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val x509 = pair.getPublic.getEncoded
    (enc.encodeToString(x509.drop(X509Prefix.length)), enc.encodeToString(pair.getPrivate.getEncoded))

  def sign(privateKey: String, bytes: Array[Byte]): Array[Byte] =
    val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(dec.decode(privateKey)))
    val s   = Signature.getInstance("Ed25519")
    s.initSign(key)
    s.update(bytes)
    s.sign()

  /** Whether `signature` is `publicKey`'s over `bytes`; a key or signature that cannot be read is false. */
  def verify(publicKey: String, bytes: Array[Byte], signature: Array[Byte]): Boolean =
    Try {
      val key = publicKeyFromX(publicKey).get
      val s   = Signature.getInstance("Ed25519")
      s.initVerify(key)
      s.update(bytes)
      s.verify(signature)
    }.getOrElse(false)

  /** The JDK key of a key document's `x`, or none when it is not 32 bytes of base64url. */
  def publicKeyFromX(x: String): Option[java.security.PublicKey] =
    Try(dec.decode(x)).toOption.filter(_.length == 32).flatMap(raw =>
      Try(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509Prefix ++ raw))).toOption)

  def encode(bytes: Array[Byte]): String = enc.encodeToString(bytes)

  def decode(text: String): Option[Array[Byte]] = Try(dec.decode(text)).toOption

  private def hex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
