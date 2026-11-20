package io.eitheror.casino.wallet.api

import io.eitheror.casino.contract.money.Currency

/** An operator's adjustment amount, as text or minor units, is bounded so no balance can wrap (review S3). */
class MajorAmountSuite extends munit.FunSuite:

  private def ask(amount: String = "", minor: Option[Long] = None) =
    Major.adjustAmount(OperatorAdjustRequest(key = Some("k"), amount = amount, amountMinor = minor), Eur)

  private val Eur = Currency.unsafe("EUR")

  test("an amount up to 10^15 minor units either way is taken, as text or minor units") {
    assertEquals(ask("10000000000000.00"), Right(Major.MaxMinor))
    assertEquals(ask("-10000000000000"), Right(-Major.MaxMinor))
    assertEquals(ask(minor = Some(Major.MaxMinor)), Right(Major.MaxMinor))
    assertEquals(ask(minor = Some(-Major.MaxMinor)), Right(-Major.MaxMinor))
    assertEquals(ask("10.50"), Right(1050L))
  }

  test("an amount past the bound is refused, never wrapped: the 17 digits that overflowed, Long's extremes") {
    assert(ask("99999999999999999").isLeft)       // * 100 overflowed Long before
    assert(ask("10000000000000.01").isLeft)
    assert(ask(minor = Some(Major.MaxMinor + 1)).isLeft)
    assert(ask(minor = Some(Long.MaxValue)).isLeft)
    assert(ask(minor = Some(Long.MinValue)).isLeft)
    assert(Major.parse("92233720368547758", Eur).isLeft)
  }
