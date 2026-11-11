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

  /**
   * The public key of an EC private key, as base64 of its DER SubjectPublicKeyInfo (spec 026
   * `contracts/brand-amendments.md`: the operator copies it into the provider's brand entry). The
   * JDK derives no public key from a private one, so it is made with the JDK's own primitives:
   * ECDH between the private key and the curve's generator is the public point's x; y is one of the
   * two square roots of x³ + ax + b (a prime p ≡ 3 mod 4, as P-256's is, gives them directly); the
   * root is the one under which a signature by the private key verifies.
   */
  def publicKeyOf(privateText: String): Either[String, String] =
    privateKey(privateText).flatMap {
      case k: java.security.interfaces.ECPrivateKey =>
        Try {
          import java.math.BigInteger
          import java.security.spec.{ECFieldFp, ECPoint, ECPublicKeySpec}
          val params = k.getParams
          val p      = params.getCurve.getField.asInstanceOf[ECFieldFp].getP
          require(p.testBit(0) && p.testBit(1), "the curve's prime is not 3 mod 4")
          val factory   = KeyFactory.getInstance("EC")
          val generator = factory.generatePublic(ECPublicKeySpec(params.getGenerator, params))
          val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
          agreement.init(k)
          agreement.doPhase(generator, true)
          val x   = BigInteger(1, agreement.generateSecret())
          val rhs = x.pow(3).add(params.getCurve.getA.multiply(x)).add(params.getCurve.getB).mod(p)
          val y   = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
          val probe = "eitheror operator key".getBytes(UTF_8)
          val signed = Base64.getDecoder.decode(sign(probe, k))
          Vector(y, p.subtract(y)).map(r => factory.generatePublic(ECPublicKeySpec(ECPoint(x, r), params))).find { candidate =>
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(candidate)
            v.update(probe)
            v.verify(signed)
          }.map(pub => Base64.getEncoder.encodeToString(pub.getEncoded)).getOrElse(throw IllegalStateException("no root verifies"))
        }.toEither.left.map(e => s"the public key could not be derived: ${e.getMessage}")
      case _ => Left("not an EC private key")
    }

  /** Base64 of a PEM document's DER, which is what the configuration stores. */
  def base64Der(text: String): Either[String, String] = der(text).map(Base64.getEncoder.encodeToString)

  private def der(text: String): Either[String, Array[Byte]] =
    val body = text.linesIterator.filterNot(_.startsWith("-----")).mkString.replaceAll("\\s", "")
    Try(Base64.getDecoder.decode(body)).toEither.left.map(_ => "not base64").filterOrElse(_.nonEmpty, "empty")
