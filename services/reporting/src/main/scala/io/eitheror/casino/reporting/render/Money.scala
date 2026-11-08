package io.eitheror.casino.reporting.render

import io.eitheror.casino.contract.money.{Amounts, Currency}

/** Amounts at the boundary (research R16): minor units as the original's decimal strings by the currency's places. */
object Money:
  @scala.annotation.targetName("applyCurrency")
  def apply(minor: Long, currency: Currency): String = Amounts.render(minor, currency)

  def apply(minor: Long, currency: String): String =
    Currency.from(currency).fold(_ => Amounts.render(minor, Currency.unsafe("XXX")), Amounts.render(minor, _))

  /** An email cut as the original's tables cut it: `n` characters and `…`. */
  def truncated(email: String, n: Int): String = if email.length > n then email.take(n) + "…" else email
