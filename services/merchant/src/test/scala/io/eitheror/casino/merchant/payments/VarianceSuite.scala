package io.eitheror.casino.merchant.payments

import io.eitheror.casino.contract.money.Currency

/** FR-026, case for case of the original's `CashierSettlementVarianceSpec`. */
class VarianceSuite extends munit.FunSuite:

  private val EUR     = Currency.unsafe("EUR")
  private val invoice = 10000L // 100.00

  private def under(amount: String, currency: Option[String] = Some("EUR")) =
    Some(Variance(Variance.Underpayment, Some("0.0012"), Some(amount).filter(_.nonEmpty), currency))

  test("an underpayment credits the received value") {
    assertEquals(Variance.credited(invoice, EUR, under("49.50")), Right(4950L))
  }

  test("an underpayment equal to the invoice is accepted: equal is not over") {
    assertEquals(Variance.credited(invoice, EUR, under("100.00")), Right(10000L))
  }

  test("an overpayment credits the invoiced amount and only records the surplus") {
    val over = Some(Variance(Variance.Overpayment, Some("0.002"), Some("130.00"), Some("EUR")))
    assertEquals(Variance.credited(invoice, EUR, over), Right(invoice))
  }

  test("a capture without a variance credits the invoice") {
    assertEquals(Variance.credited(invoice, EUR, None), Right(invoice))
  }

  for (description, v) <- Vector(
      "a zero valuation — a captured payment with an empty credit loses the crypto silently" -> under("0.00"),
      "a negative valuation"                                                                -> under("-5.00"),
      "a valuation that is not a decimal"                                                   -> under("49,50"),
      "a missing valuation"                                                                 -> under(""),
      "a valuation above the invoiced amount — that is a PSP fault, not a shortfall"         -> under("100.01"),
      "a valuation in a different currency — it would be credited at face value into the wrong wallet" -> under("49.50", Some("USD")),
      "a valuation with no currency"                                                        -> under("49.50", None),
      "a valuation finer than the currency's minor unit"                                    -> under("49.505")
    )
  do
    test(s"refuse $description") {
      assert(Variance.credited(invoice, EUR, v).isLeft, v.toString)
    }

  test("refuse a variance kind it does not know") {
    assertEquals(Variance.fromFields(Map("variance" -> "SHORTFALL").get), Left("unknown settlement variance 'SHORTFALL'"))
    assertEquals(Variance.fromFields(Map.empty[String, String].get), Right(None))
    assertEquals(Variance.fromFields(Map("variance" -> "OVERPAYMENT", "receivedConvertAmount" -> "1.00").get).map(_.map(_.kind)), Right(Some("OVERPAYMENT")))
  }
