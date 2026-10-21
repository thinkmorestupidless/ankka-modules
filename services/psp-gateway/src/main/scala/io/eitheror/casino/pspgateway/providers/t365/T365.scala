package io.eitheror.casino.pspgateway.providers.t365

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.Amounts
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.{text, textOr}
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.time.Instant

/**
 * T365 (`t365/adapter.go`, `t365/signature.go`, `handler/webhook.go:410-548`): a hosted payment page
 * from one JSON call. The `orderId` sent is the payment id without its dashes (`adapter.go:73-74`), so
 * a webhook is found through `ReferenceIndex` under `t365/<orderId>` (research R4).
 *
 * `providerData`: `orderId`, the dashless id sent and echoed.
 */
object T365 extends Deposits:
  val provider: Provider = Provider.T365

  val Declined = "Payment declined"

  /** The payment id as T365 takes it: 32 hex characters, within its 10–50 alphanumerics. */
  def orderId(paymentId: String): String = paymentId.replace("-", "")

  /** Our method names to T365's (`adapter.go:191-228`); none for a method it lacks. */
  def methodCode(method: String): Option[String] = method match
    case "BANK_TRANSFER" | "WALLETS" | "QR_CODE" | "VIRTUAL_ACCOUNT" | "UPI" | "FPX" | "DUITNOW" | "TRUEMONEY" |
        "MOMOPAY" | "MOBILE_MONEY" | "CASH" | "PIX" | "OPEN_BANKING" => Some(method)
    case "CRYPTO_BEP20" => Some("BEP20")
    case "CRYPTO_ERC20" => Some("ERC20")
    case "CRYPTO_TRC20" => Some("TRC20")
    case _              => None

  /** The key padded with `0` to 16 characters; a longer key as it is (`signature.go:13-18`). */
  def padSecretKey(key: String): String = if key.length >= 16 then key else key + "0" * (16 - key.length)

  /** Upper case hex SHA-256 of the notification string; `id` and `orderId` both the order id (`signature.go:30-38`). */
  def sign(secretKey: String, amount: String, currency: String, orderId: String, status: String): String =
    Digests.sha256Hex(
      s"secretKey=${padSecretKey(secretKey)}&amount=$amount&currency=$currency&id=$orderId&orderId=$orderId&status=$status"
    ).toUpperCase

  /**
   * The hosted-payment call (`adapter.go:55-125`). `midId` is a number when it parses, else the string;
   * the fields are Go's map order (sorted). Customer email, IP and country are sent empty when absent,
   * as the original only logs them missing.
   */
  def request(charge: Charge, credential: Credential, customer: Customer, base: String): Either[Initiation, Outbound] =
    (credential.get("apiKey"), credential.get("secretKey"), credential.get("midId")) match
      case (Some(apiKey), Some(_), Some(midId)) =>
        methodCode(charge.method) match
          case None => Left(Initiation.Refused("unsupported_method", s"t365: unsupported method \"${charge.method}\""))
          case Some(code) =>
            val body = Json.obj(
              "amount"      -> Render.twoDecimals(charge.amount, charge.currency),
              "callbackUrl" -> charge.webhookUrl,
              "country"     -> customer.country.getOrElse(""),
              "currency"    -> charge.currency.code,
              "email"       -> customer.email.getOrElse(""),
              "ip"          -> customer.ip.getOrElse(""),
              "method"      -> code,
              "midId"       -> midId.toLongOption.getOrElse(midId),
              "orderId"     -> orderId(charge.paymentId),
              "returnUrl"   -> charge.returnUrl
            )
            Right(Outbound.json(s"${base.reverse.dropWhile(_ == '/').reverse}/api/v1/external/payment/hosted", body,
              "apikey" -> apiKey))
      case _ => Left(Initiation.Unavailable("t365: missing required credentials (apiKey, secretKey, midId)"))

  /**
   * `paymentUrl` required, whatever the status (`adapter.go:140-145`); T365's `id` is its reference.
   */
  def redirectFrom(answered: Answered, charge: Charge): Initiation =
    answered.json.filter(_.isObject) match
      case None => Initiation.Unavailable(s"t365: failed to decode response (HTTP ${answered.status})")
      case Some(json) =>
        json.text("paymentUrl") match
          case None =>
            Initiation.Unavailable(s"t365: response missing paymentUrl (HTTP ${answered.status} status=\"${
                json.textOr("status", "")}\" message=\"${json.textOr("message", "")}\")")
          case Some(url) => Initiation.Redirected(url, json.text("id"), Map("orderId" -> orderId(charge.paymentId)))

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    request(charge, credential, customer, base) match
      case Left(refusal) => refusal
      case Right(out) =>
        call(out) match
          case Left(CallFailure.TimedOut)        => Initiation.Unavailable("t365: payment request timed out")
          case Left(CallFailure.Unreachable(why)) => Initiation.Unavailable(s"t365: payment request failed: $why")
          case Right(answered)                   => redirectFrom(answered, charge)

  /** A postback (`webhook.go:427-434`). */
  final case class Notification(id: String, orderId: String, status: String, amount: Option[String],
      currency: Option[String], hash: String)

  def read(raw: Raw): Option[Notification] =
    Json.parseObject(raw.body).flatMap { (n: JsonNode) =>
      n.text("orderId").map(Notification(n.textOr("id", ""), _, n.textOr("status", ""), n.text("amount"),
        n.text("currency"), n.textOr("hash", "")))
    }

  /**
   * The `apikey` header checked only when both it and the credential's are present
   * (`webhook.go:499-505`), then the hash over the postback's amount and currency, the session's when
   * absent (`webhook.go:507-521`). Both comparisons constant time.
   */
  def verify(n: Notification, charge: Charge, credential: Credential, apikeyHeader: Option[String]): Either[Rejected, Unit] =
    val apiKey = credential.get("apiKey")
    if apikeyHeader.exists(_.nonEmpty) && apiKey.nonEmpty && !Digests.same(apikeyHeader.get, apiKey.get) then
      Left(Rejected("apikey header does not match"))
    else
      val amount   = n.amount.getOrElse(Render.twoDecimals(charge.amount, charge.currency))
      val currency = n.currency.getOrElse(charge.currency.code)
      val expected = sign(credential.get("secretKey").getOrElse(""), amount, currency, n.orderId, n.status)
      Either.cond(Digests.same(expected, n.hash), (), Rejected("hash does not verify"))

  /** `SUCCESS` captured; `REJECTED`, `FAIL` failed; `PENDING` open (`webhook.go:523-545`). */
  def outcome(n: Notification): Verdict = n.status match
    case "SUCCESS"            => Verdict.Settle(Outcome.Captured(), n.status)
    case "REJECTED" | "FAIL"  => Verdict.Settle(Outcome.Failed(Some(n.status), Declined), n.status)
    case "PENDING"            => Verdict.Open(n.status)
    case other                => Verdict.Ignore(s"unknown status $other")

  /** T365 retries a non-`200` up to five times, so every answer is `200`, empty. */
  def locate(raw: Raw): Either[Reply, Located] = read(raw).map(n => Located.Indexed(n.orderId)).toRight(Reply.ok)

  def unknown(raw: Raw): Reply = Reply.ok

  /**
   * Verified, then cross-checked (FR-013, new): the order id, and the amount and currency where sent. A
   * postback that does not verify, or does not match its session, is `400` (`Reply.invalidSignature`); the
   * rest `200`.
   */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    read(raw) match
      case None => Handled(Verdict.Reject("unreadable postback"), Reply.ok)
      case Some(n) =>
        verify(n, charge, credential, raw.header("apikey")) match
          case Left(r)   => Handled(Verdict.Reject(r.reason), Reply.invalidSignature)
          case Right(()) =>
            crossCheck(n, charge).fold(r => Handled(Verdict.Reject(r.reason), Reply.invalidSignature), _ => Handled(outcome(n), Reply.ok))

  private def crossCheck(n: Notification, charge: Charge): Either[Rejected, Unit] =
    if n.orderId != orderId(charge.paymentId) then Left(Rejected("orderId does not match the session"))
    else if n.amount.exists(a => !Amounts.parse(a, charge.currency).exists(_.value == charge.amount)) then
      Left(Rejected("amount does not match the session"))
    else if n.currency.exists(_ != charge.currency.code) then Left(Rejected("currency does not match the session"))
    else Right(())
