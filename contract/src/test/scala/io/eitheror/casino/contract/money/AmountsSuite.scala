package io.eitheror.casino.contract.money

/** The decimal boundary rule (research R11 of spec 004), moved here from game-provider with its cases. */
class AmountsSuite extends munit.FunSuite:

  private val EUR = Currency.unsafe("EUR")
  private val JPY = Currency.unsafe("JPY")

  test("a decimal string in major units becomes minor units") {
    assertEquals(Amounts.parse("10.50", EUR).map(_.value), Right(1050L))
    assertEquals(Amounts.parse("10.5", EUR).map(_.value), Right(1050L))
    assertEquals(Amounts.parse("0", EUR).map(_.value), Right(0L))
    assertEquals(Amounts.parse("10", EUR).map(_.value), Right(1000L))
  }

  test("an amount finer than the currency's minor unit is refused naming it") {
    assertEquals(Amounts.parse("10.505", EUR), Left(AmountNotWhole("10.505")))
    assert(Amounts.parse("10.505", EUR).left.toOption.get.message.contains("'10.505'"))
    assertEquals(Amounts.parse("1.5", JPY), Left(AmountNotWhole("1.5")))
  }

  test("a negative, non-numeric or beyond-a-Long amount is refused") {
    assert(Amounts.parse("-1.00", EUR).isLeft)
    assert(Amounts.parse("ten", EUR).isLeft)
    assertEquals(Amounts.parse("92233720368547758.08", EUR), Left(AmountNotWhole("92233720368547758.08")))
    assertEquals(Amounts.parse("92233720368547758.07", EUR).map(_.value), Right(Long.MaxValue))
  }

  test("the currency's exponent decides both directions") {
    assertEquals(Amounts.parse("100", JPY).map(_.value), Right(100L))
    assertEquals(Amounts.render(100, JPY), "100")
  }

  test("an amount renders to the exponent, negative allowed") {
    assertEquals(Amounts.render(9500, EUR), "95.00")
    assertEquals(Amounts.render(-250, EUR), "-2.50")
    assertEquals(Amounts.render(0, EUR), "0.00")
    assertEquals(Amounts.render(Long.MaxValue, EUR), "92233720368547758.07")
  }
