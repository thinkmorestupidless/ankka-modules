package io.eitheror.casino.pspgateway.money

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json

/** FR-006, research R9: each provider's form of the same minor units, never through a float. */
class RenderSuite extends munit.FunSuite:

  private val EUR = Currency.unsafe("EUR")
  private val JPY = Currency.unsafe("JPY")
  private val KWD = Currency.unsafe("KWD")

  test("1500 EUR is 15.00 for the %.2f providers (DreamzPay, Unity Finance, T365, Morphosis)") {
    assertEquals(Render.twoDecimals(1500, EUR), "15.00")
    assertEquals(Render.twoDecimals(1, EUR), "0.01")
    assertEquals(Render.twoDecimals(123456789, EUR), "1234567.89")
  }

  test("EuPaymentz's %d.%02d is the same digits for a two-decimal currency") {
    assertEquals(Render.dotted(1500, EUR), "15.00")
    assertEquals(Render.dotted(1505, EUR), "15.05")
  }

  test("a JSON number is written as Go writes the float64 of the same value (Digitarium, BounceMoney, Cozeni fiat)") {
    def written(minor: Long) = Json.render(Json.obj("amount" -> Render.decimalNumber(minor, EUR)))
    assertEquals(written(1500), """{"amount":15}""")
    assertEquals(written(1550), """{"amount":15.5}""")
    assertEquals(written(1555), """{"amount":15.55}""")
    assertEquals(written(1), """{"amount":0.01}""")
    assertEquals(written(100000000000L), """{"amount":1000000000}""", "never in exponent notation")
  }

  test("Gigadat's dollars are the decimal number") {
    assertEquals(Render.dollars(2599, Currency.unsafe("CAD")).toPlainString, "25.99")
  }

  test("a currency's own exponent is used: JPY has none, KWD three") {
    assertEquals(Render.twoDecimals(1500, JPY), "1500")
    assertEquals(Render.twoDecimals(1500, KWD), "1.500")
    assert(!Render.twoDecimalCurrency(JPY))
    assert(!Render.twoDecimalCurrency(KWD))
    assert(Render.twoDecimalCurrency(EUR))
  }

  test("a coin amount to 8 places (Cozeni's %.8f)") {
    assertEquals(Render.coin(java.math.BigDecimal("0.000123456789")), "0.00012346")
    assertEquals(Render.coin(java.math.BigDecimal("1")), "1.00000000")
  }

  test("an amount is whole minor units: 1500 is read, 1500.00 is refused as not whole") {
    assertEquals(Render.minorUnits("1500"), Right(1500L))
    assert(Render.minorUnits("1500.00").isLeft)
    assert(Render.minorUnits("15e2").isLeft)
    assert(Render.minorUnits("-1").isLeft)
    assert(Render.minorUnits("").isLeft)
    assert(Render.minorUnits("99999999999999999999").isLeft, "beyond a Long")
    assert(Render.minorUnits("1500.00").left.exists(_.contains("1500.00")), "the refusal names the amount")
  }
