package io.eitheror.casino.pspgateway.merchant

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.http.{Neighbour, Reply}
import io.eitheror.casino.pspgateway.providers.Credential
import io.eitheror.casino.pspgateway.sessions.Provider

import scala.jdk.CollectionConverters.*

/** Why a read from `merchant` gave nothing (contracts/merchant.md §4). */
enum Unread:
  /** `404`: `merchant` holds none. */
  case None
  /** `503`, any other answer, or no answer: `merchant` could not say. */
  case Unavailable(reason: String)

/** A withdrawal-capable terminal as `merchant`'s brand listing gives it (non-secret values only). */
final case class Terminal(
    id: String,
    subBrandId: String,
    gateway: String,
    scheme: String,
    name: String,
    status: String,
    supportsDeposit: Boolean,
    supportsWithdrawal: Boolean,
    requiresPayoutDestination: Boolean,
    properties: Map[String, String]
)

/**
 * The seam to `merchant`'s credential store (research R7). Every read is made at the moment of use and
 * never cached; the values are returned for the caller's scope and held in no field (FR-018, FR-022).
 */
trait MerchantClient:
  def credential(subBrandId: String, provider: Provider): Either[Unread, Credential]
  def terminalProperties(terminalId: String): Either[Unread, Map[String, String]]
  def brandTerminals(brand: String): Either[Unread, Vector[Terminal]]

object MerchantClient:
  /** `merchant` in this project, by identity; the provider named in the catalogue's upper case. */
  def http(services: ServiceClients, name: String): MerchantClient = HttpMerchant(Neighbour(services, name))

  private[merchant] def read[A](reply: Reply)(decode: JsonNode => Option[A]): Either[Unread, A] = reply match
    case Reply.Answered(200, Some(body), _) =>
      decode(body).toRight(Unread.Unavailable("merchant answered what cannot be read"))
    case Reply.Answered(404, _, _)    => Left(Unread.None)
    case Reply.Answered(s, _, _)      => Left(Unread.Unavailable(s"merchant answered $s"))
    case Reply.Unreachable(reason)    => Left(Unread.Unavailable(reason))

  /** An object's value fields as text; nested values are dropped. */
  private[merchant] def strings(node: JsonNode): Map[String, String] =
    Option(node).filter(_.isObject).map(_.properties.asScala.toVector.collect {
      case e if e.getValue.isValueNode && !e.getValue.isNull => e.getKey -> e.getValue.asText
    }.toMap).getOrElse(Map.empty)

  private[merchant] def terminal(t: JsonNode): Option[Terminal] =
    for id <- t.text("id") yield Terminal(
      id, t.text("subBrandId").getOrElse(""), t.text("gateway").getOrElse(""), t.text("scheme").getOrElse(""),
      t.text("name").getOrElse(id), t.text("status").getOrElse(""), t.bool("supportsDeposit").getOrElse(false),
      t.bool("supportsWithdrawal").getOrElse(false), t.bool("requiresPayoutDestination").getOrElse(false),
      strings(t.get("properties")))

private final class HttpMerchant(merchant: Neighbour) extends MerchantClient:
  import MerchantClient.*

  def credential(subBrandId: String, provider: Provider): Either[Unread, Credential] =
    read(merchant.get(s"/internal/v1/sub-brands/${Neighbour.segment(subBrandId)}/psp-credentials/${provider.wire}"))(b =>
      Option(b.get("credentials")).filter(_.isObject).map(c => Credential(strings(c))))

  def terminalProperties(terminalId: String): Either[Unread, Map[String, String]] =
    read(merchant.get(s"/internal/v1/terminals/${Neighbour.segment(terminalId)}/properties"))(b =>
      Option(b.get("properties")).filter(_.isObject).map(strings))

  def brandTerminals(brand: String): Either[Unread, Vector[Terminal]] =
    read(merchant.get(s"/internal/v1/brands/${Neighbour.segment(brand)}/terminals"))(b =>
      Option(b.get("terminals")).filter(_.isArray).map(_.elements.asScala.toVector.flatMap(terminal)))
