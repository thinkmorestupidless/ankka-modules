package io.eitheror.casino.pspgateway.crypto

import java.math.{BigDecimal as JBigDecimal, BigInteger}
import scala.util.Try

/**
 * Whether a crypto confirmation is short of the quote (FR-006, SC-005), as the original's
 * `morphosis.Underpaid` decides it (`morphosis/adapter.go:46-56`): an exact rational comparison of
 * the coin amounts, never a float. An expected amount that is not a positive number, and a received
 * amount that is missing or not a number, both count as short, so a confirmation that cannot be read
 * is never credited.
 */
object Underpaid:

  def apply(expected: String, received: Option[String]): Boolean =
    (rational(expected), received.flatMap(rational)) match
      case (Some(exp), Some(got)) if exp.signum > 0 => compare(got, exp) < 0
      case _                                        => true

  /** Why it counts as short, for the log: none when it is not. */
  def reason(expected: String, received: Option[String]): Option[String] =
    rational(expected) match
      case None                       => Some(s"expected coin amount '$expected' is not a positive number")
      case Some(e) if e.signum <= 0   => Some(s"expected coin amount '$expected' is not a positive number")
      case Some(_) =>
        received.flatMap(rational) match
          case None                              => Some(s"received coin amount '${received.getOrElse("")}' is not a number")
          case Some(_) if apply(expected, received) => Some(s"received ${received.get} is less than the quoted $expected")
          case Some(_)                            => None

  /** A number as Go's `big.Rat.SetString` reads one: a decimal, with an exponent, or a fraction `a/b`. */
  private final case class Rational(num: BigInteger, den: BigInteger):
    def signum: Int = num.signum * den.signum

  private def rational(text: String): Option[Rational] =
    val t = text.trim
    if t.isEmpty then None
    else
      t.split('/') match
        case Array(a, b) =>
          for
            n <- Try(BigInteger(a.trim)).toOption
            d <- Try(BigInteger(b.trim)).toOption if d.signum != 0
          yield Rational(n, d)
        case Array(_) =>
          Try(JBigDecimal(t)).toOption.map { d =>
            if d.scale <= 0 then Rational(d.toBigIntegerExact, BigInteger.ONE)
            else Rational(d.unscaledValue, BigInteger.TEN.pow(d.scale))
          }
        case _ => None

  private def compare(a: Rational, b: Rational): Int =
    // Both denominators made positive, so cross-multiplying keeps the order.
    def norm(r: Rational) = if r.den.signum < 0 then Rational(r.num.negate, r.den.negate) else r
    val (x, y) = (norm(a), norm(b))
    x.num.multiply(y.den).compareTo(y.num.multiply(x.den))
