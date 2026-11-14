package io.eitheror.casino.publicapi.wire

import io.eitheror.casino.contract.money.{Amounts, Currency}

import java.math.{BigDecimal as JBigDecimal, RoundingMode}

/**
 * The contract's `Money`: an exact decimal string in major units with two places (`^-?[0-9]+\.[0-9]{2}$`), from a
 * `Long` of minor units by the currency's exponent (research R12; original `mappers/money.ts`). A currency whose
 * exponent is not 2 cannot be written in that pattern without rounding, so it is a projection error, never served
 * rounded.
 */
object Money:
  final case class NotTwoPlaces(currency: String, exponent: Int):
    def message: String = s"currency $currency has $exponent minor digits; the contract's Money has two"

  def of(minor: Long, currency: String): Either[NotTwoPlaces, String] =
    Currency.from(currency).toOption.fold[Either[NotTwoPlaces, String]](Left(NotTwoPlaces(currency, -1))) { c =>
      val exp = Amounts.exponent(c)
      if exp != 2 then Left(NotTwoPlaces(currency, exp))
      else Right(JBigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString)
    }

  /** The original's `centsToMoney`: minor units of a two-place currency. */
  def cents(minor: Long): String = JBigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString
