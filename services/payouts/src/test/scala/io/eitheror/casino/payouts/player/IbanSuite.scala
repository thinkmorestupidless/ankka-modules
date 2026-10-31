package io.eitheror.casino.payouts.player

/** The original's `IbanValidatorSpec` cases (R13). */
class IbanSuite extends munit.FunSuite:
  test("valid IBANs across the SEPA countries in scope"):
    val valid = Vector("DE89370400440532013000", "GB29NWBK60161331926819", "FR1420041010050500013M02606",
      "NL91ABNA0417164300", "IE29AIBK93115212345678", "MT84MALT011000012345MTLCAST001S")
    valid.foreach(iban => assert(Iban.isValid(iban), iban))

  test("the conventional four-character groups, and lower case"):
    assert(Iban.isValid("DE89 3704 0044 0532 0130 00"))
    assert(Iban.isValid("de89370400440532013000"))

  test("check digits that do not match"):
    assert(!Iban.isValid("DE89370400440532013010"))
    assert(!Iban.isValid("DE00370400440532013000"))

  test("structurally malformed input, punctuation not stripped"):
    Vector("", "DE89", "1289370400440532013000", "DEX9370400440532013000", "DE89-3704-0044-0532-0130-00")
      .foreach(iban => assert(!Iban.isValid(iban), s"'$iban'"))

  test("longer than 34 characters"):
    assert(!Iban.isValid("DE89" + "3" * 31))

  test("normalise strips whitespace and upper-cases; the last four of the normal form"):
    assertEquals(Iban.normalise(" de89 3704 0044 0532 0130 00 "), "DE89370400440532013000")
    assertEquals(Iban.lastFour("de89 3704 0044 0532 0130 00"), "3000")
