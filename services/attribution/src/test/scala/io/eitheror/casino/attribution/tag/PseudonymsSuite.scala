package io.eitheror.casino.attribution.tag

import com.thinkmorestupidless.ankka.testkit.LogCapturing

class PseudonymsSuite extends munit.FunSuite with LogCapturing:
  /** The documented vector: the key is the bytes 0x00 to 0x1f, base64 as the store keeps it. */
  private val key = Pseudonyms.keyBytes("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")

  test("a fixed key and player-1 give the documented digest, 43 characters, the same twice") {
    assertEquals(Pseudonyms.derive(key, "player-1"), "2QR3cBwraKJ0RAIFJteyulr7No_PDskVPVCuBQWrBgA")
    assertEquals(Pseudonyms.derive(key, "player-1").length, 43)
    assertEquals(Pseudonyms.derive(key, "player-1"), Pseudonyms.derive(key, "player-1"))
  }

  test("two networks' keys give different pseudonyms for one player; two players differ under one key") {
    val a = Pseudonyms.keyBytes(Pseudonyms.newKey())
    val b = Pseudonyms.keyBytes(Pseudonyms.newKey())
    assertNotEquals(Pseudonyms.derive(a, "player-1"), Pseudonyms.derive(b, "player-1"))
    assertNotEquals(Pseudonyms.derive(a, "player-1"), Pseudonyms.derive(a, "player-2"))
  }

  test("a new key is 32 bytes; the secret's name is the network's") {
    assertEquals(Pseudonyms.keyBytes(Pseudonyms.newKey()).length, 32)
    assertEquals(Pseudonyms.secretName("programme"), "network/programme/pseudonym-key")
  }
