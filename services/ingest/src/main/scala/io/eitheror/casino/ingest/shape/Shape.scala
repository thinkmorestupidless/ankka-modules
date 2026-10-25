package io.eitheror.casino.ingest.shape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.ingest.Json

import scala.jdk.CollectionConverters.*

/**
 * The public create into `merchant`'s command (004 `contracts/commands.md`): the key's sub-brand, never the
 * body's (FR-003), the request id given or minted, `amountCents` as `amount`, and every customer field as
 * the caller sent it (FR-008), which this service passes on and keeps nowhere.
 */
object Shape:
  val CustomerFields: Vector[String] = Vector("customerEmail", "customerFirstName", "customerLastName", "customerPhone",
    "customerMobile", "customerProvince", "customerCountry", "customerCity", "customerAddress", "customerPostCode", "customerState")

  def toCommand(subBrand: String, requestId: String, checked: Checked, body: JsonNode, clientIp: String): ObjectNode =
    val command = Json.obj("subBrandId" -> subBrand, "transactionId" -> requestId, "amount" -> checked.amount,
      "currency" -> checked.currency, "country" -> checked.country, "customerId" -> checked.customerId,
      "redirectUrl" -> checked.redirectUrl, "clientIp" -> clientIp)
    CustomerFields.foreach(f => Option(body.get(f)).filterNot(_.isNull).foreach(v => command.set[JsonNode](f, v)))
    command

  /** The player's address: the first `X-Forwarded-For` entry, else empty, never the gateway's (research R11). */
  def clientIp(forwardedFor: Option[String]): String =
    forwardedFor.flatMap(_.split(',').headOption).map(_.trim).getOrElse("")

  /** A submit's body is the player's choice of terminal and their entries: passed on as received. */
  def submitBody(body: JsonNode): JsonNode = body

  /** An abandon carries nothing. */
  def abandonBody: JsonNode = Json.obj()

  /** The names a body holds, for a test's and a log's sake: never its values. */
  def names(body: JsonNode): Vector[String] = body.properties.asScala.toVector.map(_.getKey)
