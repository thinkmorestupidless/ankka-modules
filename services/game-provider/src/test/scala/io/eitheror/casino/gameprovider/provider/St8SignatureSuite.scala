package io.eitheror.casino.gameprovider.provider

import io.eitheror.casino.gameprovider.TestKeys
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

class St8SignatureSuite extends munit.FunSuite:

  private val body = """{"token":"abc","transaction_id":"t-1","amount":"1.00"}"""

  test("a body signed with the test key verifies under its public key") {
    val sig = St8Signature.sign(body, TestKeys.testPrivate)
    assert(St8Signature.verify(body, Some(sig), Seq(TestKeys.testPublic)))
  }

  test("a signature over different bytes is refused") {
    val sig = St8Signature.sign(body, TestKeys.testPrivate)
    assert(!St8Signature.verify(body.replace("1.00", "100.00"), Some(sig), Seq(TestKeys.testPublic)))
    assert(!St8Signature.verify(body + " ", Some(sig), Seq(TestKeys.testPublic)), "even whitespace is a different body")
  }

  test("a signature under another key is refused, and accepted when that key is also current") {
    val sig = St8Signature.sign(body, TestKeys.otherPrivate)
    assert(!St8Signature.verify(body, Some(sig), Seq(TestKeys.testPublic)))
    assert(St8Signature.verify(body, Some(sig), Seq(TestKeys.testPublic, TestKeys.otherPublic)))
  }

  test("a missing, empty or non-base64 header is refused") {
    assert(!St8Signature.verify(body, None, Seq(TestKeys.testPublic)))
    assert(!St8Signature.verify(body, Some(""), Seq(TestKeys.testPublic)))
    assert(!St8Signature.verify(body, Some("not base64!!"), Seq(TestKeys.testPublic)))
    assert(!St8Signature.verify(body, Some("AAAA"), Seq(TestKeys.testPublic)), "base64 that is not a signature")
  }

  test("no key verifies nothing") {
    assert(!St8Signature.verify(body, Some(St8Signature.sign(body, TestKeys.testPrivate)), Nil))
  }

  test("keys load from PEM and from base64 DER alike") {
    assertEquals(St8Signature.publicKey(TestKeys.testPublicDer).toOption.get, TestKeys.testPublic)
    assertEquals(St8Signature.privateKey(TestKeys.testPrivateDer).toOption.get.getEncoded.toVector, TestKeys.testPrivate.getEncoded.toVector)
    assert(St8Signature.publicKey("nope").isLeft)
  }

  test("a body that is not ASCII verifies over its UTF-8 bytes (research R3)") {
    val text = """{"player":"Zoë","amount":"1.00"}"""
    val sig  = St8Signature.sign(text.getBytes("UTF-8"), TestKeys.testPrivate)
    assert(St8Signature.verify(text, Some(sig), Seq(TestKeys.testPublic)))
  }
