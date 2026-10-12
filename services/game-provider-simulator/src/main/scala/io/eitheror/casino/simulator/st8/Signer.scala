package io.eitheror.casino.simulator.st8

import java.security.*
import java.security.spec.{ECGenParameterSpec, PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64

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
