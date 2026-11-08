package io.eitheror.casino.programme.sealing

import java.util.Base64

class SealingSuite extends munit.FunSuite:

  private val identity = Identity("Ada Lovelace", Some("Ada"), "ada@example.com", Some("Engines Ltd"),
    Some("https://ada.example"), Some("seo"), Some("""{"iban":"GB33BUKB20201555555555"}"""))
  private val key = Sealing.newKey()

  test("an identity sealed opens to itself under its key and its affiliate") {
    assertEquals(Sealing.open(Sealing.seal(identity, key, "aff-1"), key, "aff-1"), Right(identity))
  }

  test("nothing readable is in a sealed value") {
    val s = Sealing.seal(identity, key, "aff-1")
    val bytes = new String(Base64.getDecoder.decode(s.ciphertext), "ISO-8859-1")
    Seq("Ada", "ada@example.com", "GB33", "Engines").foreach(v => assert(!bytes.contains(v), v))
  }

  test("a changed byte, another affiliate's id and another key all fail") {
    val s = Sealing.seal(identity, key, "aff-1")
    val raw = Base64.getDecoder.decode(s.ciphertext)
    raw(3) = (raw(3) ^ 1).toByte
    assert(Sealing.open(s.copy(ciphertext = Base64.getEncoder.encodeToString(raw)), key, "aff-1").isLeft)
    assert(Sealing.open(s, key, "aff-2").isLeft)
    assert(Sealing.open(s, Sealing.newKey(), "aff-1").isLeft)
  }

  test("two seals of one identity differ: a fresh nonce each") {
    val a = Sealing.seal(identity, key, "aff-1")
    val b = Sealing.seal(identity, key, "aff-1")
    assertNotEquals(a.nonce, b.nonce)
    assertNotEquals(a.ciphertext, b.ciphertext)
  }

  test("the erased value opens to nothing") {
    assertEquals(Sealing.open(Sealed.erased, key, "aff-1"), Left("erased"))
    assert(Sealed.erased.isErased)
  }

  test("the email digest ignores case and surrounding space, and depends on the key") {
    val k = Sealing.newKey()
    assertEquals(Digest.of(" Ada@Example.com ", k), Digest.of("ada@example.com", k))
    assertNotEquals(Digest.of("ada@example.com", k), Digest.of("ada@example.com", Sealing.newKey()))
    assertEquals(Digest.of("ada@example.com", k).length, 64)
  }
