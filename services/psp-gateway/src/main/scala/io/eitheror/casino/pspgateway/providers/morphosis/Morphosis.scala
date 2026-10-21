package io.eitheror.casino.pspgateway.providers.morphosis

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.{text, textOr}
import io.eitheror.casino.pspgateway.crypto.Underpaid
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Destination, Outcome, Provider, Variance}

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Morphosis (QVEI's WPB API), crypto deposits and payouts (`morphosis/adapter.go`,
 * `handler/morphosis.go`, `handler/payout.go:405-520`). QVEI signs nothing and its ids are sequential,
 * so a callback is admitted only on the per-session token the gateway put in its `notification_url`
 * (`morphosis/sessions.go:9-20`), and only when its `id` is the session's QVEI id.
 *
 * The webhook routes are `/v1/webhooks/morphosis/{deposit,withdrawal}/{token}`; `Raw` carries no path,
 * so the endpoint puts the path's token in the query as `token`, and `locate` reads it from there.
 *
 * `generated`: `token` (deposit and payout each). `providerData`: `wpbId` (QVEI's id) and
 * `expectedCoinAmount` (the quote). A payout's `providerReference` is its `wpbId`.
 */
object Morphosis extends Deposits with Payouts:
  val provider: Provider = Provider.Morphosis

  val TokenKey        = "token"
  val WpbIdKey        = "wpbId"
  val ExpectedCoinKey = "expectedCoinAmount"

  /** `adapter.go:26`: `insert_payout` takes no currency and reads the amount as USD. */
  val PayoutCurrency = "USD"

  /** `handler/webhook.go:591`. */
  val ReasonDeclined = "Payment declined"

  /** QVEI answers this, plain text, or retries every 60 s twenty times (`handler/morphosis.go:64-70`). */
  val Ok: Reply = Reply.text(200, "OK")

  /** A v4 UUID, 122 random bits (`sessions.go:22-23`). */
  override def generate(random: SecureRandom): Map[String, String] =
    val b = new Array[Byte](16)
    random.nextBytes(b)
    b(6) = ((b(6) & 0x0f) | 0x40).toByte
    b(8) = ((b(8) & 0x3f) | 0x80).toByte
    def long(from: Int) = (from until from + 8).foldLeft(0L)((acc, i) => (acc << 8) | (b(i) & 0xffL))
    Map(TokenKey -> UUID(long(0), long(8)).toString)

  private val TokenShape = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r

  /** `sessions.go:27-30`: the 36-character UUID shape, checked before any lookup. */
  def validToken(token: String): Boolean = TokenShape.matches(token)

  /** `adapter.go:64-123`: QVEI's method names, from docs.qvei.com; an unknown ticker lower-cased. */
  def method(ticker: String): String = ticker.trim.toUpperCase match
    case "BTC"     => "bitcoin"
    case "ETH"     => "ethereum"
    case "LTC"     => "litecoin"
    case "XRP"     => "ripple"
    case "DOGE"    => "dogecoin"
    case "BCH"     => "bitcoincash"
    case "BNB"     => "binance"
    case "AVAX"    => "avalanche"
    case "SOL"     => "solana"
    case "ADA"     => "cardano"
    case "TRX"     => "tron"
    case "XLM"     => "stellar"
    case "DASH"    => "dash"
    case "MATIC"   => "matic"
    case "USDT"    => "tether"
    case "USDTRX"  => "tether/trx"
    case "USDTSOL" => "tether/sol"
    case "USDC"    => "usdcoin"
    case "USDCSOL" => "usdcoin/sol"
    case "SHIB"    => "shibainu"
    case "LINK"    => "chainlink"
    case "APE"     => "apecoin"
    case "ZRX"     => "0x"
    case "PAX"     => "paxos"
    case "QUANTUM" => "quantum"
    case "SCOR"    => "scor"
    case "TRUMP"   => "trump"
    case _         => ticker.trim.toLowerCase

  /** `user` is the account when no `account` is configured (`adapter.go:199-202`). */
  private def account(credential: Credential, user: String) = credential.get("account").getOrElse(user)

  // ── Deposits ────────────────────────────────────────────────────────────────

  /**
   * `POST {base}?action=insert_deposit` (`adapter.go:186-219`): `amount` `%.2f`, `trace_id` the payment
   * id (R3), `notification_url` the session's webhook address, which carries the token.
   */
  def depositRequest(charge: Charge, credential: Credential, base: String): Either[String, Outbound] =
    (credential.get("user"), credential.get("pass")) match
      case (Some(user), Some(pass)) =>
        Right(Outbound.form(s"$base?action=insert_deposit", Form.of(
          "user"             -> user,
          "pass"             -> pass,
          "account"          -> account(credential, user),
          "amount"           -> Render.twoDecimals(charge.amount, charge.currency),
          "currency"         -> charge.currency.code,
          "method"           -> method(charge.method),
          "trace_id"         -> charge.paymentId,
          "notification_url" -> charge.webhookUrl
        )))
      case _ => Left("morphosis: missing required credentials (user, pass)")

  /**
   * `adapter.go:227-247`: a JSON body with `result=ok`, a non-zero `id` and an `address`; the coin amount
   * as QVEI wrote it. QVEI gives no deadline.
   */
  def destinationFrom(body: String, coin: String): Either[String, (Destination.Crypto, String)] =
    Json.parseObject(body).toRight("morphosis: decode response").flatMap { j =>
      if !j.text("result").contains("ok") then Left(s"morphosis: API error: ${j.textOr("detail", "")}")
      else
        j.text("id").filter(_ != "0") match
          case None => Left("morphosis: no id in response")
          case Some(id) =>
            j.text("address") match
              case None          => Left(s"morphosis: no address in response (id=$id)")
              case Some(address) => Right(Destination.Crypto(address, j.textOr("coin_amount", ""), coin, None) -> id)
    }

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    val result =
      for
        outbound <- depositRequest(charge, credential, base)
        answered <- call(outbound).left.map {
          case CallFailure.TimedOut         => "morphosis: request timed out"
          case CallFailure.Unreachable(why) => s"morphosis: request failed: $why"
        }
        quoted <- destinationFrom(answered.body, charge.method)
      yield quoted
    result match
      case Left(reason) => Initiation.Unavailable(reason)
      case Right((destination, wpbId)) =>
        Initiation.Quoted(destination, Some(wpbId), Map(WpbIdKey -> wpbId, ExpectedCoinKey -> destination.coinAmount))

  /** By the token the endpoint copied from the path into `token`; a malformed one is acknowledged. */
  def locate(raw: Raw): Either[Reply, Located] =
    raw.query.get(TokenKey).filter(validToken).map(Located.Indexed(_)).toRight(Ok)

  def unknown(raw: Raw): Reply = Ok

  /** A callback's fields, form or JSON by content type (`handler/morphosis.go:29-62`). */
  final case class Callback(id: Option[String], status: String, txHash: Option[String], message: Option[String],
      coinAmount: Option[String])

  def callback(raw: Raw): Option[Callback] =
    if raw.contentType.exists(_.contains("form")) then
      val f = raw.form
      Some(Callback(f.text("id"), f.get("status").getOrElse(""), f.text("tx_hash"), f.text("message"), f.text("coin_amount")))
    else
      Json.parseObject(raw.body).map { j =>
        Callback(j.text("id"), j.textOr("status", ""), j.text("tx_hash"), j.text("message"), j.text("coin_amount"))
      }

  /**
   * `handler/morphosis.go:78-126,188-236`: the route's token is the session's (constant time), and the
   * callback's `id` is the session's QVEI id. A deposit with no `wpbId` yet (claimed, not initiated) is
   * acknowledged and moves nothing (data-model.md); a payout's id is checked only when known, since its
   * answer may have been lost (`morphosis.go:178-180`).
   */
  def verify(token: Option[String], generated: Map[String, String], wpbId: Option[String], id: Option[String],
      required: Boolean): Either[Verdict, Unit] =
    val expected = generated.get(TokenKey)
    if !token.exists(validToken) then Left(Verdict.Reject("malformed notify token"))
    else if !expected.exists(e => Digests.same(e, token.get)) then Left(Verdict.Reject("notify token is not the session's"))
    else
      wpbId match
        case None if required                     => Left(Verdict.Ignore("session has no QVEI id yet"))
        case Some(known) if !id.contains(known)   => Left(Verdict.Reject("id does not match the session's QVEI id"))
        case _                                    => Right(())

  /**
   * `handler/morphosis.go:128-171`: `ac` captures unless `Underpaid` (the exact comparison; an unreadable
   * amount is short), which fails `underpaid` with the variance; `de` fails with `message`; else open.
   */
  def outcome(cb: Callback, charge: Charge): Verdict = cb.status match
    case "ac" =>
      val expected = charge.providerData.getOrElse(ExpectedCoinKey, "")
      if Underpaid(expected, cb.coinAmount) then
        val reason = s"Underpaid: received ${cb.coinAmount.getOrElse("")} of $expected expected"
        Verdict.Settle(Outcome.Failed(Some("underpaid"), reason,
          Some(Variance(Variance.Underpayment, Some(expected).filter(_.nonEmpty), cb.coinAmount))), cb.status)
      else Verdict.Settle(Outcome.Captured(), cb.status)
    case "de" => Verdict.Settle(Outcome.Failed(Some("de"), cb.message.getOrElse(ReasonDeclined)), cb.status)
    case other => Verdict.Open(other)

  /** Every exit answers `200 OK`: a forgery learns nothing (`morphosis.go:64-66`). */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    callback(raw) match
      case None => Handled(Verdict.Reject("unreadable body"), Ok)
      case Some(cb) =>
        verify(raw.query.get(TokenKey), charge.generated, charge.providerData.get(WpbIdKey), cb.id, required = true) match
          case Left(verdict) => Handled(verdict, Ok)
          case Right(())     => Handled(outcome(cb, charge), Ok)

  // ── Payouts ─────────────────────────────────────────────────────────────────

  /**
   * `POST {base}?action=insert_payout` (`adapter.go:293-315`): no currency and no request id; the token
   * in the notification address; `details` the memo or tag when there is one.
   */
  def payoutRequest(withdrawal: Withdrawal, credential: Credential, base: String): Either[PayoutAnswer, Outbound] =
    (credential.get("user"), credential.get("pass")) match
      case (Some(user), Some(pass)) =>
        if !withdrawal.currency.code.equalsIgnoreCase(PayoutCurrency) then Left(PayoutAnswer.Rejected("unsupported_currency"))
        else if !withdrawal.generated.get(TokenKey).exists(validToken) then
          Left(PayoutAnswer.InDoubt("morphosis payout: no notify token"))
        else
          val coin = withdrawal.destination.get("coin").map(_.trim).filter(_.nonEmpty).getOrElse(withdrawal.method)
          val memo = withdrawal.destination.get("memo").map(_.trim).filter(_.nonEmpty)
          Right(Outbound.form(s"$base?action=insert_payout", Form(Vector(
            "user"             -> user,
            "pass"             -> pass,
            "account"          -> account(credential, user),
            "amount"           -> Render.twoDecimals(withdrawal.amount, withdrawal.currency),
            "method"           -> method(coin),
            "address"          -> withdrawal.destination.getOrElse("address", ""),
            "notification_url" -> withdrawal.webhookUrl
          ) ++ memo.map("details" -> _))))
      case _ => Left(PayoutAnswer.Rejected("missing_credentials"))

  /**
   * `adapter.go:335-349`, `payout.go:499-512`: `result != ok` rejected with QVEI's `detail`; an unreadable
   * body or no id in doubt; else initiated with QVEI's id. A `401`/`403` is unauthorised whatever the
   * body (`PayoutAnswer.unauthorised`).
   */
  def payoutAnswer(answered: Answered): PayoutAnswer =
    Json.parseObject(answered.body) match
      case _ if PayoutAnswer.unauthorised(answered.status).nonEmpty => PayoutAnswer.unauthorised(answered.status).get
      case None => PayoutAnswer.InDoubt("morphosis payout: decode response")
      case Some(j) if !j.text("result").contains("ok") =>
        PayoutAnswer.Rejected(j.text("detail").getOrElse("upstream_rejection"))
      case Some(j) =>
        j.text("id").filter(_ != "0") match
          case None     => PayoutAnswer.InDoubt("morphosis payout: no id in response")
          case Some(id) => PayoutAnswer.Initiated(Some(id))

  def payout(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): PayoutAnswer =
    payoutRequest(withdrawal, credential, base) match
      case Left(answer) => answer
      case Right(outbound) =>
        call(outbound) match
          case Left(CallFailure.TimedOut)         => PayoutAnswer.InDoubt("morphosis payout: timed out")
          case Left(CallFailure.Unreachable(why)) => PayoutAnswer.InDoubt(s"morphosis payout: request failed: $why")
          case Right(answered)                    => payoutAnswer(answered)

  def locatePayout(raw: Raw): Either[Reply, Located] = locate(raw)

  def unknownPayout(raw: Raw): Reply = Ok

  /** `handler/morphosis.go:181-249`: the deposit's codes, `ac` completed and `de` failed. */
  def payoutWebhook(raw: Raw, withdrawal: Withdrawal, credential: Credential, now: Instant): Handled =
    callback(raw) match
      case None => Handled(Verdict.Reject("unreadable body"), Ok)
      case Some(cb) =>
        verify(raw.query.get(TokenKey), withdrawal.generated, withdrawal.providerReference, cb.id, required = false) match
          case Left(verdict) => Handled(verdict, Ok)
          case Right(()) =>
            val verdict = cb.status match
              case "ac"  => Verdict.Settle(Outcome.Completed, cb.status)
              case "de"  => Verdict.Settle(Outcome.PayoutFailed(Some("de"), cb.message.getOrElse(ReasonDeclined)), cb.status)
              case other => Verdict.Open(other)
            Handled(verdict, Ok)
