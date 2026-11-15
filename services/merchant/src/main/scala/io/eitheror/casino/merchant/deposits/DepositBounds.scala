package io.eitheror.casino.merchant.deposits

import io.eitheror.casino.contract.money.{Amounts, Currency, MinorUnits}
import io.eitheror.casino.merchant.hierarchy.DepositBound
import io.eitheror.casino.merchant.terminals.catalogue.Catalogue

/** Which bound a deposit broke, as the submit answers it: the method, the currency and the bound, in minor units. */
final case class BrokenBound(method: String, currency: String, kind: String, bound: Long):
  def code: String = if kind == BrokenBound.Minimum then DepositBounds.BelowMinimum else DepositBounds.AboveMaximum

  def rendered: String = Currency.from(currency).toOption.map(c => s"${Amounts.render(bound, c)} ${c.code}").getOrElse(s"$bound $currency")

  def message: String =
    if kind == BrokenBound.Minimum then s"the least a deposit by $method may be is $rendered"
    else s"the most a deposit by $method may be is $rendered"

object BrokenBound:
  val Minimum = "minimum"
  val Maximum = "maximum"

/**
 * A sub-brand's deposit bounds (spec 030 H3; spec 004 FR-029c): the least and the most one deposit by a
 * payment method may be, in one currency, in minor units. The original kept a brand's bounds per
 * currency and refused a deposit outside them before any provider was asked
 * (`DepositPreflightRoutes.scala`: below when `amount < min`, above when `amount > max`, both bounds
 * inclusive); here they are the sub-brand's, per method as well, and the deposit workflow applies them.
 *
 * A method is the catalogue's category of the terminal's scheme (`Card`, `Bank Transfer`, ... as
 * `card`, `bank_transfer`), else the scheme itself, so one bound covers every card scheme and gateway.
 */
object DepositBounds:
  val BelowMinimum = "DEPOSIT_BELOW_MINIMUM"
  val AboveMaximum = "DEPOSIT_ABOVE_MAXIMUM"

  private val Method = "[a-z0-9_]{1,64}".r

  private def slug(s: String): String = s.trim.toLowerCase.replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")

  /** The method a terminal of a gateway's scheme is: the catalogue's category, else the scheme. */
  def methodOf(gateway: String, scheme: String): String =
    Catalogue.gateways.get(gateway.trim.toUpperCase).flatMap(_.schemes.find(_.pspId.equalsIgnoreCase(scheme.trim)))
      .map(s => slug(s.category)).getOrElse(slug(scheme))

  /** Every method the catalogue knows, for the page that sets the bounds. */
  val methods: Vector[String] = Catalogue.gateways.values.flatMap(_.schemes.map(s => slug(s.category))).toVector.distinct.sorted

  /**
   * The bounds as they are kept, or why not: a method's name, an ISO 4217 currency, at least one of the
   * two, each a positive whole number of minor units, the least no more than the most, and one entry per
   * method and currency. Sorted, so that the same set is the same state.
   */
  def validate(bounds: Vector[DepositBound]): Either[String, Vector[DepositBound]] =
    val problems = bounds.iterator.map { b =>
      if !Method.matches(b.method) then Some(s"'${b.method}' is not a payment method")
      else if Currency.from(b.currency).isLeft then Some(s"'${b.currency}' is not a currency")
      else if b.minimum.isEmpty && b.maximum.isEmpty then Some(s"the bounds for ${b.method} in ${b.currency} name neither a minimum nor a maximum")
      else if (b.minimum ++ b.maximum).exists(v => v <= 0 || MinorUnits.from(v).isLeft) then
        Some(s"the bounds for ${b.method} in ${b.currency} must be positive whole numbers of minor units")
      else if b.minimum.zip(b.maximum).exists(_ > _) then Some(s"the minimum for ${b.method} in ${b.currency} is above its maximum")
      else None
    }.collectFirst { case Some(p) => p }
    val duplicate = bounds.groupBy(b => (b.method, b.currency)).collectFirst { case ((m, c), bs) if bs.size > 1 => s"$m in $c is bounded twice" }
    problems.orElse(duplicate).toLeft(bounds.sortBy(b => (b.method, b.currency)))

  /** The bound a deposit of `amount` minor units of `currency` by `method` breaks, if any; no bound for the pair takes any amount. */
  def broken(bounds: Vector[DepositBound], method: String, currency: String, amount: Long): Option[BrokenBound] =
    bounds.find(b => b.method == method && b.currency == currency).flatMap { b =>
      b.minimum.filter(amount < _).map(BrokenBound(method, currency, BrokenBound.Minimum, _))
        .orElse(b.maximum.filter(amount > _).map(BrokenBound(method, currency, BrokenBound.Maximum, _)))
    }
