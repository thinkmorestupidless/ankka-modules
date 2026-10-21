package io.eitheror.casino.pspgateway.providers.cozeni

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.{text, textOr}
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Destination, Outcome, Provider, Variance}

import java.math.{BigDecimal as JBigDecimal, RoundingMode}
import java.security.SecureRandom
import java.time.Instant
import scala.util.Try

/**
 * Cozeni, crypto deposits and payouts (`cozeni/adapter.go`, `handler/webhook.go:845-1125,1263-1384`,
 * `handler/payout.go:71-185`). A deposit asks the public rate, then opens a pay-in for the fiat amount
 * divided by it; Cozeni answers an address. Every call and callback is authorised by
 * `txnauthz` = hex SHA-256 of `merchantId + salt + paymentRef + apiKey` (`cozeni/signature.go:9-31`),
 * with the salt the gateway generated for the session.
 *
 * `generated`: `salt`. `providerData`: `cozeniTxnId` (Cozeni's id) and `cryptoAmount` (the quote, the
 * variance's `quoted`).
 */
object Cozeni extends Deposits with Payouts:
  val provider: Provider = Provider.Cozeni

  val SaltKey         = "salt"
  val TxnIdKey        = "cozeniTxnId"
  val CryptoAmountKey = "cryptoAmount"

  /** `cozeni/adapter.go:252-257`: what a `COMPLETED` pay-in's result code says of the amount. */
  val Overpayment  = "02"
  val Underpayment = "03"

  /** `handler/webhook.go:591,596`. */
  val ReasonDeclined = "Payment declined"
  val ReasonUnderpaymentUnpriced =
    "Less crypto arrived than requested and its value was not reported — under review"

  /**
   * The per-session salt. The original sent the clock's milliseconds (`adapter.go:156`), 13 digits; this
   * is 13 random digits of the same shape, made at claim so a callback can be verified whatever happens
   * to the call.
   */
  override def generate(random: SecureRandom): Map[String, String] =
    Map(SaltKey -> (1000000000000L + random.nextLong(9000000000000L)).toString)

  /** `signature.go:11-14`. */
  def txnauthz(merchantId: String, salt: String, paymentRef: String, apiKey: String): String =
    Digests.sha256Hex(merchantId + salt + paymentRef + apiKey)

  // ── Deposits ────────────────────────────────────────────────────────────────

  /** `GET {base}/pub/rate/{crypto}/{fiat}`: how many fiat units one coin is worth (`adapter.go:67-101`). */
  def rateRequest(base: String, crypto: String, fiat: Currency): Outbound =
    Outbound.get(s"${base.stripSuffix("/")}/pub/rate/$crypto/${fiat.code}")

  /** `{"rate": n}`, or a bare number; only a positive rate (`adapter.go:89-100`). */
  def rateFrom(answered: Answered): Either[String, JBigDecimal] =
    def positive(d: JBigDecimal) = Option(d).filter(_.signum > 0)
    if answered.status != 200 then Left(s"unexpected HTTP ${answered.status} from exchange rate endpoint")
    else
      answered.json.flatMap(j => Option(j.get("rate"))).filter(_.isNumber).map(_.decimalValue).flatMap(positive)
        .orElse(Try(JBigDecimal(answered.body.trim)).toOption.flatMap(positive))
        .toRight("could not parse exchange rate response")

  /**
   * `POST {base}/mxapi/{merchantId}/payintxn` (`adapter.go:159-182`): `requestAmount` is the fiat amount
   * divided by the rate, to 8 places; `paymentRef` the payment id (R3).
   */
  def payinRequest(charge: Charge, credential: Credential, salt: String, rate: JBigDecimal, base: String): Either[String, Outbound] =
    for
      accountId  <- credential.get("accountId").toRight("cozeni: missing required credentials (accountId, apiKey)")
      apiKey     <- credential.get("apiKey").toRight("cozeni: missing required credentials (accountId, apiKey)")
      merchantId <- credential.get("merchantId").toRight("cozeni: missing merchantId credential")
    yield
      val fiat = Render.decimalNumber(charge.amount, charge.currency)
      val coin = fiat.divide(rate, 8, RoundingMode.HALF_EVEN)
      val body = Json.obj(
        "accountId"       -> accountId,
        "requestSalt"     -> salt,
        "requestAmount"   -> coin,
        "requestCurrency" -> charge.method,
        "convertCurrency" -> charge.currency.code,
        "paymentRef"      -> charge.paymentId,
        "paymentDesc"     -> s"deposit ${charge.paymentId}",
        "callbackUrl"     -> charge.webhookUrl,
        "dupCheck"        -> true
      )
      Outbound.json(s"${base.stripSuffix("/")}/mxapi/$merchantId/payintxn", body,
        "X-cozeni-txnauthz" -> txnauthz(merchantId, salt, charge.paymentId, apiKey))

  /** The pay-in's answer: `paymentAddress` required, the invoiced coin amount to 8 places (`adapter.go:190-246`). */
  def destinationFrom(answered: Answered, coin: String): Either[String, (Destination.Crypto, Option[String])] =
    if answered.status == 422 then
      answered.json match
        case Some(e) => Left(s"cozeni: pay-in rejected ${e.textOr("msgCode", "")}: ${e.textOr("msgText", "")}")
        case None    => Left("cozeni: pay-in rejected (HTTP 422), could not decode error")
    else if answered.status != 200 then Left(s"cozeni: pay-in returned unexpected HTTP ${answered.status}")
    else
      answered.json.filter(_.isObject).toRight("cozeni: failed to decode pay-in response").flatMap { j =>
        j.text("paymentAddress") match
          case None =>
            Left(s"cozeni: pay-in response missing paymentAddress (resultCode=${j.textOr("resultCode", "")})")
          case Some(address) =>
            val amount = Option(j.get("requestAmount")).filter(_.isNumber).map(n => Render.coin(n.decimalValue))
              .getOrElse(Render.coin(JBigDecimal.ZERO))
            Right(Destination.Crypto(address, amount, coin, j.text("validUntil")) -> j.text("txnId"))
      }

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    def failed(f: CallFailure) = f match
      case CallFailure.TimedOut          => "timed out"
      case CallFailure.Unreachable(why)  => why
    charge.generated.get(SaltKey) match
      case None => Initiation.Unavailable("cozeni: no salt generated for the session")
      case Some(salt) =>
        val result =
          for
            rateAnswer <- call(rateRequest(base, charge.method, charge.currency)).left.map(failed)
            rate       <- rateFrom(rateAnswer)
              .left.map(why => s"cozeni: could not determine exchange rate for ${charge.method}/${charge.currency.code}: $why")
            outbound   <- payinRequest(charge, credential, salt, rate, base)
            answered   <- call(outbound).left.map(f => s"cozeni: pay-in request failed: ${failed(f)}")
            quoted     <- destinationFrom(answered, charge.method)
          yield quoted
        result match
          case Left(reason) => Initiation.Unavailable(reason)
          case Right((destination, txnId)) =>
            Initiation.Quoted(destination, txnId,
              Map(CryptoAmountKey -> destination.coinAmount) ++ txnId.map(TxnIdKey -> _))

  /** Deposits are found by `paymentRef`, the payment id (`webhook.go:1022-1053`); none named is acknowledged. */
  def locate(raw: Raw): Either[Reply, Located] =
    raw.form.text("paymentRef").map(Located.Deposit(_)).toRight(Reply.ok)

  /** `webhook.go:1048-1051`: an unknown reference is answered `200` so Cozeni stops retrying. */
  def unknown(raw: Raw): Reply = Reply.ok

  /**
   * `webhook.go:849-878`: the `txnauthz` body field against the session's salt and the credential's
   * `merchantId` and `apiKey`, constant time; fails closed when any is missing.
   */
  def verify(form: Form, credential: Credential, salt: Option[String], paymentRef: String): Either[String, Unit] =
    for
      received   <- form.text("txnauthz").toRight("no txnauthz in callback body")
      salt       <- salt.filter(_.nonEmpty).toRight("session carries no salt")
      merchantId <- credential.get("merchantId").toRight("credential has no merchantId")
      apiKey     <- credential.get("apiKey").toRight("credential has no apiKey")
      _          <- Either.cond(Digests.same(txnauthz(merchantId, salt, paymentRef, apiKey), received), (), "invalid txnauthz")
    yield ()

  /** `cozeni/adapter.go:261-271`: `COMPLETED`, `FAILED`/`CANCELLED`, any other case-insensitively open. */
  enum Status:
    case Completed, Failed, Open

  def status(word: String): Status = word.trim.toUpperCase match
    case "COMPLETED"            => Status.Completed
    case "FAILED" | "CANCELLED" => Status.Failed
    case _                      => Status.Open

  /** `webhook.go:639-642`: a convert amount that reads as a number above zero. */
  private def positive(amount: Option[String]): Boolean =
    amount.flatMap(a => Try(JBigDecimal(a)).toOption).exists(_.signum > 0)

  /**
   * `webhook.go:1059-1125`: `COMPLETED` captures, with an `OVERPAYMENT` variance on `02` and an
   * `UNDERPAYMENT` one on `03` when Cozeni valued what arrived (`convertAmount` > 0); an unvalued `03`
   * fails with code `03`, carrying what was reported. `FAILED`/`CANCELLED` fail with `resultMsg`.
   */
  def outcome(form: Form, charge: Charge): Verdict =
    val word = form.get("status").getOrElse("")
    val code = form.text("resultCode")
    def variance(kind: String) = Variance(kind, charge.providerData.get(CryptoAmountKey),
      form.text("receivedAmount"), form.text("convertAmount"), form.text("convertCurrency"))
    status(word) match
      case Status.Open => Verdict.Open(word)
      case Status.Failed =>
        Verdict.Settle(Outcome.Failed(code, form.text("resultMsg").getOrElse(ReasonDeclined)), word)
      case Status.Completed =>
        code match
          case Some(Underpayment) if !positive(form.text("convertAmount")) =>
            Verdict.Settle(Outcome.Failed(code, ReasonUnderpaymentUnpriced, Some(variance(Variance.Underpayment))), word)
          case Some(Underpayment) => Verdict.Settle(Outcome.Captured(Some(variance(Variance.Underpayment))), word)
          case Some(Overpayment)  => Verdict.Settle(Outcome.Captured(Some(variance(Variance.Overpayment))), word)
          case _                  => Verdict.Settle(Outcome.Captured(), word)

  /**
   * `200`, empty: Cozeni retries anything else (`webhook.go:992`). A callback that does not verify is
   * `400` (`Reply.invalidSignature`), so Cozeni retries it.
   */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    val form = raw.form
    verify(form, credential, charge.generated.get(SaltKey), charge.paymentId) match
      case Left(reason) => Handled(Verdict.Reject(reason), Reply.invalidSignature)
      case Right(())    => Handled(outcome(form, charge), Reply.ok)

  // ── Payouts ─────────────────────────────────────────────────────────────────

  /**
   * `POST {base}/mxapi/{merchantId}/payouttxn` (`adapter.go:329-357`): the fiat `amount` (Cozeni converts),
   * `payoutCurrency` the coin, `paymentRef` the request id, the same `txnauthz` with the payout's salt.
   */
  def payoutRequest(withdrawal: Withdrawal, credential: Credential, salt: String, base: String): Either[String, Outbound] =
    for
      accountId  <- credential.get("accountId").toRight("missing_credentials")
      apiKey     <- credential.get("apiKey").toRight("missing_credentials")
      merchantId <- credential.get("merchantId").toRight("missing_credentials")
    yield
      val coin = withdrawal.destination.get("coin").map(_.trim).filter(_.nonEmpty).getOrElse(withdrawal.method)
      val body = Json.obj(
        "accountId"      -> accountId,
        "requestSalt"    -> salt,
        "amount"         -> Render.decimalNumber(withdrawal.amount, withdrawal.currency),
        "currency"       -> withdrawal.currency.code,
        "payoutCurrency" -> coin,
        "paymentRef"     -> withdrawal.requestId,
        "paymentDesc"    -> s"withdrawal ${withdrawal.requestId}",
        "callbackUrl"    -> withdrawal.webhookUrl,
        "paymentAddress" -> withdrawal.destination.getOrElse("address", ""),
        "dupCheck"       -> true
      )
      Outbound.json(s"${base.stripSuffix("/")}/mxapi/$merchantId/payouttxn", body,
        "X-cozeni-txnauthz" -> txnauthz(merchantId, salt, withdrawal.requestId, apiKey))

  /**
   * `adapter.go:313-322,365-389` and `payout.go:149-153`: `00` completed, `01` initiated, any other code
   * rejected; a `401`/`403` unauthorised (`PayoutAnswer.unauthorised`); a `422`, another status or an
   * unreadable body is an error there, so in doubt here.
   */
  def payoutAnswer(answered: Answered): PayoutAnswer =
    PayoutAnswer.unauthorised(answered.status).getOrElse(payoutBody(answered))

  private def payoutBody(answered: Answered): PayoutAnswer =
    if answered.status == 422 then PayoutAnswer.InDoubt("cozeni payout: rejected (HTTP 422)")
    else if answered.status != 200 then PayoutAnswer.InDoubt(s"cozeni payout: unexpected HTTP ${answered.status}")
    else
      answered.json.filter(_.isObject) match
        case None => PayoutAnswer.InDoubt("cozeni payout: decode response")
        case Some(j) =>
          val txnId = j.text("txnId")
          j.textOr("resultCode", "") match
            case "00" => PayoutAnswer.Completed(txnId)
            case "01" => PayoutAnswer.Initiated(txnId)
            case code => PayoutAnswer.Rejected(j.text("resultMsg").getOrElse(s"resultCode $code"))

  def payout(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): PayoutAnswer =
    withdrawal.generated.get(SaltKey) match
      case None => PayoutAnswer.Rejected("no salt generated for the payout")
      case Some(salt) =>
        payoutRequest(withdrawal, credential, salt, base) match
          case Left(reason) => PayoutAnswer.Rejected(reason)
          case Right(outbound) =>
            call(outbound) match
              case Left(CallFailure.TimedOut)         => PayoutAnswer.InDoubt("cozeni payout: timed out")
              case Left(CallFailure.Unreachable(why)) => PayoutAnswer.InDoubt(s"cozeni payout: request failed: $why")
              case Right(answered)                    => payoutAnswer(answered)

  /** `webhook.go:1296-1300`: by `paymentRef`, the request id. */
  def locatePayout(raw: Raw): Either[Reply, Located] =
    raw.form.text("paymentRef").map(Located.Payout(_)).toRight(Reply.ok)

  def unknownPayout(raw: Raw): Reply = Reply.ok

  /**
   * `webhook.go:1352-1381`: verified as a deposit is, with the payout's salt; `COMPLETED` completes,
   * `FAILED`/`CANCELLED` fail, any other is open.
   */
  def payoutWebhook(raw: Raw, withdrawal: Withdrawal, credential: Credential, now: Instant): Handled =
    val form = raw.form
    verify(form, credential, withdrawal.generated.get(SaltKey), withdrawal.requestId) match
      case Left(reason) => Handled(Verdict.Reject(reason), Reply.invalidSignature)
      case Right(()) =>
        val word = form.get("status").getOrElse("")
        val verdict = status(word) match
          case Status.Completed => Verdict.Settle(Outcome.Completed, word)
          case Status.Failed =>
            Verdict.Settle(Outcome.PayoutFailed(form.text("resultCode"), form.text("resultMsg").getOrElse(ReasonDeclined)), word)
          case Status.Open => Verdict.Open(word)
        Handled(verdict, Reply.ok)
