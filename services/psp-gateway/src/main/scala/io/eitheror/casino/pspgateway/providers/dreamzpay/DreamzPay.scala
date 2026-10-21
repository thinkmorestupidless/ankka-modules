package io.eitheror.casino.pspgateway.providers.dreamzpay

import io.eitheror.casino.contract.money.Amounts
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.net.URI
import java.time.Instant
import scala.util.Try

/**
 * DreamzPay (`dreamzpay/adapter.go`, `dreamzpay/signature.go`, `handler/webhook.go:49-247`). No
 * outbound call: the initiation is a form the gateway's relay page auto-POSTs to
 * `{DREAMZPAY_BASE_URL}/transaction/Checkout` for the player.
 *
 * `providerData`: `relayAction`, the address the relay form posts to.
 */
object DreamzPay extends Deposits:
  val provider: Provider = Provider.DreamzPay

  /** The reason the original sends `merchant` for every decline (`webhook.go:591`). */
  val Declined = "Payment declined"

  /** `memberId|totype|amount|ref|redirectUrl|secureKey`, MD5 hex (`signature.go:11-14`). */
  def outboundChecksum(memberId: String, totype: String, amount: String, ref: String, redirectUrl: String,
      secureKey: String): String =
    Digests.md5Hex(Vector(memberId, totype, amount, ref, redirectUrl, secureKey).mkString("|"))

  /** `paymentId|ref|amount|status|secureKey`, MD5 hex (`signature.go:18-21`). */
  def inboundChecksum(paymentId: String, ref: String, amount: String, status: String, secureKey: String): String =
    Digests.md5Hex(Vector(paymentId, ref, amount, status, secureKey).mkString("|"))

  /**
   * The gateway's public base (`PUBLIC_URL`), read back from the webhook address it was given: the
   * caller builds `charge.webhookUrl` as `PUBLIC_URL` + the webhook path, so its scheme and authority
   * are `PUBLIC_URL`. The original used `PSP_GATEWAY_BASE_URL` directly (`adapter.go:53`).
   */
  def publicUrl(charge: Charge): String =
    Try(URI(charge.webhookUrl)).toOption.filter(u => u.getScheme != null && u.getRawAuthority != null)
      .map(u => s"${u.getScheme}://${u.getRawAuthority}")
      .getOrElse(charge.webhookUrl.reverse.dropWhile(_ == '/').reverse)

  /** The relay page the player is sent to (`adapter.go:53`). */
  def relayPage(charge: Charge): String = s"${publicUrl(charge)}/v1/psp/checkout/dreamzpay/${charge.paymentId}"

  /**
   * The relay form's fields (`adapter.go:36-77`): `terminalId.{METHOD}` over `terminalId`, left out when
   * neither is set; the return and notification addresses are the session's. A missing key is the
   * original's error, naming the keys only.
   */
  def checkoutForm(charge: Charge, credential: Credential): Either[String, Form] =
    (credential.get("memberId"), credential.get("totype"), credential.get("secureKey")) match
      case (Some(memberId), Some(totype), Some(secureKey)) =>
        val amount = Render.twoDecimals(charge.amount, charge.currency)
        val fields = Vector(
          "amount"                -> amount,
          "checksum"              -> outboundChecksum(memberId, totype, amount, charge.paymentId, charge.returnUrl, secureKey),
          "currency"              -> charge.currency.code,
          "memberId"              -> memberId,
          "merchantRedirectUrl"   -> charge.returnUrl,
          "merchantTransactionId" -> charge.paymentId,
          "notificationUrl"       -> charge.webhookUrl
        ) ++ credential.forMethod("terminalId", charge.method).map("terminalId" -> _) :+ ("totype" -> totype)
        Right(Form(fields.sortBy(_._1)))
      case _ => Left("dreamzpay: missing required credentials (memberId, totype, secureKey)")

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    checkoutForm(charge, credential) match
      case Left(reason) => Initiation.Unavailable(reason)
      case Right(form) =>
        Initiation.Redirected(relayPage(charge), None,
          Map("relayAction" -> s"${base.reverse.dropWhile(_ == '/').reverse}/transaction/Checkout"), Some(form))

  /** A notification as `webhook.go:51-59` reads it, from either body shape. */
  final case class Notification(paymentId: String, ref: String, amount: String, status: String,
      currency: Option[String], checksum: String)

  /**
   * The body by content type with the other shape as fallback (`webhook.go:70-117`); none when it names
   * no transaction. `transactionStatus` is the signed `Y`/`N`, `status` its fallback (`webhook.go:150-160`).
   */
  def read(raw: Raw): Option[Notification] =
    val body = raw.sniffed
    def f(key: String) = body.text(key).getOrElse("")
    body.text("merchantTransactionId").map { ref =>
      Notification(f("paymentId"), ref, f("amount"), body.text("transactionStatus").getOrElse(f("status")),
        body.text("currency"), f("checksum"))
    }

  /** The inbound checksum with the session's `secureKey`, constant time (`webhook.go:215`). */
  def verify(raw: Raw, credential: Credential): Either[Rejected, Notification] =
    for
      n   <- read(raw).toRight(Rejected("no merchantTransactionId"))
      key <- credential.get("secureKey").toRight(Rejected("missing secureKey"))
      _   <- Either.cond(Digests.same(inboundChecksum(n.paymentId, n.ref, n.amount, n.status, key), n.checksum), (),
               Rejected("checksum does not verify"))
    yield n

  /** `Y` captured, anything else failed with the status as its code (`webhook.go:220-242`). */
  def outcome(n: Notification): Verdict =
    if n.status == "Y" then Verdict.Settle(Outcome.Captured(), n.status)
    else Verdict.Settle(Outcome.Failed(Some(n.status), Declined), n.status)

  /** `200 {"paymentId","result":"accepted"}`, as Go's map encodes it (`webhook.go:245`). */
  def accepted(paymentId: String): Reply = Reply.json(200, Json.obj("paymentId" -> paymentId, "result" -> "accepted"))
  def unknownTxn(paymentId: String): Reply = Reply.json(200, Json.obj("paymentId" -> paymentId, "result" -> "unknown_txn"))
  val invalidChecksum: Reply               = Reply.json(400, Json.obj("error" -> "invalid_checksum"))
  /** A body that reads as neither shape (`webhook.go:140-145`). */
  val invalidBody: Reply = Reply.text(400, "invalid request body\n")

  def locate(raw: Raw): Either[Reply, Located] =
    read(raw) match
      case Some(n) => Right(Located.Deposit(n.ref))
      case None if !raw.isEmpty && raw.sniffed == Raw.Body.Empty => Left(invalidBody)
      case None    => Left(unknown(raw))

  def unknown(raw: Raw): Reply = unknownTxn(raw.sniffed.text("paymentId").getOrElse(""))

  /**
   * Verified, then cross-checked against the session (FR-013, new): the reference, the amount and,
   * where sent, the currency. A mismatch answers as a bad checksum does.
   */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    verify(raw, credential).flatMap(n => crossCheck(n, charge).map(_ => n)) match
      case Left(Rejected(reason)) => Handled(Verdict.Reject(reason), invalidChecksum)
      case Right(n)               => Handled(outcome(n), accepted(n.paymentId))

  private def crossCheck(n: Notification, charge: Charge): Either[Rejected, Unit] =
    if n.ref != charge.paymentId then Left(Rejected("reference does not match the session"))
    else if !Amounts.parse(n.amount, charge.currency).exists(_.value == charge.amount) then
      Left(Rejected("amount does not match the session"))
    else if n.currency.exists(!_.equalsIgnoreCase(charge.currency.code)) then
      Left(Rejected("currency does not match the session"))
    else Right(())
