package io.eitheror.casino.pspgateway.sessions

import java.time.Instant

/**
 * The nine providers (spec 005; ConnecX dropped), by the name `merchant` stores a credential under and
 * the path segment their routes use. `wire` is the catalogue's upper case, which `merchant`'s
 * credential route matches case-insensitively (research R7, as built).
 */
enum Provider(val wire: String, val path: String):
  case DreamzPay   extends Provider("DREAMZPAY", "dreamzpay")
  case UnityFinance extends Provider("UNITYFINANCE", "unityfinance")
  case T365        extends Provider("T365", "t365")
  case Cozeni      extends Provider("COZENI", "cozeni")
  case EuPaymentz  extends Provider("EUPAYMENTZ", "eupaymentz")
  case Gigadat     extends Provider("GIGADAT", "gigadat")
  case BounceMoney extends Provider("BOUNCEMONEY", "bouncemoney")
  case Digitarium  extends Provider("DIGITARIUM", "digitarium")
  case Morphosis   extends Provider("MORPHOSIS", "morphosis")

object Provider:
  /** A provider by any case of its name; none for an unknown one, which the initiate refuses (FR-001). */
  def from(name: String): Option[Provider] = values.find(_.wire.equalsIgnoreCase(name.trim))

/**
 * A crypto deposit's difference from its quote, as the provider reported it (FR-005): the kind, and
 * the provider's own strings, passed to `merchant` as the original passes them.
 */
final case class Variance(
    kind: String,
    quoted: Option[String] = None,
    receivedCryptoAmount: Option[String] = None,
    receivedConvertAmount: Option[String] = None,
    receivedConvertCurrency: Option[String] = None
)

object Variance:
  val Underpayment = "UNDERPAYMENT"
  val Overpayment  = "OVERPAYMENT"

/**
 * What a provider said happened (FR-005). Pending is "no outcome yet" and is never stored as one; a
 * provider's cancellation is a failure, as the original maps it. A failure carries the provider's code
 * and the player-facing reason, and what arrived when the provider reported it.
 */
enum Outcome:
  case Captured(variance: Option[Variance] = None)
  case Failed(code: Option[String], reason: String, received: Option[Variance] = None)
  case Completed
  case PayoutFailed(code: Option[String], reason: String)
  case Reversed

  /** The word 004's session read and 007's outcome body use. */
  def word: String = this match
    case Captured(_)        => "captured"
    case Failed(_, _, _)    => "failed"
    case Completed          => "completed"
    case PayoutFailed(_, _) => "failed"
    case Reversed           => "reversed"

/**
 * Who an outcome is delivered to, fixed at claim time from the session and never from a webhook
 * (FR-017): `merchant`'s capture or fail for a deposit, the brand's `payouts` for a payout.
 */
enum Receiver:
  case Merchant(paymentId: String)
  case Payouts(brand: String, requestId: String)

  def describe: String = this match
    case Merchant(id)            => s"payments/merchant $id"
    case Payouts(brand, request) => s"$brand/payouts $request"

/** Whether an outcome reached its receiver (FR-015, FR-016). */
enum Delivery:
  case NotYet
  case Delivered(at: Instant, status: Int)
  /** The receiver answered a client error: only an operator's re-drive sends it again. */
  case Refused(at: Instant, status: Int, answer: String)
  /** The budget was spent with no acknowledgement: the timer re-drives it. */
  case Undelivered(at: Instant, attempts: Int, lastAnswer: String)

  def word: String = this match
    case NotYet               => "NotYet"
    case Delivered(_, _)      => "Delivered"
    case Refused(_, _, _)     => "Refused"
    case Undelivered(_, _, _) => "Undelivered"

/** Where the player is sent to pay (FR-003): a page, or a crypto destination. */
enum Destination:
  case Redirect(url: String)
  case Crypto(address: String, coinAmount: String, coinCurrency: String, validUntil: Option[String])
