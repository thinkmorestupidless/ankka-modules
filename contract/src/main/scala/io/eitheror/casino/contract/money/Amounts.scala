package io.eitheror.casino.contract.money

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader

import java.math.{BigDecimal as JBigDecimal, RoundingMode}
import scala.util.Try

/** Why a decimal amount was refused: not a whole number of the currency's minor units, or beyond a `Long`. */
final case class AmountNotWhole(amount: String):
  def message: String = s"the amount '$amount' is not a whole number of minor units"

/**
 * Amounts at a boundary (spec 001 FR-026). Inside every service an amount is a `Long` of minor units;
 * where a caller or a frontend speaks a decimal string in major units (a provider's callback, the
 * cashier's `amount`), `parse` reads it by the currency's exponent and `render` writes it back. Hoisted
 * from game-provider (spec 004 research R11) so `merchant` and `payouts` share one rule.
 */
object Amounts:
  private val Whole = "-?[0-9]{1,19}".r

  /** Minor units per major unit, as ISO 4217 has it; 2 for a code the JDK does not know. */
  def exponent(currency: Currency): Int =
    Try(java.util.Currency.getInstance(currency.code).getDefaultFractionDigits).toOption.filter(_ >= 0).getOrElse(2)

  /** A decimal in major units as minor units, refused naming the amount when it is negative, not whole, or beyond a `Long`. */
  def parse(amount: String, currency: Currency): Either[AmountNotWhole, MinorUnits] =
    Try(JBigDecimal(amount.trim)).toOption
      .map(_.movePointRight(exponent(currency)))
      .filter(minor => minor.signum >= 0 && minor.stripTrailingZeros.scale <= 0)
      .flatMap(minor => Try(minor.longValueExact).toOption)
      .flatMap(minor => MinorUnits.from(minor).toOption)
      .toRight(AmountNotWhole(amount))

  /** Minor units as a decimal in major units: `9500` EUR is `"95.00"`, `-250` is `"-2.50"`, JPY `100` is `"100"`. */
  def render(minor: Long, currency: Currency): String =
    val exp = exponent(currency)
    JBigDecimal.valueOf(minor).movePointLeft(exp).setScale(exp, RoundingMode.UNNECESSARY).toPlainString

  private[money] def parseWhole(text: String): Option[Long] =
    if Whole.matches(text.trim) then text.trim.toLongOption else None

  /**
   * The token exactly as sent, accepted only as digits with an optional minus: a decimal point
   * (`1050.0`), an exponent (`1.05e3`) or a string (`"1050"`) is refused, because a boundary that
   * accepts any of them has accepted something other than a whole number of minor units.
   */
  private[money] def readWhole(in: JsonReader): Option[Long] =
    val raw = String(in.readRawValAsBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
    parseWhole(raw)
