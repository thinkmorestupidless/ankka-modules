package io.eitheror.casino.gameprovider.money

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.math.{BigDecimal as JBigDecimal, RoundingMode}
import scala.util.Try

/** Why a provider amount was refused: not a whole number of the currency's minor units (FR-005). */
final case class AmountNotWhole(amount: String):
  def message: String = s"the amount '$amount' is not a whole number of minor units"

/**
 * The provider's decimal amounts at the boundary (FR-005, spec 001 FR-026). ST8 sends a decimal
 * string or number in major units; every amount inside this service is minor units as a `Long`, and
 * every balance answered is minor units rendered back to the currency's exponent.
 */
object Amounts:

  /** Minor units per major unit, as ISO 4217 has it; 2 for a code the JDK does not know. */
  def exponent(currency: Currency): Int =
    Try(java.util.Currency.getInstance(currency.code).getDefaultFractionDigits).toOption.filter(_ >= 0).getOrElse(2)

  /** A decimal in major units as minor units, refused naming the amount when it is not whole or is negative. */
  def parse(amount: String, currency: Currency): Either[AmountNotWhole, MinorUnits] =
    Try(JBigDecimal(amount.trim)).toOption
      .map(_.movePointRight(exponent(currency)))
      .filter(minor => minor.signum >= 0 && minor.stripTrailingZeros.scale <= 0)
      .flatMap(minor => Try(minor.longValueExact).toOption)
      .flatMap(minor => MinorUnits.from(minor).toOption)
      .toRight(AmountNotWhole(amount))

  /** A field of a callback: a decimal string or a number; absent is zero, as the original read it. */
  def field(body: JsonNode, name: String, currency: Currency): Either[AmountNotWhole, MinorUnits] =
    Option(body.get(name)).filterNot(_.isNull) match
      case None                                   => Right(MinorUnits.unsafe(0))
      case Some(n) if n.isNumber || n.isTextual   => parse(if n.isNumber then n.decimalValue.toPlainString else n.asText, currency)
      case Some(n)                                => Left(AmountNotWhole(n.toString))

  /** Minor units as the provider reads them: `9500` EUR is `"95.00"`, `-250` is `"-2.50"`, JPY `100` is `"100"`. */
  def render(minor: Long, currency: Currency): String =
    val exp = exponent(currency)
    JBigDecimal.valueOf(minor).movePointLeft(exp).setScale(exp, RoundingMode.UNNECESSARY).toPlainString
