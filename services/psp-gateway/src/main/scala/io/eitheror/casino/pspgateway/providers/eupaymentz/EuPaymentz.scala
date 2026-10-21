package io.eitheror.casino.pspgateway.providers.eupaymentz

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.Amounts
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.{bool, objects, text, textOr}
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.net.URI
import java.time.Instant
import scala.util.Try

/**
 * EuPaymentz (`eupaymentz/adapter.go`, `eupaymentz/signature.go`, `handler/webhook.go:1144-1250`): a
 * hosted page from `/api/v2/processTx`. A `GET` redirect sends the player to EuPaymentz directly; a
 * `POST` redirect goes through the gateway's relay page with EuPaymentz's posted parameters
 * (`adapter.go:240-247`).
 *
 * `providerData`: `relayAction`, the address a POST relay form posts to (only for a POST redirect).
 * The reference recorded is EuPaymentz's `resp_trans_id`.
 */
object EuPaymentz extends Deposits:
  val provider: Provider = Provider.EuPaymentz

  val Declined = "Payment declined"

  /** `account_sha`: hex SHA-256 of `passphrase + amount + accountId + email + ip` (`signature.go:14-16`). */
  def outboundSha(passphrase: String, amount: String, accountId: String, email: String, ip: String): String =
    Digests.sha256Hex(passphrase + amount + accountId + email + ip)

  /** `resp_sha`: hex SHA-256 of `passphrase + resp_trans_id + resp_trans_amount + resp_trans_status` (`signature.go:23-26`). */
  def inboundSha(passphrase: String, transId: String, amount: String, status: String): String =
    Digests.sha256Hex(passphrase + transId + amount + status)

  /** Case-insensitive, as the original's `EqualFold`, and constant time (finding 4). */
  def verifySha(passphrase: String, transId: String, amount: String, status: String, sha: String): Boolean =
    Digests.same(inboundSha(passphrase, transId, amount, status), sha.toLowerCase)

  /** The gateway's public base, read from the webhook address it was given (see `DreamzPay.publicUrl`). */
  def publicUrl(charge: Charge): String =
    Try(URI(charge.webhookUrl)).toOption.filter(u => u.getScheme != null && u.getRawAuthority != null)
      .map(u => s"${u.getScheme}://${u.getRawAuthority}")
      .getOrElse(charge.webhookUrl.reverse.dropWhile(_ == '/').reverse)

  /** The relay page for a POST redirect (`adapter.go:243`). */
  def relayPage(charge: Charge): String = s"${publicUrl(charge)}/v1/psp/checkout/eupaymentz/${charge.paymentId}"

  /**
   * The `processTx` call (`adapter.go:129-178`): credentials in the body, the fields in the original's
   * struct order, `cust_billing_state` `NA` when absent (`adapter.go:97-102`).
   */
  def request(charge: Charge, credential: Credential, customer: Customer, base: String): Either[Initiation, Outbound] =
    (credential.get("accountId"), credential.get("password"), credential.get("passphrase")) match
      case (Some(accountId), Some(password), Some(passphrase)) =>
        val amount = Render.dotted(charge.amount, charge.currency)
        def c(f: Customer => Option[String]) = f(customer).getOrElse("")
        val body = Json.obj(
          "account_id"              -> accountId,
          "account_password"        -> password,
          "account_sha"             -> outboundSha(passphrase, amount, accountId, c(_.email), c(_.ip)),
          "action_type"             -> "payment",
          "account_gateway"         -> "1",
          "merchant_payment_id"     -> charge.paymentId,
          "cust_email"              -> c(_.email),
          "cust_billing_last_name"  -> c(_.lastName),
          "cust_billing_first_name" -> c(_.firstName),
          "cust_billing_address"    -> c(_.address),
          "cust_billing_city"       -> c(_.city),
          "cust_billing_zipcode"    -> c(_.postCode),
          "cust_billing_state"      -> customer.state.filter(_.nonEmpty).getOrElse("NA"),
          "cust_billing_country"    -> c(_.country),
          "cust_billing_phone"      -> c(_.phone),
          "transac_products_name"   -> "Casino Deposit",
          "transac_amount"          -> amount,
          "transac_currency_code"   -> charge.currency.code,
          "customer_ip"             -> c(_.ip),
          "merchant_url_return"     -> charge.returnUrl,
          "merchant_url_callback"   -> charge.webhookUrl
        )
        Right(Outbound.json(s"${base.reverse.dropWhile(_ == '/').reverse}/api/v2/processTx", body))
      case _ => Left(Initiation.Unavailable("eupaymentz: missing required credentials (accountId, password, passphrase)"))

  /**
   * The answer (`adapter.go:186-247`): HTTP `200`, `success`, then under `data` `PEND` and
   * `UrlToRedirect` required. A `POST` redirect becomes the relay page and its form.
   */
  def redirectFrom(answered: Answered, charge: Charge): Initiation =
    if answered.status != 200 then Initiation.Unavailable(s"eupaymentz: processTx returned unexpected HTTP ${answered.status}")
    else
      answered.json.filter(_.isObject) match
        case None => Initiation.Unavailable("eupaymentz: failed to decode processTx response")
        case Some(envelope) if !envelope.bool("success").contains(true) =>
          Initiation.Unavailable("eupaymentz: processTx returned success=false")
        case Some(envelope) =>
          val data: JsonNode = Option(envelope.get("data")).filter(_.isObject).getOrElse(Json.obj())
          val status         = data.textOr("resp_trans_status", "")
          (status, data.text("UrlToRedirect")) match
            case ("PEND", Some(url)) =>
              val reference = data.text("resp_trans_id")
              if data.textOr("UrlToRedirectMethod", "") == "POST" then
                val form = Form(data.objects("UrlToRedirecPostedParameters").map(p => p.textOr("key", "") -> p.textOr("value", "")))
                Initiation.Redirected(relayPage(charge), reference, Map("relayAction" -> url), Some(form))
              else Initiation.Redirected(url, reference)
            case ("PEND", None) => Initiation.Unavailable("eupaymentz: processTx returned PEND but no UrlToRedirect")
            case _ =>
              Initiation.Unavailable(s"eupaymentz: unexpected resp_trans_status=\"$status\" — hosted mode should return PEND")

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    request(charge, credential, customer, base) match
      case Left(refusal) => refusal
      case Right(out) =>
        call(out) match
          case Left(CallFailure.TimedOut)         => Initiation.Unavailable("eupaymentz: processTx request timed out")
          case Left(CallFailure.Unreachable(why)) => Initiation.Unavailable(s"eupaymentz: processTx request failed: $why")
          case Right(answered)                    => redirectFrom(answered, charge)

  /** A callback, flat at the top level (`webhook.go:1159-1172`; not the `processTx` envelope). */
  final case class Notification(transId: String, merchantId: String, amount: String, status: String,
      description: Option[String], sha: String)

  def read(raw: Raw): Option[Notification] =
    Json.parseObject(raw.body).flatMap { n =>
      n.text("resp_trans_merchant_id").map(Notification(n.textOr("resp_trans_id", ""), _, n.textOr("resp_trans_amount", ""),
        n.textOr("resp_trans_status", ""), n.text("resp_trans_description_status"), n.textOr("resp_sha", "")))
    }

  def verify(n: Notification, credential: Credential): Either[Rejected, Unit] =
    credential.get("passphrase") match
      case None             => Left(Rejected("missing passphrase"))
      case Some(passphrase) =>
        Either.cond(verifySha(passphrase, n.transId, n.amount, n.status, n.sha), (), Rejected("resp_sha does not verify"))

  /** `00000` captured; `PEND` open; anything else failed (`adapter.go:252-262`, `webhook.go:1232-1247`). */
  def outcome(n: Notification): Verdict = n.status match
    case "00000" => Verdict.Settle(Outcome.Captured(), n.status)
    case "PEND"  => Verdict.Open(n.status)
    case other   => Verdict.Settle(Outcome.Failed(Some(other), n.description.getOrElse(Declined)), other)

  /** Always `200`, empty, to stop EuPaymentz retrying. */
  def locate(raw: Raw): Either[Reply, Located] = read(raw).map(n => Located.Deposit(n.merchantId)).toRight(Reply.ok)

  def unknown(raw: Raw): Reply = Reply.ok

  /**
   * Verified, then cross-checked (FR-013): `resp_trans_merchant_id` is outside the signature, so the
   * signed `resp_trans_id` must be the one `processTx` answered and the signed amount the session's;
   * that closes a replay of one session's signed success under another's reference (finding 4). A
   * callback that does not verify, or does not match its session, is `400` (`Reply.invalidSignature`);
   * the rest `200`.
   */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    read(raw) match
      case None => Handled(Verdict.Reject("unreadable callback"), Reply.ok)
      case Some(n) =>
        verify(n, credential) match
          case Left(r)   => Handled(Verdict.Reject(r.reason), Reply.invalidSignature)
          case Right(()) =>
            crossCheck(n, charge).fold(r => Handled(Verdict.Reject(r.reason), Reply.invalidSignature), _ => Handled(outcome(n), Reply.ok))

  private def crossCheck(n: Notification, charge: Charge): Either[Rejected, Unit] =
    if n.merchantId != charge.paymentId then Left(Rejected("reference does not match the session"))
    else if charge.providerReference.exists(_ != n.transId) then Left(Rejected("resp_trans_id does not match the session"))
    else if !Amounts.parse(n.amount, charge.currency).exists(_.value == charge.amount) then
      Left(Rejected("amount does not match the session"))
    else Right(())
