package io.eitheror.casino.gameprovider.provider.st8

import java.nio.charset.StandardCharsets.UTF_8
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, PrivateKey, PublicKey, Signature}
import java.util.Base64
import scala.util.Try

/**
 * ST8's signature: `SHA256withECDSA` on P-256, base64 of the DER signature, in `x-st8-sign`, over the
 * exact bytes of a body (a POST) or the query string (a GET) (FR-003, contracts/provider-api.md).
 * The callbacks are verified under the provider's public key; the calls to ST8 are signed under the
 * brand's operator private key.
 */
object St8Signature:
  val Header = "x-st8-sign"

  /** Whether `header` is a valid signature over `body` under any of `keys`; a missing or non-base64 header is not. */
  def verify(body: Array[Byte], header: Option[String], keys: Seq[PublicKey]): Boolean =
    header.map(_.trim).filter(_.nonEmpty).flatMap(h => Try(Base64.getDecoder.decode(h)).toOption) match
      case None => false
      case Some(signature) =>
        keys.exists { key =>
          Try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(body)
            verifier.verify(signature)
          }.getOrElse(false)
        }

  def verify(body: String, header: Option[String], keys: Seq[PublicKey]): Boolean = verify(body.getBytes(UTF_8), header, keys)

  def sign(body: Array[Byte], key: PrivateKey): String =
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(key)
    signer.update(body)
    Base64.getEncoder.encodeToString(signer.sign())

  def sign(body: String, key: PrivateKey): String = sign(body.getBytes(UTF_8), key)

  /** A public key as PEM or as base64 of its DER SubjectPublicKeyInfo, the form ST8 publishes. */
  def publicKey(text: String): Either[String, PublicKey] =
    der(text).flatMap(bytes => Try(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))).toEither.left.map(_ => "not an EC public key"))

  /** A private key as PEM or as base64 of its PKCS#8 DER. */
  def privateKey(text: String): Either[String, PrivateKey] =
    der(text).flatMap(bytes => Try(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))).toEither.left.map(_ => "not an EC private key in PKCS#8"))

  /** Base64 of a PEM document's DER, which is what the configuration stores. */
  def base64Der(text: String): Either[String, String] = der(text).map(Base64.getEncoder.encodeToString)

  private def der(text: String): Either[String, Array[Byte]] =
    val body = text.linesIterator.filterNot(_.startsWith("-----")).mkString.replaceAll("\\s", "")
    Try(Base64.getDecoder.decode(body)).toEither.left.map(_ => "not base64").filterOrElse(_.nonEmpty, "empty")
