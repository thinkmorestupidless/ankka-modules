package io.eitheror.casino.merchant.payments

import io.eitheror.casino.contract.money.{Amounts, Currency}

import scala.util.Try

/**
 * What a provider reported about a capture whose settled amount differs from the invoice (FR-026; the
 * original's `SettlementVariance`): the kind, and what arrived on-chain with the provider's valuation
 * of it, as the provider's strings.
 */
final case class Variance(
    kind: String,
    receivedCryptoAmount: Option[String],
    receivedConvertAmount: Option[String],
    receivedConvertCurrency: Option[String]
)

object Variance:
  val Underpayment = "UNDERPAYMENT"
  val Overpayment  = "OVERPAYMENT"

  /** The variance a capture body names, none, or a kind the cashier does not know (refused). */
  def fromFields(field: String => Option[String]): Either[String, Option[Variance]] =
    field("variance") match
      case None => Right(None)
      case Some(kind) if kind == Underpayment || kind == Overpayment =>
        Right(Some(Variance(kind, field("receivedCryptoAmount"), field("receivedConvertAmount"), field("receivedConvertCurrency"))))
      case Some(other) => Left(s"unknown settlement variance '$other'")

  /**
   * What the wallet is credited for a capture with this variance, in minor units of the payment's
   * currency, or why the capture is refused. An overpayment credits the invoice and only records the
   * surplus. An underpayment credits the provider's valuation, checked before it is trusted, because
   * the credit cannot be taken back: a positive decimal, no more than the invoice (equal is not over),
   * carrying the payment's currency (`settlementProps` in the original).
   */
  def credited(invoice: Long, currency: Currency, variance: Option[Variance]): Either[String, Long] =
    variance match
      case None                                     => Right(invoice)
      case Some(v) if v.kind != Underpayment        => Right(invoice)
      case Some(v) =>
        val raw       = v.receivedConvertAmount.getOrElse("")
        val valuation = Try(BigDecimal(raw)).toOption
        valuation match
          case None                => Left(s"underpayment valuation '$raw' is not a decimal amount")
          case Some(d) if d <= 0   => Left(s"underpayment valuation $d is not positive")
          case Some(d) if d > BigDecimal(Amounts.render(invoice, currency)) =>
            Left(s"underpayment valuation $d exceeds the invoiced amount ${Amounts.render(invoice, currency)}")
          case Some(_) =>
            v.receivedConvertCurrency match
              case None                          => Left("underpayment valuation carries no currency")
              case Some(c) if c != currency.code => Left(s"underpayment valuation currency $c does not match the payment currency ${currency.code}")
              case Some(_) =>
                Amounts.parse(raw, currency).map(_.value).left.map(_ => s"underpayment valuation '$raw' is not a decimal amount")
