package io.eitheror.casino.wallet.api

import io.eitheror.casino.contract.money.Currency

/** An operator's amount is read by the currency's exponent, as the reads write it (spec 027 FR-058; review). */
class MajorSuite extends munit.FunSuite:
  private def c(code: String) = Currency.from(code).fold(m => fail(m), identity)

  test("two decimals for EUR, a sign kept, more decimals refused") {
    assertEquals(Major.parse("10.00", c("EUR")), Right(1000L))
    assertEquals(Major.parse("10.5", c("EUR")), Right(1050L))
    assertEquals(Major.parse("-2.50", c("EUR")), Right(-250L))
    assert(Major.parse("1.234", c("EUR")).isLeft)
  }

  test("no decimals for CLP and PYG: what an operator reads is what is credited") {
    assertEquals(Major.parse("5000", c("CLP")), Right(5000L))
    assertEquals(Major.parse("5000", c("PYG")), Right(5000L))
    assert(Major.parse("50.00", c("CLP")).isLeft)
  }

  test("three decimals for KWD, as the balance shows it") {
    assertEquals(Major.parse("1.234", c("KWD")), Right(1234L))
    assertEquals(Major.parse("1.2", c("KWD")), Right(1200L))
  }

  test("the reads and the parse agree for every exponent") {
    for (code, minor) <- Seq("EUR" -> 123456L, "CLP" -> 5000L, "KWD" -> 1234L, "JPY" -> 77L) do
      val currency = c(code)
      assertEquals(Major.parse(io.eitheror.casino.contract.money.Amounts.render(minor, currency), currency), Right(minor), code)
  }

  test("an amount beyond a Long is refused, not wrapped: 15 digits of a currency of 4 decimals") {
    assert(Major.parse("999999999999999.9999", c("CLF")).isLeft)
    assertEquals(Major.parse("1.2345", c("CLF")), Right(12345L))
  }

  test("anything else names the amount") {
    assert(Major.parse("ten", c("EUR")).left.exists(_.contains("'ten'")))
    assert(Major.parse("", c("EUR")).isLeft)
  }
