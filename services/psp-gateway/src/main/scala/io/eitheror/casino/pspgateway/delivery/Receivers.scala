package io.eitheror.casino.pspgateway.delivery

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.{Neighbour, Reply}
import io.eitheror.casino.pspgateway.sessions.{Outcome, Receiver}

/** What a receiver answered one delivery: its status (none when nothing answered) and a short account of it. */
final case class Answer(status: Option[Int], text: String):
  def describe: String = status.fold(text)(s => if text.isEmpty then s.toString else s"$s $text")

/**
 * The seam to whoever an outcome is for (FR-014): `merchant`'s capture and fail within the project, or
 * a brand's `payouts` under the grant it made. The body is built here from the outcome, as 004's
 * `gateway.md` and contracts/payouts.md give it, and sent as it was signed.
 */
trait Receivers:
  def deliver(receiver: Receiver, outcome: Outcome, body: String, headers: Vector[(String, String)]): Answer

object Receivers:
  def http(services: ServiceClients, merchantService: String): Receivers = HttpReceivers(services, merchantService)

  /** The path the outcome is sent to: capture or fail for a deposit, the payout's outcome route. */
  def path(receiver: Receiver, outcome: Outcome): String = receiver match
    case Receiver.Merchant(paymentId) =>
      val verb = outcome match
        case Outcome.Captured(_) => "capture"
        case _                   => "fail"
      s"/v1/payments/${Neighbour.segment(paymentId)}/$verb"
    case Receiver.Payouts(brand, requestId) =>
      s"/v1/casino/brands/${Neighbour.segment(brand)}/withdrawals/${Neighbour.segment(requestId)}/outcome"

  /**
   * The body for the receiver. A capture: `outcome: success`, `psp_transaction_id` and the variance where
   * the provider reported one. A fail: `failureCode?`, `failureReason`, the received amounts. A payout:
   * `outcome`, `providerReference`, `reason?`.
   */
  def body(receiver: Receiver, outcome: Outcome, providerReference: Option[String]): String =
    val node = (receiver, outcome) match
      case (Receiver.Merchant(paymentId), Outcome.Captured(v)) =>
        Json.obj("outcome" -> "success", "psp_transaction_id" -> providerReference.getOrElse(paymentId),
          "variance" -> v.map(_.kind), "receivedCryptoAmount" -> v.flatMap(_.receivedCryptoAmount),
          "receivedConvertAmount" -> v.flatMap(_.receivedConvertAmount), "receivedConvertCurrency" -> v.flatMap(_.receivedConvertCurrency))
      case (Receiver.Merchant(_), Outcome.Failed(code, reason, received)) =>
        Json.obj("failureCode" -> code, "failureReason" -> reason,
          "receivedCryptoAmount" -> received.flatMap(_.receivedCryptoAmount),
          "receivedConvertAmount" -> received.flatMap(_.receivedConvertAmount),
          "receivedConvertCurrency" -> received.flatMap(_.receivedConvertCurrency))
      case (Receiver.Merchant(_), other) =>
        Json.obj("failureReason" -> s"unexpected outcome ${other.word}")
      case (Receiver.Payouts(_, _), o) =>
        val reason = o match
          case Outcome.PayoutFailed(_, r) => Some(r)
          case Outcome.Failed(_, r, _)    => Some(r)
          case _                          => None
        Json.obj("outcome" -> o.word, "providerReference" -> providerReference.getOrElse(""), "reason" -> reason)
    Json.render(node)

private final class HttpReceivers(services: ServiceClients, merchantService: String) extends Receivers:
  def deliver(receiver: Receiver, outcome: Outcome, body: String, headers: Vector[(String, String)]): Answer =
    val target = receiver match
      case Receiver.Merchant(_)       => Neighbour(services, merchantService)
      case Receiver.Payouts(brand, _) => Neighbour(services, "payouts", Some(brand))
    target.post(Receivers.path(receiver, outcome), body, headers) match
      case Reply.Answered(s, b, text) =>
        Answer(Some(s), b.flatMap(n => Json.text(n)("error")).getOrElse(text.take(200)))
      case Reply.Unreachable(reason) => Answer(None, reason)
