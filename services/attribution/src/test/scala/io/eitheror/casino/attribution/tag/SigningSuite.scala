package io.eitheror.casino.attribution.tag

import com.thinkmorestupidless.ankka.testkit.LogCapturing

class SigningSuite extends munit.FunSuite with LogCapturing:
  private val message = "header.payload".getBytes("US-ASCII")

  test("a generated pair signs and verifies; the public key is 32 bytes of base64url") {
    val (x, priv) = Signing.generate()
    assertEquals(Signing.decode(x).map(_.length), Some(32))
    val sig = Signing.sign(priv, message)
    assertEquals(sig.length, 64)
    assert(Signing.verify(x, message, sig))
  }

  test("a flipped byte of the message or the signature fails") {
    val (x, priv) = Signing.generate()
    val sig = Signing.sign(priv, message)
    val m2  = message.clone(); m2(0) = (m2(0) ^ 1).toByte
    val s2  = sig.clone(); s2(10) = (s2(10) ^ 1).toByte
    assert(!Signing.verify(x, m2, sig))
    assert(!Signing.verify(x, message, s2))
  }

  test("a key from x verifies what its private half signed, and no other key does") {
    val (x, priv) = Signing.generate()
    val (other, _) = Signing.generate()
    val sig = Signing.sign(priv, message)
    assert(Signing.publicKeyFromX(x).isDefined)
    assert(!Signing.verify(other, message, sig))
  }

  test("an x that is not 32 bytes of base64url is no key, and verifies nothing") {
    assertEquals(Signing.publicKeyFromX("AAAA"), None)
    assertEquals(Signing.publicKeyFromX("not base64!"), None)
    assert(!Signing.verify("AAAA", message, new Array[Byte](64)))
  }
