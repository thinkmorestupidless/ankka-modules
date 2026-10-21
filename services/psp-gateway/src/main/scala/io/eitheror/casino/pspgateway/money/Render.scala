package io.eitheror.casino.pspgateway.money

import io.eitheror.casino.contract.money.{Amounts, Currency}

import java.math.{BigDecimal as JBigDecimal, RoundingMode}

/**
 * Minor units rendered the way each provider expects (FR-006, research R9), from the one `Long` the
 * gateway holds. The original divided by 100 into a float and formatted it; here every form is exact,
 * by the currency's own exponent (`contract.money.Amounts`), so a two-decimal currency renders as the
 * original did and no float ever carries money.
 */
object Render:

  /** `"15.00"` for 1,500 EUR: DreamzPay, Unity Finance, T365, Morphosis (`%.2f`). */
  def twoDecimals(minor: Long, currency: Currency): String = Amounts.render(minor, currency)

  /** `"15.00"` for 1,500 EUR: EuPaymentz's `%d.%02d`, the same digits as `twoDecimals` for a two-decimal currency. */
  def dotted(minor: Long, currency: Currency): String = Amounts.render(minor, currency)

  /**
   * A JSON number as Go's encoder writes a float64 of the same value: `15` for 1,500 EUR, `15.5` for
   * 1,550 (Digitarium, BounceMoney, Cozeni's fiat, Gigadat's dollars).
   */
  def decimalNumber(minor: Long, currency: Currency): JBigDecimal =
    val d = JBigDecimal.valueOf(minor).movePointLeft(Amounts.exponent(currency)).stripTrailingZeros
    if d.scale < 0 then d.setScale(0) else d

  /** Gigadat's `amount`: dollars as a number. */
  def dollars(minor: Long, currency: Currency): JBigDecimal = decimalNumber(minor, currency)

  /** A coin amount to 8 places, as Cozeni's `requestAmount` and payout amounts are sent (`%.8f`). */
  def coin(amount: JBigDecimal): String = amount.setScale(8, RoundingMode.HALF_EVEN).toPlainString

  private val Whole = "[0-9]{1,19}".r

  /**
   * An amount as a caller sends it (`merchant`'s `amount`, `payouts`'s): a whole, non-negative number of
   * minor units, written as digits. `1500.00`, `15e2` and `"abc"` are refused (contracts/merchant.md
   * §1's `bad_request`): a boundary that accepts them has accepted something other than minor units.
   */
  def minorUnits(text: String): Either[String, Long] =
    Some(text.trim).filter(Whole.matches).flatMap(_.toLongOption)
      .toRight(s"the amount '$text' is not a whole number of minor units")

  /** Whether a currency has two decimal places: Digitarium takes no other (research R9, FR-006). */
  def twoDecimalCurrency(currency: Currency): Boolean = Amounts.exponent(currency) == 2
