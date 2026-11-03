package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The email variant's normalisation (spec 011 FR-035, research R9), with the original's cases
 * (`MultiAccountDetectorSpec`, `normalizeEmailLocal`).
 */
class EmailVariantSuite extends munit.FunSuite with LogCapturing:

  test("lower-cased, the +tag and the digits dropped, the domain kept") {
    assertEquals(NormalisedEmail.of("John.Smith77+promo@Example.COM"), Some("john.smith" -> "example.com"))
  }

  test("the dots dropped on Gmail only") {
    assertEquals(NormalisedEmail.of("j.o.h.n.smith1@googlemail.com"), Some("johnsmith" -> "googlemail.com"))
    assertEquals(NormalisedEmail.of("ada.lovelace@gmail.com"), NormalisedEmail.of("adalovelace7@gmail.com"))
    assertEquals(NormalisedEmail.of("ada.lovelace@gmail.com"), NormalisedEmail.of("ada.lovelace+promo@gmail.com"))
  }

  test("none for an all-digit local part or an address that does not parse") {
    assertEquals(NormalisedEmail.of("12345@gmail.com"), None)
    assertEquals(NormalisedEmail.of("not-an-email"), None)
    assertEquals(NormalisedEmail.of("a@b@c.com"), None)
    assertEquals(NormalisedEmail.of("adalovelace@"), None)
  }

  test("none when the normalised local part is shorter than six characters") {
    assertEquals(NormalisedEmail.of("mike82@gmail.com"), None)
    assertEquals(NormalisedEmail.of("mikeys82@gmail.com"), Some("mikeys" -> "gmail.com"))
  }

  test("the same local part on another domain is another variant") {
    assertNotEquals(LookupTokens.emailVariant("k", "johnsmith@gmail.com"), LookupTokens.emailVariant("k", "johnsmith@example.com"))
  }
