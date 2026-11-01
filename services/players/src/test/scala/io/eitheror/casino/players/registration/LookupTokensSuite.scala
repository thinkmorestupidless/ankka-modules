package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/** FR-036, research R9, and spec 011 FR-035: what two registrations share, as a keyed digest. */
class LookupTokensSuite extends munit.FunSuite with LogCapturing:

  test("names and a date of birth differing only in case and space share a token") {
    assertEquals(LookupTokens.identity("k", " Ann ", "SMITH", "1990-01-01"), LookupTokens.identity("k", "ann", "smith", "1990-01-01"))
    assertNotEquals(LookupTokens.identity("k", "ann", "smith", "1990-01-01"), LookupTokens.identity("k", "ann", "smith", "1990-01-02"))
  }

  test("a missing part is no token") {
    assertEquals(LookupTokens.identity("k", "", "smith", "1990-01-01"), None)
    assertEquals(LookupTokens.identity("k", "ann", " ", "1990-01-01"), None)
    assertEquals(LookupTokens.identity("k", "ann", "smith", ""), None)
    assertEquals(LookupTokens.device("k", ""), None)
    assertEquals(LookupTokens.device("k", null), None)
    assertEquals(LookupTokens.emailVariant("k", "mike82@gmail.com"), None)
    assertEquals(LookupTokens.address("k", " "), None)
  }

  test("two email variants share a token; an address differing only in case and space shares one") {
    assertEquals(LookupTokens.emailVariant("k", "Ada.Lovelace+promo@Gmail.com"), LookupTokens.emailVariant("k", "adalovelace7@gmail.com"))
    assertNotEquals(LookupTokens.emailVariant("k", "ada.lovelace@gmail.com"), LookupTokens.emailVariant("k", "grace.hopper@gmail.com"))
    assertEquals(LookupTokens.address("k", " 2001:DB8::1 "), LookupTokens.address("k", "2001:db8::1"))
    assertNotEquals(LookupTokens.address("k", "192.0.2.1"), LookupTokens.address("k", "192.0.2.2"))
  }

  test("two keys give two tokens, and the tokens of one text differ by signal") {
    assertNotEquals(LookupTokens.device("k1", "fp"), LookupTokens.device("k2", "fp"))
    assertNotEquals(LookupTokens.device("k", "a|b|c"), LookupTokens.identity("k", "a", "b", "c"))
    assertNotEquals(LookupTokens.address("k", "192.0.2.1"), LookupTokens.device("k", "192.0.2.1"))
  }

  test("a token holds none of what it was made from") {
    val token = LookupTokens.identity("k", "Ann", "Smith", "1990-01-01").get
    assert(token.matches("[0-9a-f]{64}"), token)
    assert(!token.contains("ann") && !token.contains("1990"))
    val variant = LookupTokens.emailVariant("k", "ada.lovelace@gmail.com").get
    assert(variant.matches("[0-9a-f]{64}") && !variant.contains("lovelace"), variant)
    assert(LookupTokens.address("k", "192.0.2.1").get.matches("[0-9a-f]{64}"))
  }

  test("a registration's tokens: each signal it presents, tagged, in one order") {
    val tagged = LookupTokens.of("k", "Ann", "Smith", "1990-01-01", Some("fp"), "ada.lovelace@gmail.com", Some("192.0.2.1"))
    assertEquals(tagged.map(LookupTokens.split).map(_._1),
      Vector(LookupTokens.Identity, LookupTokens.Device, LookupTokens.EmailVariant, LookupTokens.Address))
    assertEquals(LookupTokens.of("k", "", "", "", None, "p-1234@example.test", None), Vector.empty)
  }
