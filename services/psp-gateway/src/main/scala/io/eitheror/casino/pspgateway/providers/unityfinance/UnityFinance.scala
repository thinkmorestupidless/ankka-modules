package io.eitheror.casino.pspgateway.providers.unityfinance

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.http.{Form, Raw}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.time.Instant
import scala.util.Try

/**
 * Unity Finance (`unityfinance/adapter.go`, `handler/webhook.go:249-415`, `handler/payout.go:186-391`,
 * `handler/withdrawal_webhook.go`): a server-side invoice answered with the payment page, an `ik_*`
 * webhook, eubanking payouts, a payout status read and a withdrawal webhook.
 *
 * Two base URLs (research R10): `initiate`'s `base` is `UNITYFINANCE_PAY_URL`, posted to as it is
 * (`adapter.go:163`); `payout`'s and `status`'s `base` is `UNITYFINANCE_API_URL`, under which
 * `/withdraw` is called (`adapter.go:414`, `:563`).
 *
 * `providerData` on a deposit: `checkoutId`, the `ik_co_id` the invoice was made under, which the
 * webhook must name (`webhook.go:357-364`).
 */
object UnityFinance extends Deposits with Payouts:
  val provider: Provider = Provider.UnityFinance

  override def generate(@scala.annotation.unused random: SecureRandom): Map[String, String] = Map.empty

  /** The `providerData` key holding the invoice's checkout id. */
  val CheckoutIdKey = "checkoutId"

  /** The documented webhook source addresses: advisory, logged on a mismatch, never refused (`webhook.go:249-254`). */
  val webhookIps: Set[String] = Set("34.34.83.66", "34.32.253.200")
  def knownSource(ip: String): Boolean = webhookIps.contains(ip.trim)

  // ---- signature (`signature.go`)

  /**
   * Base64 of the SHA-256 of the values in key order joined by `:`, then `:` and the sign key
   * (`signature.go:20-35`). The caller leaves `ik_sign` out.
   */
  def sign(params: Map[String, String], signKey: String): String =
    val values = params.toVector.sortBy(_._1).map(_._2)
    Digests.base64(Digests.sha256(values.mkString(":") + ":" + signKey))

  /**
   * Whether a webhook's form carries a valid `ik_sign`: the signature over every field but `ik_sign`,
   * the first value of a repeated field as Go's `r.Form` gives it (`webhook.go:344-356`), compared in
   * constant time.
   */
  def verify(form: Form, signKey: String): Boolean =
    val params = form.without("ik_sign").values.collect { case (k, v +: _) => k -> v }
    Digests.same(sign(params, signKey), form.get("ik_sign").getOrElse(""))

  // ---- methods (`adapter.go:228-292`)

  /** The `ik_payment_method` payway for a method, the legacy `CARD_` names included. */
  def methodCode(method: String): Option[String] = method match
    case "APPLE_PAY"                => Some("apay")
    case "GOOGLE_PAY"               => Some("gpay")
    case "OPEN_BANKING"             => Some("eubanking")
    case "VISA" | "CARD_VISA"       => Some("visa")
    case "MASTERCARD" | "CARD_MASTERCARD" => Some("mastercard")
    case "CARD_TEST"                => Some("card")
    case "MBWAY"                    => Some("mbway")
    case "SKRILL"                   => Some("skrill")
    case "NETELLER"                 => Some("neteller")
    case "PAYSAFECARD"              => Some("paysafecard")
    case "RAPID"                    => Some("rapid")
    case _                          => None

  /** Cards carry the full identity and address (`adapter.go:285-292`). */
  def isCardMethod(method: String): Boolean =
    Set("VISA", "MASTERCARD", "CARD_VISA", "CARD_MASTERCARD", "CARD_TEST").contains(method)

  /** Wallets that need email, name and country (`adapter.go:273-280`). */
  def requiresWalletIdentity(method: String): Boolean = Set("SKRILL", "NETELLER", "PAYSAFECARD", "RAPID").contains(method)

  /** Open banking needs the payer's name and none of the card fields (`adapter.go:267-269`). */
  def requiresPayerName(method: String): Boolean = method == "OPEN_BANKING"

  // ---- deposits

  /**
   * The invoice form, signed (`adapter.go:59-156`). The checkout id is read exactly as
   * `checkoutId.{METHOD}`, with no fallback (`sessions_test.go` / `adapter_test.go:163-172`). A missing
   * credential or an unknown method is refused before any call, naming the key, as the original
   * errors (`adapter.go:59-71`). Empty `ik_customer_email` is sent and signed, as the original does.
   */
  def invoice(charge: Charge, credential: Credential, customer: Customer): Either[Initiation, Form] =
    val method = charge.method
    for
      checkoutId <- credential.get(s"checkoutId.$method")
        .toRight(Initiation.Refused("unsupported_method", s"unityfinance: missing credential checkoutId.$method"))
      signKey <- credential.get("signKey")
        .toRight(Initiation.Refused("unsupported_method", "unityfinance: missing credential signKey"))
      ikMethod <- methodCode(method)
        .toRight(Initiation.Refused("unsupported_method", s"unityfinance: unsupported method \"$method\""))
    yield
      val ref = charge.paymentId
      val base = Map(
        "ik_co_id"            -> checkoutId,
        "ik_pm_no"            -> ref,
        "ik_am"               -> Render.twoDecimals(charge.amount, charge.currency),
        "ik_cur"              -> charge.currency.code,
        "ik_desc"             -> s"payment $ref",
        "ik_payment_method"   -> ikMethod,
        "ik_payment_currency" -> charge.currency.code,
        "ik_act"              -> "process",
        "ik_int"              -> "json",
        "ik_ia_u"             -> charge.webhookUrl,
        "ik_suc_u"            -> charge.returnUrl,
        "ik_fal_u"            -> charge.returnUrl,
        "ik_customer_email"   -> customer.email.getOrElse("")
      )
      val identity: Vector[(String, Option[String])] =
        if isCardMethod(method) then
          Vector(
            "ik_customer_first_name" -> customer.firstName, "ik_customer_last_name" -> customer.lastName,
            "ik_customer_phone" -> customer.phone, "ik_customer_ip" -> customer.ip,
            "ik_customer_country" -> customer.country, "ik_customer_city" -> customer.city,
            "ik_customer_address" -> customer.address, "ik_customer_post_code" -> customer.postCode,
            "ik_customer_state" -> customer.state
          )
        else if requiresWalletIdentity(method) then
          Vector("ik_customer_first_name" -> customer.firstName, "ik_customer_last_name" -> customer.lastName,
            "ik_customer_country" -> customer.country)
        else if requiresPayerName(method) then
          Vector("ik_customer_first_name" -> customer.firstName, "ik_customer_last_name" -> customer.lastName)
        else Vector.empty
      val params = base ++ identity.collect { case (k, Some(v)) if v.nonEmpty => k -> v }
      Form((params + ("ik_sign" -> sign(params, signKey))).toVector.sortBy(_._1))

  /**
   * The player's page from the invoice answer (`adapter.go:175-205`): `resultCode` 0 and
   * `resultData.paymentForm.action` required; a `get` form's `parameters` appended as a query.
   */
  def redirectFrom(json: JsonNode): Either[String, String] =
    val code = json.long("resultCode").getOrElse(0L)
    val form = Option(json.get("resultData")).flatMap(d => Option(d.get("paymentForm")))
    if code != 0 then Left(s"invoice refused: resultCode $code: ${json.textOr("resultMsg", "")}")
    else
      form.flatMap(_.text("action")) match
        case None => Left(s"invoice answer has no paymentForm action (resultCode $code)")
        case Some(action) =>
          val params = form.flatMap(f => Option(f.get("parameters")).filter(_.isObject)).map { p =>
            import scala.jdk.CollectionConverters.*
            p.properties.asScala.toVector.map(e => e.getKey -> Json.scalar(e.getValue))
          }.getOrElse(Vector.empty)
          if form.flatMap(_.text("method")).contains("get") && params.nonEmpty then Right(s"$action?${Form(params).encode}")
          else Right(action)

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    invoice(charge, credential, customer) match
      case Left(refused) => refused
      case Right(form) =>
        call(Outbound.form(base, form)) match
          case Left(CallFailure.TimedOut)        => Initiation.Unavailable("unityfinance: invoice timed out")
          case Left(CallFailure.Unreachable(why)) => Initiation.Unavailable(s"unityfinance: invoice failed: $why")
          case Right(answer) if !answer.ok        => Initiation.Unavailable(s"unityfinance: invoice answered HTTP ${answer.status}")
          case Right(answer) =>
            answer.json.filter(_.isObject) match
              case None => Initiation.Unavailable(s"unityfinance: invoice answer unreadable (HTTP ${answer.status})")
              case Some(json) =>
                redirectFrom(json) match
                  case Left(why) => Initiation.Unavailable(s"unityfinance: $why")
                  case Right(url) =>
                    Initiation.Redirected(url, None, Map(CheckoutIdKey -> form.get("ik_co_id").getOrElse("")))

  /** `ik_pm_no` is the payment id (research R3); a webhook without one is acknowledged (`webhook.go:278`, `:322-326`). */
  def locate(raw: Raw): Either[Reply, Located] =
    raw.form.text("ik_pm_no").map(Located.Deposit(_)).toRight(Reply.ok)

  /** Unity Finance retries 13 times until `200`: every answer is `200`, empty (`webhook.go:258-259`). */
  def unknown(raw: Raw): Reply = Reply.ok

  /** The open states and the logged ones (`webhook.go:396-401`). */
  val openStates: Set[String]   = Set("on_review", "process", "wait_accept", "new", "chargeback", "blocked")
  val loggedStates: Set[String] = Set("refunded", "partial_refunded", "chargebacked")

  /**
   * `ik_inv_st` to a verdict (`webhook.go:367-403`): `success` captured; `canceled` and `expired`
   * failed, coded `ik_inv_cancel_code` or the state, with `ik_error_desc` or the original's reasons.
   */
  def outcome(form: Form): Verdict =
    val state = form.get("ik_inv_st").getOrElse("")
    state match
      case "success" => Verdict.Settle(Outcome.Captured(), state)
      case "canceled" | "expired" =>
        val reason = form.text("ik_error_desc").getOrElse(if state == "expired" then "Payment session expired" else "Payment cancelled")
        Verdict.Settle(Outcome.Failed(Some(form.text("ik_inv_cancel_code").getOrElse(state)), reason), state)
      case s if openStates(s)   => Verdict.Open(s)
      case s if loggedStates(s) => Verdict.Ignore(s"refund or chargeback status $s is not handled")
      case s                    => Verdict.Ignore(s"unknown ik_inv_st \"$s\"")

  /**
   * Verify, cross-check, read (research R8). The checkout id must be the invoice's: the recorded one,
   * or before the initiation is recorded the credential's for the session's method (the original
   * checked only when its session had one, `webhook.go:357-364`). The amount and currency are checked
   * where the webhook names them (FR-013, new). A webhook that does not verify, or does not match its
   * session, is `400` (`Reply.invalidSignature`); the rest `200`.
   */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    val form = raw.form
    credential.get("signKey") match
      case None => Handled(Verdict.Reject("missing credential signKey"), Reply.invalidSignature)
      case Some(signKey) if !verify(form, signKey) => Handled(Verdict.Reject("invalid signature"), Reply.invalidSignature)
      case Some(_) =>
        val expected = charge.providerData.get(CheckoutIdKey).filter(_.nonEmpty)
          .orElse(credential.get(s"checkoutId.${charge.method}"))
        val verdict =
          if expected.exists(id => !form.get("ik_co_id").contains(id)) then Verdict.Reject("ik_co_id is not the session's checkout id")
          else if form.text("ik_cur").exists(!_.equalsIgnoreCase(charge.currency.code)) then Verdict.Reject("ik_cur is not the session's currency")
          else if form.text("ik_am").exists(a => !sameAmount(a, charge)) then Verdict.Reject("ik_am is not the session's amount")
          else outcome(form)
        verdict match
          case Verdict.Reject(_) => Handled(verdict, Reply.invalidSignature)
          case _                 => Handled(verdict, Reply.ok)

  private def sameAmount(text: String, charge: Charge): Boolean =
    Try(java.math.BigDecimal(text.trim)).toOption.exists(_.compareTo(java.math.BigDecimal(Render.twoDecimals(charge.amount, charge.currency))) == 0)

  // ---- payouts

  private def basic(userId: String, apiKey: String): String = "Basic " + Digests.base64(s"$userId:$apiKey".getBytes(UTF_8))

  /** `userId`, `withdrawalApiKey` and `accountId`, or the first key missing. */
  private def apiCredential(credential: Credential): Either[String, (String, String, String)] =
    for
      userId    <- credential.get("userId").toRight("userId")
      apiKey    <- credential.get("withdrawalApiKey").toRight("withdrawalApiKey")
      accountId <- credential.get("accountId").toRight("accountId")
    yield (userId, apiKey, accountId)

  /**
   * The eubanking withdrawal (`adapter.go:392-421`): `paymentNo` the request id, the destination's IBAN
   * and names, `details[email]` only when there is one, `interactionUrl` the payout's webhook. A
   * missing credential is `rejected` / `missing_credentials` before any call (`payout.go:260-265`).
   */
  def payoutRequest(withdrawal: Withdrawal, credential: Credential, base: String): Either[PayoutAnswer, Outbound] =
    for
      (userId, apiKey, accountId) <- apiCredential(credential).left.map(_ => PayoutAnswer.Rejected("missing_credentials"))
      purseId <- credential.get("purseId").toRight(PayoutAnswer.Rejected("missing_credentials"))
    yield
      val d = withdrawal.destination
      val fields = Vector(
        "amount"              -> Render.twoDecimals(withdrawal.amount, withdrawal.currency),
        "purseId"             -> purseId,
        "action"              -> "process",
        "calcKey"             -> "psPayeeAmount",
        "paymentNo"           -> withdrawal.requestId,
        "useShortAlias"       -> "true",
        "method"              -> "eubanking",
        "currency"            -> withdrawal.currency.code,
        "details[first_name]" -> d.getOrElse("firstName", ""),
        "details[last_name]"  -> d.getOrElse("lastName", ""),
        "details[iban]"       -> d.getOrElse("iban", "")
      ) ++ d.get("email").map(_.trim).filter(_.nonEmpty).map("details[email]" -> _) ++
        Option(withdrawal.webhookUrl).filter(_.nonEmpty).map("interactionUrl" -> _)
      Outbound.form(s"${base.stripSuffix("/")}/withdraw", Form(fields),
        "Authorization" -> basic(userId, apiKey), "Ik-Api-Account-Id" -> accountId)

  /**
   * The withdrawal answer (`adapter.go:429-497`). `401`/`403` is `rejected` / `unauthorised` whatever
   * the body (the original decoded first, so a non-JSON `401` was in doubt: research finding 8). Any
   * other non-2xx or unreadable answer is in doubt. `status:ok` with `data.withdraw.id` is initiated,
   * whatever its `state`; an error naming a duplicate is already initiated; other errors rejected.
   */
  def payoutAnswer(status: Int, body: String): PayoutAnswer =
    if status == 401 || status == 403 then PayoutAnswer.Rejected("unauthorised")
    else if status / 100 != 2 then PayoutAnswer.InDoubt(s"unityfinance: withdraw answered HTTP $status")
    else
      Json.parseObject(body.trim) match
        case None => PayoutAnswer.InDoubt(s"unityfinance: withdraw answer unreadable (HTTP $status)")
        case Some(json) =>
          val id = Option(json.get("data")).flatMap(d => Option(d.get("withdraw"))).filter(_.isObject).flatMap(_.text("id"))
          json.text("status") match
            case Some("ok") if id.isDefined => PayoutAnswer.Initiated(id)
            case Some("error") =>
              val message = json.textOr("message", "")
              val lower   = message.toLowerCase
              if lower.contains("duplicate") || lower.contains("already exist") then PayoutAnswer.AlreadyInitiated
              else PayoutAnswer.Rejected(message)
            case _ => PayoutAnswer.InDoubt("unityfinance: unrecognised withdraw answer")

  def payout(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): PayoutAnswer =
    payoutRequest(withdrawal, credential, base) match
      case Left(answer) => answer
      case Right(outbound) =>
        call(outbound) match
          case Left(CallFailure.TimedOut)         => PayoutAnswer.InDoubt("unityfinance: withdraw timed out")
          case Left(CallFailure.Unreachable(why)) => PayoutAnswer.InDoubt(s"unityfinance: withdraw failed: $why")
          case Right(answer)                      => payoutAnswer(answer.status, answer.body)

  /** `GET {API}/withdraw?paymentNo=`, the request id escaped (`adapter.go:560-569`). */
  def statusRequest(withdrawal: Withdrawal, credential: Credential, base: String): Either[PayoutStatus, Outbound] =
    apiCredential(credential) match
      case Left(key) => Left(PayoutStatus.Unavailable(s"missing credential $key"))
      case Right((userId, apiKey, accountId)) =>
        Right(Outbound.get(s"${base.stripSuffix("/")}/withdraw?paymentNo=${Form.escape(withdrawal.requestId)}",
          "Authorization" -> basic(userId, apiKey), "Ik-Api-Account-Id" -> accountId))

  /**
   * The status answer (`adapter.go:583-611`, `mapUFStatus` `:505-516`): `data: []` is no record, the
   * only answer that says so; the first record's `state` (a quoted number) `8` completed, `9`/`20`
   * failed, `11` reversed, anything else pending. A non-2xx, an error envelope, or a `data` that is not
   * an array is unavailable (the original read an absent `data` as no record).
   */
  def payoutStatus(status: Int, body: String): PayoutStatus =
    if status / 100 != 2 then PayoutStatus.Unavailable(s"unityfinance: status answered HTTP $status")
    else
      Json.parseObject(body.trim) match
        case None => PayoutStatus.Unavailable(s"unityfinance: status answer unreadable (HTTP $status)")
        case Some(json) if json.text("status").contains("error") =>
          PayoutStatus.Unavailable(s"unityfinance: status refused: ${json.textOr("message", "")}")
        case Some(json) =>
          Option(json.get("data")).filter(_.isArray) match
            case None => PayoutStatus.Unavailable("unityfinance: status answer has no data array")
            case Some(data) if data.isEmpty => PayoutStatus.NoRecord
            case Some(data) =>
              val record = data.get(0)
              val state  = record.textOr("state", "")
              state match
                case "8"        => PayoutStatus.Final(Outcome.Completed, state)
                case "9" | "20" => PayoutStatus.Final(Outcome.PayoutFailed(Some(state), record.textOr("stateName", "Payout failed")), state)
                case "11"       => PayoutStatus.Final(Outcome.Reversed, state)
                case _          => PayoutStatus.Pending(state)

  override def status(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): Option[PayoutStatus] =
    Some(statusRequest(withdrawal, credential, base) match
      case Left(unavailable) => unavailable
      case Right(outbound) =>
        call(outbound) match
          case Left(CallFailure.TimedOut)         => PayoutStatus.Unavailable("unityfinance: status timed out")
          case Left(CallFailure.Unreachable(why)) => PayoutStatus.Unavailable(s"unityfinance: status failed: $why")
          case Right(answer)                      => payoutStatus(answer.status, answer.body))

  /** `paymentNo` is the request id (`withdrawal_webhook.go:63`, research R4); the query is not read. */
  def locatePayout(raw: Raw): Either[Reply, Located] =
    raw.form.text("paymentNo").map(Located.Payout(_)).toRight(Reply.ok)

  def unknownPayout(raw: Raw): Reply = Reply.ok

  /**
   * The withdrawal webhook's `state`, a name (`MapWithdrawalStateName`, `adapter.go:545-556`):
   * `success` completed, `canceled`/`limitExceeded` failed, `chargebacked` reversed, the rest open.
   */
  def withdrawalOutcome(form: Form): Verdict =
    val state = form.get("state").getOrElse("")
    state match
      case "success"       => Verdict.Settle(Outcome.Completed, state)
      case "canceled"      => Verdict.Settle(Outcome.PayoutFailed(Some(state), "Payout cancelled"), state)
      case "limitExceeded" => Verdict.Settle(Outcome.PayoutFailed(Some(state), "Payout limit exceeded"), state)
      case "chargebacked"  => Verdict.Settle(Outcome.Reversed, state)
      case s               => Verdict.Open(s)

  /**
   * Signed over the body alone, never the query (`withdrawal_webhook.go:108-124`); `id` must be the
   * provider reference the payout recorded, where it recorded one (FR-013, new). A webhook that does
   * not verify, or names another payout, is `400` as a deposit's is (the 2026-10-20 decision), so the
   * provider retries it rather than taking it as received.
   */
  def payoutWebhook(raw: Raw, withdrawal: Withdrawal, credential: Credential, now: Instant): Handled =
    val form = raw.form
    credential.get("signKey") match
      case None => Handled(Verdict.Reject("missing credential signKey"), Reply.invalidSignature)
      case Some(signKey) if !verify(form, signKey) => Handled(Verdict.Reject("invalid signature"), Reply.invalidSignature)
      case Some(_) if withdrawal.providerReference.exists(r => form.text("id").exists(_ != r)) =>
        Handled(Verdict.Reject("id is not the payout's provider reference"), Reply.invalidSignature)
      case Some(_) => Handled(withdrawalOutcome(form), Reply.ok)
