package io.eitheror.casino.pspgateway.providers.digitarium

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.text
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.{Base64, HexFormat}
import scala.util.Try

/**
 * Digitarium (G2Pay): a hosted card checkout, two-decimal currencies only (`digitarium/adapter.go`),
 * its webhook signed with HMAC-SHA256 of the body (`signature.go`) and answered as BounceMoney's is
 * (`handler/digitarium.go:39-158`).
 *
 * `providerData`: `g2payId`, G2Pay's payment id from the answer, cross-checked against the webhook's.
 */
object Digitarium extends Deposits:
  val provider: Provider = Provider.Digitarium

  /**
   * The installation setting holding the host this credential's `environment` selects: production only
   * for `production`, the sandbox otherwise (`adapter.go:27-35`; the original's `DIGITARIUM_BASE_URL`
   * override is the installation's setting itself here).
   */
  def host(credential: Credential): String =
    if credential.get("environment").contains("production") then "DIGITARIUM_PRODUCTION_URL" else "DIGITARIUM_SANDBOX_URL"

  /**
   * `POST {base}/api/v1/payments`, Bearer `apiKey` (`adapter.go:111-160`): `DEPOSIT` by `BASIC_CARD`, the
   * amount a JSON number, `referenceId` the payment id. G2Pay takes no customer fields here. A missing
   * key or a currency whose minor unit is not 1/100 is refused before the call.
   */
  def request(charge: Charge, credential: Credential, base: String): Either[Initiation, Outbound] =
    credential.get("apiKey") match
      case None => Left(Initiation.Refused("missing_credentials", "digitarium: missing required credential (apiKey)"))
      case Some(_) if !Render.twoDecimalCurrency(charge.currency) =>
        Left(Initiation.Refused("unsupported_currency",
          s"digitarium: currency ${charge.currency.code} is not a 2-decimal currency; amount scaling unsupported"))
      case Some(apiKey) =>
        val body = Json.obj(
          "referenceId"   -> charge.paymentId,
          "paymentType"   -> "DEPOSIT",
          "paymentMethod" -> "BASIC_CARD",
          "amount"        -> Render.decimalNumber(charge.amount, charge.currency),
          "currency"      -> charge.currency.code,
          "returnUrl"     -> Option(charge.returnUrl).filter(_.nonEmpty),
          "webhookUrl"    -> Option(charge.webhookUrl).filter(_.nonEmpty)
        )
        Right(Outbound.json(s"$base/api/v1/payments", body, "Authorization" -> s"Bearer $apiKey"))

  /**
   * The answer (`adapter.go:178-191`): `401` a bad key, any `4xx`/`5xx` a failure, then `result.redirectUrl`
   * required and `result.id` kept as the reference.
   */
  def redirectFrom(answered: Answered): Initiation =
    if answered.status == 401 then Initiation.Unavailable("digitarium: invalid API key (HTTP 401)")
    else if answered.status >= 400 then Initiation.Unavailable(s"digitarium: unexpected HTTP ${answered.status}")
    else
      answered.json.filter(_.isObject) match
        case None => Initiation.Unavailable("digitarium: answer unreadable")
        case Some(node) =>
          val result = Option(node.get("result")).filter(_.isObject)
          result.flatMap(_.text("redirectUrl")) match
            case None =>
              val state = result.flatMap(_.text("state")).getOrElse("")
              val code  = result.flatMap(_.text("errorCode")).getOrElse("")
              Initiation.Unavailable(s"digitarium: no redirectUrl in answer (state=\"$state\" errorCode=\"$code\")")
            case Some(url) =>
              val id = result.flatMap(_.text("id"))
              Initiation.Redirected(url, id, id.map("g2payId" -> _).toMap)

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    request(charge, credential, base) match
      case Left(refused) => refused
      case Right(out) =>
        call(out) match
          case Left(CallFailure.TimedOut)       => Initiation.Unavailable("digitarium: call timed out")
          case Left(CallFailure.Unreachable(r)) => Initiation.Unavailable(s"digitarium: request failed: $r")
          case Right(answered)                  => redirectFrom(answered)

  // ── Webhooks ───────────────────────────────────────────────────────────────────────────────────

  /**
   * Whether `signature` is HMAC-SHA256 of the raw body under `signingKey` (`signature.go:21-52`): hex in
   * either case, or standard or URL-safe base64 with or without padding, each decoded to 32 bytes and
   * compared in constant time. An empty key or signature never verifies.
   */
  def verify(rawBody: String, signature: String, signingKey: String): Boolean =
    if signingKey.isEmpty || signature.isEmpty then false
    else
      val expected = Digests.hmacSha256(signingKey, rawBody.getBytes(UTF_8))
      val sig      = signature.trim
      val candidates = Vector(
        Try(HexFormat.of.parseHex(sig)).toOption,
        Try(Base64.getDecoder.decode(sig)).toOption,
        Try(Base64.getUrlDecoder.decode(sig)).toOption
      ).flatten.filter(_.length == 32)
      candidates.exists(Digests.same(expected, _))

  /**
   * The payment object, bare or wrapped in `{"result": …}` (`adapter.go:86-98`): the bare one when it
   * names a `referenceId` or there is no `result` object.
   */
  def payment(node: JsonNode): JsonNode =
    val wrapped = Option(node.get("result")).filter(_.isObject)
    if node.text("referenceId").nonEmpty || wrapped.isEmpty then node else wrapped.get

  /**
   * The state as the original maps it (`adapter.go:217-227`, `handler/digitarium.go:126-154`): `COMPLETED`
   * captured; `DECLINED` and `CANCELLED` failed with `errorCode` and `errorMessage` ("Payment declined"
   * without one); `CHECKOUT`, `PENDING`, `AUTHORIZED` and anything else open.
   */
  def outcome(node: JsonNode): Verdict =
    val p = payment(node)
    p.text("state").getOrElse("") match
      case s @ "COMPLETED" => Verdict.Settle(Outcome.Captured(), s)
      case s @ ("DECLINED" | "CANCELLED") =>
        Verdict.Settle(Outcome.Failed(p.text("errorCode"), p.text("errorMessage").getOrElse("Payment declined")), s)
      case s => Verdict.Open(s)

  /** As BounceMoney's: who can fix what went wrong decides whether G2Pay redelivers (`handler/digitarium.go:31-34`). */
  enum Answer:
    case Malformed(text: String)
    case NotVerified
    case NoSigningKey
    case Unreadable(text: String)
    case Acknowledged

  def answer(a: Answer): Reply = a match
    case Answer.Malformed(text)  => Reply.text(400, s"$text\n")
    case Answer.NotVerified      => Reply.text(400, "invalid signature\n")
    case Answer.NoSigningKey     => Reply.text(500, "signing key not configured\n")
    case Answer.Unreadable(text) => Reply.text(502, s"$text\n")
    case Answer.Acknowledged     => Reply.ok

  /** The original's two `502` texts (`handler/digitarium.go:82`, `:111`). */
  val sessionUnreadable: Reply    = answer(Answer.Unreadable("session lookup failed"))
  val credentialUnreadable: Reply = answer(Answer.Unreadable("credential lookup failed"))

  /** The payment's `referenceId` names the session (`handler/digitarium.go:55-71`). */
  def locate(raw: Raw): Either[Reply, Located] =
    Json.parseObject(raw.body) match
      case None => Left(answer(Answer.Malformed("invalid request body")))
      case Some(node) =>
        payment(node).text("referenceId") match
          case None    => Left(answer(Answer.Malformed("missing referenceId")))
          case Some(r) => Right(Located.Deposit(r))

  def unknown(raw: Raw): Reply = answer(Answer.Acknowledged)

  /**
   * The cross-check (FR-013, *new*): the reference is the session's, G2Pay's id the one its answer gave,
   * and the amount and currency, where the payment carries them, the session's.
   */
  def crossCheck(node: JsonNode, charge: Charge): Option[String] =
    val p = payment(node)
    def mismatch(field: String, ok: String => Boolean) = p.text(field).exists(v => !ok(v))
    if !p.text("referenceId").exists(Digests.same(_, charge.paymentId)) then Some("reference does not match the session")
    else if charge.providerData.get("g2payId").exists(id => mismatch("id", Digests.same(_, id))) then
      Some("payment id does not match the session")
    else if mismatch("amount", a =>
        Try(java.math.BigDecimal(a)).toOption.exists(_.compareTo(Render.decimalNumber(charge.amount, charge.currency)) == 0))
    then Some("amount does not match the session")
    else if mismatch("currency", _.equalsIgnoreCase(charge.currency.code)) then Some("currency does not match the session")
    else None

  /** Verifies the `Signature` header under `signingKey`, cross-checks, then maps the state. */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    credential.get("signingKey") match
      case None => Handled(Verdict.Reject("no signing key"), answer(Answer.NoSigningKey))
      case Some(key) =>
        if !verify(raw.body, raw.header("Signature").getOrElse(""), key) then
          Handled(Verdict.Reject("invalid signature"), answer(Answer.NotVerified))
        else
          Json.parseObject(raw.body) match
            case None => Handled(Verdict.Reject("unreadable body"), answer(Answer.Malformed("invalid request body")))
            case Some(node) =>
              crossCheck(node, charge) match
                case Some(reason) => Handled(Verdict.Reject(reason), answer(Answer.NotVerified))
                case None         => Handled(outcome(node), answer(Answer.Acknowledged))
