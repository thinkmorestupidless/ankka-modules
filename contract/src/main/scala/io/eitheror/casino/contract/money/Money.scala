package io.eitheror.casino.contract.money

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

/**
 * An ISO 4217 currency code: three upper-case letters (FR-026, FR-028).
 *
 * The code alone; how many minor units a major unit has is the currency's, not this type's, because
 * no service converts: every amount stays in minor units from the edge to the ledger.
 */
opaque type Currency = String

object Currency:
  private val Code = "[A-Z]{3}".r

  def from(code: String): Either[String, Currency] =
    if Code.matches(code) then Right(code) else Left(s"'$code' is not an ISO 4217 currency code")

  def unsafe(code: String): Currency = from(code).fold(e => throw IllegalArgumentException(e), identity)

  extension (currency: Currency) def code: String = currency

  given JsonValueCodec[Currency] with
    def decodeValue(in: JsonReader, default: Currency): Currency =
      val text = in.readString(null)
      from(text).fold(in.decodeError, identity)
    def encodeValue(x: Currency, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Currency                             = null.asInstanceOf[Currency]

/** Why an amount was refused at a boundary: it was not a whole number of minor units (FR-026). */
final case class AmountNotMinorUnits(amount: String):
  def message: String = s"the amount '$amount' is not a whole number of minor units"

/**
 * A whole number of a currency's minor units, never negative: a stake, a deposit, a credit.
 *
 * `Long` end to end (constitution II; the original's #662 overflowed 32 bits). JSON is an integer;
 * a fraction, an exponent or a string is refused naming what was sent.
 */
opaque type MinorUnits = Long

object MinorUnits:
  def from(value: Long): Either[String, MinorUnits] =
    if value >= 0 then Right(value) else Left(s"an amount of $value minor units is negative")

  def unsafe(value: Long): MinorUnits = from(value).fold(e => throw IllegalArgumentException(e), identity)

  /** The boundary rule for text: digits only, as a whole number of minor units. */
  def parse(text: String): Either[AmountNotMinorUnits, MinorUnits] =
    Amounts.parseWhole(text).filter(_ >= 0).toRight(AmountNotMinorUnits(text))

  extension (amount: MinorUnits)
    def value: Long                            = amount
    def +(other: MinorUnits): MinorUnits       = Math.addExact(amount, other)
    def -(other: MinorUnits): SignedMinorUnits = SignedMinorUnits(Math.subtractExact(amount, other))

  given JsonValueCodec[MinorUnits] with
    def decodeValue(in: JsonReader, default: MinorUnits): MinorUnits =
      Amounts.readWhole(in).filter(_ >= 0).getOrElse(in.decodeError("an amount must be a whole, non-negative number of minor units"))
    def encodeValue(x: MinorUnits, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: MinorUnits                             = 0L

/** A signed whole number of minor units: a GGR, an adjustment, a balance after a correction. */
opaque type SignedMinorUnits = Long

object SignedMinorUnits:
  def apply(value: Long): SignedMinorUnits = value

  def parse(text: String): Either[AmountNotMinorUnits, SignedMinorUnits] =
    Amounts.parseWhole(text).toRight(AmountNotMinorUnits(text))

  extension (amount: SignedMinorUnits) def value: Long = amount

  given JsonValueCodec[SignedMinorUnits] with
    def decodeValue(in: JsonReader, default: SignedMinorUnits): SignedMinorUnits =
      Amounts.readWhole(in).getOrElse(in.decodeError("an amount must be a whole number of minor units"))
    def encodeValue(x: SignedMinorUnits, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: SignedMinorUnits                             = 0L

/** An amount in a named currency. */
final case class Money(minor: MinorUnits, currency: Currency)
