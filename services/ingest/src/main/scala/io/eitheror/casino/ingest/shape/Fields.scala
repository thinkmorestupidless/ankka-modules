package io.eitheror.casino.ingest.shape

import com.fasterxml.jackson.databind.JsonNode

/** The first field of the public create that fails its check (FR-007): its name, as the original spells it. */
final case class Refused(field: String)

/** A create whose fields passed: the amount in minor units, the strings as sent, the request id if given. */
final case class Checked(
    amount: Long,
    currency: String,
    customerId: String,
    country: String,
    redirectUrl: String,
    transactionId: Option[String]
)

/**
 * FR-007's checks over the public create body (contracts/routes.md), in the table's order, naming the first
 * field that fails. Nothing is defaulted, as the original defaulted (`amountCents` to `0`, `currency` to
 * `USD`): a missing field is refused here and nothing reaches `merchant` (plan.md *Divergences*).
 */
object Fields:
  val Order: Vector[String] = Vector("amountCents", "currency", "customerId", "country", "redirectUrl", "transactionId")

  def check(body: JsonNode, requestIdBound: Int): Either[Refused, Checked] =
    for
      amount      <- amountCents(body)
      currency    <- text(body, "currency")
      customerId  <- text(body, "customerId").filterOrElse(playerId, Refused("customerId"))
      country     <- text(body, "country")
      redirectUrl <- text(body, "redirectUrl")
      requestId   <- transactionId(body, requestIdBound)
    yield Checked(amount, currency, customerId, country, redirectUrl, requestId)

  /** A JSON integer above zero that fits a `Long`: `5000`, never `50.00`, `"5000"` or `0`. */
  private def amountCents(body: JsonNode): Either[Refused, Long] =
    Option(body.get("amountCents"))
      .filter(n => n.isIntegralNumber && n.canConvertToLong)
      .map(_.longValue)
      .filter(_ > 0)
      .toRight(Refused("amountCents"))

  private def text(body: JsonNode, field: String): Either[Refused, String] =
    Option(body.get(field)).filter(_.isTextual).map(_.asText).filter(_.trim.nonEmpty).toRight(Refused(field))

  /** `brand:player`, both parts non-empty: the brand is what the sub-brand's credit is checked against. */
  private def playerId(id: String): Boolean =
    id.split(":", 2) match
      case Array(brand, player) => brand.trim.nonEmpty && player.trim.nonEmpty
      case _                    => false

  /** Absent, or a string of at most the bound with no control character. */
  private def transactionId(body: JsonNode, bound: Int): Either[Refused, Option[String]] =
    Option(body.get("transactionId")).filterNot(_.isNull) match
      case None => Right(None)
      case Some(n) if n.isTextual && n.asText.nonEmpty && n.asText.length <= bound && !n.asText.exists(Character.isISOControl) =>
        Right(Some(n.asText))
      case _ => Left(Refused("transactionId"))
