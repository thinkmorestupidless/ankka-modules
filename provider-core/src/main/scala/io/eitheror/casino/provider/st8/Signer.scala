package io.eitheror.casino.provider.st8

import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.spec.{ECFieldFp, ECGenParameterSpec, ECPoint, ECPublicKeySpec, PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * The provider's signature (FR-010): ECDSA on P-256 over the SHA-256 of the exact body bytes,
 * ASN.1 (DER) encoded, then base64, in `x-st8-sign`. Java's `SHA256withECDSA` hashes then signs and
 * writes DER, which is what Go's `ecdsa.SignASN1(sha256(body))` produces. Public keys travel as PKIX
 * (SubjectPublicKeyInfo) DER in base64 with padding, as the stand-in registers them; private keys as
 * PKCS#8 DER in base64, only ever inside the secret store.
 */
object Signer:

  val Header = "x-st8-sign"

  def generate(): KeyPair =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()

  def sign(body: Array[Byte], key: PrivateKey): String =
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(key)
    signature.update(body)
    Base64.getEncoder.encodeToString(signature.sign())

  def verify(body: Array[Byte], header: String, key: PublicKey): Boolean =
    try
      val signature = Signature.getInstance("SHA256withECDSA")
      signature.initVerify(key)
      signature.update(body)
      signature.verify(Base64.getDecoder.decode(header))
    catch case _: Exception => false

  def publicKeyText(key: PublicKey): String   = Base64.getEncoder.encodeToString(key.getEncoded)
  def privateKeyText(key: PrivateKey): String = Base64.getEncoder.encodeToString(key.getEncoded)

  def publicKey(text: String): PublicKey =
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder.decode(text)))

  def privateKey(text: String): PrivateKey =
    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(text)))

  /**
   * The public key of a private key (spec 026 R11: a brand's configuration read answers the
   * operator public key it stores only the private half of). The JDK has no call for it, and the
   * curve arithmetic is not written here: an ECDH agreement of the key with the curve's generator
   * is the x-coordinate of the public point, computed by the JDK. y follows from the curve's
   * equation (P-256's prime is 3 mod 4, so the root is one power); of its two signs, the one that
   * verifies a signature made with the key is the public key's.
   */
  def publicKeyFromPrivate(key: PrivateKey): PublicKey =
    val params    = key.asInstanceOf[ECPrivateKey].getParams
    val factory   = KeyFactory.getInstance("EC")
    val generator = factory.generatePublic(ECPublicKeySpec(params.getGenerator, params))
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(key)
    agreement.doPhase(generator, true)
    val x     = BigInteger(1, agreement.generateSecret())
    val curve = params.getCurve
    val p     = curve.getField.asInstanceOf[ECFieldFp].getP
    val rhs   = x.pow(3).add(curve.getA.multiply(x)).add(curve.getB).mod(p)
    val y     = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
    require(y.multiply(y).mod(p) == rhs, "the agreement is not on the curve")
    val probe = "provider-core public key probe".getBytes("UTF-8")
    val proof = sign(probe, key)
    Vector(y, p.subtract(y).mod(p))
      .map(candidate => factory.generatePublic(ECPublicKeySpec(ECPoint(x, candidate), params)))
      .find(candidate => verify(probe, proof, candidate))
      .getOrElse(throw IllegalStateException("neither point verifies the key's own signature"))
