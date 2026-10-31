package io.eitheror.casino.payouts.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.{Json, Settings}
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.terminals.Terminal
import io.eitheror.casino.payouts.workflow.Payout

import java.time.Duration
import scala.util.Try

/** What a payout's initiation answered (R7's table), each read as the workflow acts on it. */
enum Initiation:
  case Completed(reference: Option[String])
  case Initiated(reference: Option[String])
  case AlreadyInitiated(reference: Option[String])
  case Rejected(reason: String)
  case InDoubt(reason: String)
  /** `409 initiation_in_progress`: the gateway claimed the request and has no answer yet. */
  case InProgress
  /** `400`, `403` or `502 provider_not_configured`: nothing reached a provider. */
  case Refused(status: Int, code: String)
  /** Unreachable, a 5xx, or a body it cannot read: the gateway may hold it. */
  case Unreadable(reason: String)

/** What a payout's status read answered (R8). */
enum PayoutStatus:
  case Completed(reference: Option[String])
  case Failed(reference: Option[String], reason: String)
  case Reversed(reference: Option[String], reason: String)
  case Pending
  case NoRecord
  case Unavailable(reason: String)

/** The payout the gateway is asked to make (005 contracts/payouts.md). */
final case class PayoutRequest(requestId: String, terminalId: String, amount: Long, currency: Currency, payout: Payout)

/** `payments`/`psp-gateway`, under the grant `payments` makes this service (R6, R7, R8). */
trait Gateway:
  def terminals(currency: Currency): Either[String, Vector[Terminal]]
  def initiate(request: PayoutRequest): Initiation
  def status(requestId: String): PayoutStatus

object Gateway:
  def http(services: ServiceClients, settings: Settings): Gateway =
    Http(Neighbour(services, settings.gateway, Some(settings.paymentsProject)), settings.brand)

  def terminal(n: JsonNode): Option[Terminal] =
    for id <- n.text("id") yield Terminal(
      id = id,
      subBrandId = n.textOr("subBrandId", ""),
      name = n.textOr("name", id),
      scheme = n.textOr("scheme", ""),
      gateway = n.textOr("gateway", ""),
      active = n.bool("active").getOrElse(false),
      supportsWithdrawal = n.bool("supportsWithdrawal").getOrElse(false),
      requiresPayoutDestination = n.bool("requiresPayoutDestination").getOrElse(false),
      coins = n.strings("coins").map(_.trim.toUpperCase).filter(_.nonEmpty).distinct,
      hasStatus = n.bool("hasStatus").getOrElse(false),
      reviewWindow = n.text("reviewWindow").flatMap(w => Try(Duration.parse(w)).toOption)
    )

  final class Http(gateway: Neighbour, brand: String) extends Gateway:
    def terminals(currency: Currency): Either[String, Vector[Terminal]] =
      gateway.get(s"/v1/payments/withdrawal-terminals?currency=${currency.code}") match
        case Reply.Answered(200, Some(b), _) => Right(b.objects("terminals").flatMap(terminal))
        case Reply.Answered(s, _, _)         => Left(s"psp-gateway answered $s")
        case Reply.Unreachable(why)          => Left(why)

    def initiate(r: PayoutRequest): Initiation =
      val body = Json.obj("requestId" -> r.requestId, "brand" -> brand, "subBrandId" -> r.payout.subBrandId,
        "terminalId" -> r.terminalId, "amount" -> r.amount.toString, "currency" -> r.currency.code, "method" -> r.payout.method,
        "customerId" -> r.payout.customerId)
      r.payout.details.toVector.sortBy(_._1).foreach((k, v) => body.put(k, Personal.reveal(v)))
      Gateway.initiation(gateway.post("/v1/payments/withdrawals", body))

    def status(requestId: String): PayoutStatus =
      Gateway.status(gateway.get(s"/v1/payments/withdrawals/${Neighbour.segment(requestId)}"))

  def initiation(reply: Reply): Initiation = reply match
    case Reply.Answered(200, Some(b), _) =>
      val ref    = b.text("providerReference")
      val reason = b.textOr("reason", "")
      b.text("status") match
        case Some("completed")         => Initiation.Completed(ref)
        case Some("initiated")         => Initiation.Initiated(ref)
        case Some("already_initiated") => Initiation.AlreadyInitiated(ref)
        case Some("rejected")          => Initiation.Rejected(if reason.isEmpty then "rejected" else reason)
        case Some("in_doubt")          => Initiation.InDoubt(if reason.isEmpty then "in_doubt" else reason)
        case other                     => Initiation.Unreadable(s"unrecognised status ${other.getOrElse("(none)")}")
    case Reply.Answered(409, Some(b), _) if b.text("error").contains("initiation_in_progress") => Initiation.InProgress
    case Reply.Answered(s @ (400 | 403), b, _)                                                  => Initiation.Refused(s, b.flatMap(_.text("error")).getOrElse(""))
    case Reply.Answered(502, Some(b), _) if b.text("error").contains("provider_not_configured") =>
      Initiation.Refused(502, "provider_not_configured")
    case Reply.Answered(s, _, _) => Initiation.Unreadable(s"psp-gateway answered $s")
    case Reply.Unreachable(why)  => Initiation.Unreadable(why)

  def status(reply: Reply): PayoutStatus = reply match
    case Reply.Answered(200, Some(b), _) =>
      val ref    = b.text("providerReference")
      val reason = b.textOr("reason", "")
      b.text("status") match
        case Some("completed") => PayoutStatus.Completed(ref)
        case Some("failed")    => PayoutStatus.Failed(ref, if reason.isEmpty then "payout_failed" else reason)
        case Some("reversed")  => PayoutStatus.Reversed(ref, if reason.isEmpty then "reversed" else reason)
        case Some("pending")   => PayoutStatus.Pending
        case Some("no_record") => PayoutStatus.NoRecord
        case other             => PayoutStatus.Unavailable(s"unrecognised status ${other.getOrElse("(none)")}")
    case Reply.Answered(404, _, _) => PayoutStatus.NoRecord
    case Reply.Answered(s, _, _)   => PayoutStatus.Unavailable(s"psp-gateway answered $s")
    case Reply.Unreachable(why)  => PayoutStatus.Unavailable(why)
