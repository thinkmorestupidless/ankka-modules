package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.sessions.{Destination, Outcome, Provider}

import java.security.SecureRandom
import java.time.Instant

/**
 * What a provider's pure package is given and answers: the gateway's session, read into the fields a
 * provider builds from, and the provider's verdicts. Each `providers.<name>` package implements
 * `Deposits` (and `Payouts` where the catalogue offers payouts) over its own request building,
 * parsing and verification, so the endpoints and the deliverer treat the nine alike.
 */

/** An outbound call failed before a provider answered (FR-023). */
enum CallFailure:
  case TimedOut
  case Unreachable(reason: String)

/** Makes one call to a provider: `ProviderHttp` in the service, a script in a test. */
type Call = Outbound => Either[CallFailure, Answered]

/**
 * A deposit session as a provider sees it. `generated` is what the gateway made at claim, before any
 * call (`salt` for Cozeni, `token` for Morphosis); `providerReference` and `providerData` are what the
 * initiation recorded, empty before it (a webhook on a claimed session sees them empty).
 */
final case class Charge(
    paymentId: String,
    amount: Long,
    currency: Currency,
    method: String,
    terminalId: Option[String],
    returnUrl: String,
    webhookUrl: String,
    generated: Map[String, String] = Map.empty,
    providerReference: Option[String] = None,
    providerData: Map[String, String] = Map.empty,
    /** The player's id from the initiate body (not personal; Gigadat's `userId`, BounceMoney's `customerReference`). */
    customerId: Option[String] = None
)

/** What an initiation came to, before the session records it. */
enum Initiation:
  /**
   * The player goes to `url`. `reference` is the provider's own id where it gave one;
   * `relay` is a form the gateway's own page POSTs for the player (DreamzPay, EuPaymentz's POST redirect).
   */
  case Redirected(url: String, reference: Option[String], providerData: Map[String, String] = Map.empty,
      relay: Option[Form] = None)
  /** A crypto quote: the address and coin amount the player pays to (Cozeni, Morphosis). */
  case Quoted(destination: Destination.Crypto, reference: Option[String], providerData: Map[String, String] = Map.empty)
  /**
   * A `400` the provider's rules give: `error` is `player_error`, `unsupported_method` or
   * `unsupported_currency` (contracts/merchant.md §1), `message` the text the original gave.
   */
  case Refused(error: String, message: String)
  /** The provider failed, timed out or answered what cannot be read: `502 provider_unavailable`. */
  case Unavailable(reason: String)

/** What a webhook says once verified against its session. */
enum Verdict:
  /** A final outcome. `providerStatus` is the provider's own word, for the record. */
  case Settle(outcome: Outcome, providerStatus: String)
  /** Not final yet (pending, processing): acknowledged, nothing moves. */
  case Open(providerStatus: String)
  /** Verified, but nothing the gateway acts on (a refund notice, a type it does not handle): logged. */
  case Ignore(reason: String)
  /** It did not verify, or could not be read: nothing moves. */
  case Reject(reason: String)

/** A webhook's verdict and what the provider is answered. */
final case class Handled(verdict: Verdict, reply: Reply)

/** Which session a webhook names, read before verification (it chooses the credential to verify with). */
enum Located:
  case Deposit(reference: String)
  case Payout(reference: String)
  /** Looked up through `ReferenceIndex` under `<provider>/<key>` (T365's dashless id, Morphosis's tokens). */
  case Indexed(key: String)

trait Deposits:
  def provider: Provider

  /** What the gateway generates at claim for this provider (a salt, a token); none for most. */
  def generate(@scala.annotation.unused random: SecureRandom): Map[String, String] = Map.empty

  /**
   * Initiates against `base` (the provider's base URL, resolved by the caller from `Catalogue` and
   * `Settings`). Calls go through `call`; nothing here touches the network itself.
   */
  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation

  /** The session the webhook names, or the reply for a webhook that names none readable. */
  def locate(raw: Raw): Either[Reply, Located]

  /** The reply when the named session does not exist (each provider's own, research R9). */
  def unknown(raw: Raw): Reply

  /** Verifies against the session and the credential read for it, then reads the outcome. */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled

/** A payout as a provider sees it. `destination` is the method's details, passed and never kept (FR-022). */
final case class Withdrawal(
    requestId: String,
    brand: String,
    amount: Long,
    currency: Currency,
    method: String,
    terminalId: String,
    webhookUrl: String,
    destination: Map[String, String],
    generated: Map[String, String] = Map.empty,
    providerReference: Option[String] = None,
    /** The player's id (Gigadat's `userId`). */
    customerId: Option[String] = None
):
  override def toString: String = s"Withdrawal($requestId, $brand, $amount ${currency.code}, $method)"

/** What a payout initiation came to (contracts/payouts.md's `status`). */
enum PayoutAnswer:
  case Initiated(providerReference: Option[String])
  case Completed(providerReference: Option[String])
  case AlreadyInitiated
  case Rejected(reason: String)
  case InDoubt(reason: String)

object PayoutAnswer:
  /**
   * A `401` or `403` to the request that makes a payout is `rejected` / `unauthorised` for every provider
   * (contracts/payouts.md; a decision of 2026-10-19: the original did so for Unity Finance only): the
   * provider refused the credential, so it holds no payout to be in doubt about.
   */
  def unauthorised(status: Int): Option[PayoutAnswer] =
    Option.when(status == 401 || status == 403)(Rejected("unauthorised"))

/** A payout's status read from the provider (FR-009; Unity Finance only). */
enum PayoutStatus:
  case Final(outcome: Outcome, providerStatus: String)
  case Pending(providerStatus: String)
  case NoRecord
  case Unavailable(reason: String)

trait Payouts:
  def provider: Provider

  def generate(@scala.annotation.unused random: SecureRandom): Map[String, String] = Map.empty

  def payout(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): PayoutAnswer

  /** The provider's status for a payout, where it offers one (`Catalogue.hasStatus`). */
  def status(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): Option[PayoutStatus] = None

  def locatePayout(raw: Raw): Either[Reply, Located]

  def unknownPayout(raw: Raw): Reply

  def payoutWebhook(raw: Raw, withdrawal: Withdrawal, credential: Credential, now: Instant): Handled
