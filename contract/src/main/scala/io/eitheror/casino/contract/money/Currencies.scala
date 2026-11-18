package io.eitheror.casino.contract.money

import scala.jdk.CollectionConverters.*

/**
 * Every ISO 4217 code the JDK knows with its exponent by `Amounts.exponent`, so a reader in another
 * language reads major units by the same rule the services write them (spec 027 FR-037). `sbt
 * contract/schemas` writes it as `contract/schemas/currencies.v1.json`; the player SDK keeps a copy,
 * which CI holds to this one.
 */
object Currencies:
  /** Code to exponent, sorted by code. A code the JDK gives no digits (`XAU`, `XXX`) is 2, as the wallet treats it. */
  lazy val table: Vector[(String, Int)] =
    java.util.Currency.getAvailableCurrencies.asScala.toVector
      .map(_.getCurrencyCode)
      .distinct
      .sorted
      .map(code => code -> Amounts.exponent(Currency.unsafe(code)))

  /** The table as one JSON object, a code per line. */
  def render(): String =
    table.map((code, exp) => s"""  "$code": $exp""").mkString("{\n", ",\n", "\n}\n")
