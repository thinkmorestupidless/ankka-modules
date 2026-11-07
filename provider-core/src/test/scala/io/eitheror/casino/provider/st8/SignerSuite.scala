package io.eitheror.casino.provider.st8

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The signature the house provider verifies on a brand's operator API calls (spec 026 R5) and the
 * public key it derives for a brand's configuration read (R11).
 *
 * The oracle is a vector made outside the JVM, with LibreSSL 3.3.6, so a fault shared by `Signer`
 * and the brand's `St8Signature` (both are the JDK's `SHA256withECDSA`) cannot pass unnoticed:
 *
 * {{{
 *   openssl ecparam -name prime256v1 -genkey -noout -out k.pem
 *   openssl pkcs8 -topk8 -nocrypt -in k.pem -outform DER | base64          # privateKey
 *   openssl ec -in k.pem -pubout -outform DER | base64                     # publicKey
 *   openssl dgst -sha256 -sign k.pem body | base64                         # signature
 * }}}
 *
 * The key is a test key made for this suite and signs nothing anywhere else.
 */
class SignerSuite extends munit.FunSuite with LogCapturing:

  private val privateKey =
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxUxEqSvlXI6mJxb3kFv2kx6YGngC72HVWcL6/nyOnnyhRANCAAT/5eFl/Axismdy6SANtlmgeuNIpoWw7KOhQb8S5C7uEOAYzIxRp537l7rdek1iKgdzuQaHmrK7c2ltII/sZ92S"
  private val publicKey =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/+XhZfwMYrJncukgDbZZoHrjSKaFsOyjoUG/EuQu7hDgGMyMUaed+5e63XpNYioHc7kGh5qyu3NpbSCP7Gfdkg=="
  private val body =
    """{"amount":"1.00","currency":"BRL","round":"r1","token":"s-L1","transaction_id":"t1"}""".getBytes("UTF-8")
  private val signature =
    "MEQCIE1gc9lcWB+Gm/zAFRWgZ/kgZonLSJXtADCjn5cuvo7qAiAznzWMiYaED0lxlSwU79GFCTODsufMymzfz0sY3PmxtA=="

  test("a signature made outside the JVM verifies under its public key") {
    assert(Signer.verify(body, signature, Signer.publicKey(publicKey)))
  }

  test("the same signature does not verify over another body, or under another key") {
    assert(!Signer.verify(body.dropRight(1) ++ "x".getBytes, signature, Signer.publicKey(publicKey)))
    assert(!Signer.verify(body, signature, Signer.generate().getPublic))
  }

  test("a header that is not base64, or empty, does not verify") {
    assert(!Signer.verify(body, "not base64!", Signer.publicKey(publicKey)))
    assert(!Signer.verify(body, "", Signer.publicKey(publicKey)))
  }

  test("the public key derived from the private key is the one openssl wrote") {
    assertEquals(Signer.publicKeyText(Signer.publicKeyFromPrivate(Signer.privateKey(privateKey))), publicKey)
  }

  test("the public key derived from a key pair the JDK made is that pair's") {
    (1 to 20).foreach { _ =>
      val pair = Signer.generate()
      assertEquals(Signer.publicKeyText(Signer.publicKeyFromPrivate(pair.getPrivate)), Signer.publicKeyText(pair.getPublic))
    }
  }

  test("what Signer signs verifies under the derived key") {
    val pair = Signer.generate()
    assert(Signer.verify(body, Signer.sign(body, pair.getPrivate), Signer.publicKeyFromPrivate(pair.getPrivate)))
  }
