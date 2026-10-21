package io.eitheror.casino.merchant.gateway

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.cashier.CustomerFields

/**
 * What the cashier asks the gateway to initiate (contracts/gateway.md; the original's `HppInitiateReq`
 * with the amount in minor units): the payment id is the gateway's idempotency key (spec 005 FR-008).
 */
final case class HostedPage(
    paymentId: String,
    amount: Long,
    currency: String,
    method: String,
    callbackUrl: String,
    psp: String,
    subBrandId: String,
    customerId: String,
    redirectUrl: String,
    terminalId: String,
    terminalProperties: Map[String, String],
    customer: CustomerFields
)

/** A crypto deposit's destination, when the provider returned one. */
final case class Crypto(paymentAddress: String, cryptoAmount: String, cryptoCurrency: String, validUntil: Option[String])

/** The gateway's answer to an initiate (`HppInitiateResp`). */
final case class Initiated(redirectUrl: String, pspTransactionId: String, crypto: Option[Crypto])

/** Why an initiate did not happen. */
enum GatewayRefusal:
  /** A reason the player can act on, in words written for them (`CashierPlayerErrorForwardingSpec`). */
  case Player(message: String)
  /** Any other refusal or failure: the player sees a generic failure. */
  case Failure(status: Int, detail: String)
  /** No answer at all: the submit is answered unavailable. */
  case Unreachable(reason: String)

/** What the gateway knows of a payment's provider session (contracts/gateway.md, spec 005 FR-009). */
enum SessionStatus:
  case Pending
  case Resolved(outcome: Option[String])
  /** An outcome the gateway could not deliver and holds for re-drive: the payment must wait. */
  case Undelivered
  /** The gateway holds no session for the payment. */
  case Unknown
  /** The read failed: the original fails closed and does not expire (`outcomeStillOutstanding`). */
  case Unreadable(reason: String)

/** The seam between the cashier and `psp-gateway` (research R3); `FakeGateway` serves it in the suites. */
trait GatewayClient:
  def initiate(page: HostedPage): Either[GatewayRefusal, Initiated]
  def session(paymentId: String): SessionStatus

object GatewayClient:
  /** `psp-gateway` in this project, over HTTP as this service. */
  def http(services: ServiceClients): GatewayClient = HttpGateway(Http(services, "psp-gateway"))

  def body(p: HostedPage): com.fasterxml.jackson.databind.node.ObjectNode =
    val o = Json.obj("paymentId" -> p.paymentId, "amount" -> p.amount, "currency" -> p.currency, "method" -> p.method,
      "callbackUrl" -> p.callbackUrl, "psp" -> p.psp, "subBrandId" -> p.subBrandId, "customerId" -> p.customerId,
      "redirectUrl" -> p.redirectUrl, "terminalId" -> p.terminalId, "terminalProperties" -> Json.objOf(p.terminalProperties))
    p.customer.wire.foreach((k, v) => o.put(k, v))
    o

private final class HttpGateway(http: Http) extends GatewayClient:
  def initiate(page: HostedPage): Either[GatewayRefusal, Initiated] =
    http.post("/v1/psp/hpp/initiate", GatewayClient.body(page)) match
      case Reply.Unreachable(reason) => Left(GatewayRefusal.Unreachable(reason))
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 =>
        val crypto = b.text("paymentAddress").map(addr =>
          Crypto(addr, b.textOr("cryptoAmount", ""), b.textOr("cryptoCurrency", ""), b.text("validUntil")))
        Right(Initiated(b.textOr("redirectUrl", ""), b.textOr("pspTransactionId", ""), crypto))
      case Reply.Answered(400, Some(b), _) if b.text("error").contains("player_error") && b.text("message").isDefined =>
        Left(GatewayRefusal.Player(b.text("message").get))
      case Reply.Answered(s, _, text) => Left(GatewayRefusal.Failure(s, text.take(300)))

  def session(paymentId: String): SessionStatus =
    http.get(s"/v1/psp/sessions/${Http.segment(paymentId)}") match
      case Reply.Unreachable(reason)                    => SessionStatus.Unreadable(reason)
      case Reply.Answered(404, _, _)                   => SessionStatus.Unknown
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 =>
        if b.bool("undelivered").contains(true) then SessionStatus.Undelivered
        else if b.text("state").contains("resolved") then SessionStatus.Resolved(b.text("outcome"))
        else SessionStatus.Pending
      case Reply.Answered(s, _, text) => SessionStatus.Unreadable(s"the gateway answered $s: ${text.take(200)}")
