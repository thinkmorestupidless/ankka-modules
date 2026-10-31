package io.eitheror.casino.markets

/**
 * What `markets` is told at start (spec 023 R7), from the brand's values as the modules tool renders
 * them: the categories and currencies a draft may name (FR-013), the limits a book opens with when a
 * draft sets none (FR-014), and the taker fee rate in basis points (FR-015). No secret.
 */
final case class Settings(
    brand: String,
    categories: Vector[String],
    currencies: Vector[String],
    maxOrderCost: Long,
    maxPosition: Long,
    takerFeeBps: Int = 0
)

object Settings:
  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    def named(name: String): Either[String, String] =
      env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$name: not set; the brand's values name it (spec 023 R7)")
    def list(name: String): Either[String, Vector[String]] =
      named(name).map(_.split(',').toVector.map(_.trim).filter(_.nonEmpty)).filterOrElse(_.nonEmpty, s"$name: names nothing")
    def positive(name: String): Either[String, Long] =
      named(name).flatMap(v => v.toLongOption.filter(_ > 0).toRight(s"$name: '$v' is not a positive whole number"))
    val fee = env.get("PREDICTIONS_TAKER_FEE_BPS").map(_.trim).filter(_.nonEmpty).fold(Right(0)) { v =>
      v.toIntOption.filter(b => b >= 0 && b <= 10_000).toRight(s"PREDICTIONS_TAKER_FEE_BPS: '$v' is not a rate from 0 to 10000 basis points")
    }
    for
      brand      <- named("CASINO_BRAND")
      categories <- list("PREDICTIONS_CATEGORIES")
      currencies <- list("PREDICTIONS_CURRENCIES")
      _          <- currencies.find(c => !c.matches("[A-Z]{3}")).map(c => s"PREDICTIONS_CURRENCIES: '$c' is not a currency code").toLeft(())
      cost       <- positive("PREDICTIONS_MAX_ORDER_COST")
      position   <- positive("PREDICTIONS_MAX_POSITION")
      bps        <- fee
    yield Settings(brand, categories, currencies, cost, position, bps)
