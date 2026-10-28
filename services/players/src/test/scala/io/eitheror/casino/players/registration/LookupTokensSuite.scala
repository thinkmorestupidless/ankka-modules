package io.eitheror.casino.players.registration

/** FR-036, research R9: what two registrations share, as a keyed digest. */
class LookupTokensSuite extends munit.FunSuite:

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
  }

  test("two keys give two tokens, and the identity and device tokens of one text differ") {
    assertNotEquals(LookupTokens.device("k1", "fp"), LookupTokens.device("k2", "fp"))
    assertNotEquals(LookupTokens.device("k", "a|b|c"), LookupTokens.identity("k", "a", "b", "c"))
  }

  test("a token holds none of what it was made from") {
    val token = LookupTokens.identity("k", "Ann", "Smith", "1990-01-01").get
    assert(token.matches("[0-9a-f]{64}"), token)
    assert(!token.contains("ann") && !token.contains("1990"))
  }
