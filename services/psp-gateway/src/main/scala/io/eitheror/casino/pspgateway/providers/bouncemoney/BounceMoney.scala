package io.eitheror.casino.pspgateway.providers.bouncemoney

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.text
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, Signature}
import java.time.Instant
import java.util.Base64
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * BounceMoney: a hosted fiat-to-crypto page (`bouncemoney/adapter.go`), the session call signed with
 * Ed25519 and the webhook with a timestamped HMAC (`signature.go`), answered so that BounceMoney
 * redelivers only what a redelivery could fix (`handler/webhook.go:1400-1563`). No sandbox.
 *
 * `providerData`: none; the session's own id is the reference.
 */
object BounceMoney extends Deposits:
  val provider: Provider = Provider.BounceMoney

  /** 15.00 in any fiat currency, by minor units (`adapter.go:102-105`); refused as a player error. */
  val minimum: Long          = 1500
  val minimumMessage: String = "minimum deposit amount is 15.00"

  /** The webhook timestamp window either side of now, in seconds (`signature.go:54-57`). */
  val window: Long = 300

  // ── Request signing ────────────────────────────────────────────────────────────────────────────

  private val armour = Pattern.compile("(?s)-----BEGIN [^-]*-----(.*?)-----END [^-]*-----")

  /**
   * The PKCS8 DER inside a key as an operator's form or a JSON blob delivers it (`signature.go:98-137`):
   * proper PEM, armour with its newlines stripped or escaped, or the bare base64 body. Never the key in
   * an error, only its length.
   */
  def der(keyText: String): Either[String, Array[Byte]] =
    val normalised = keyText.replace("\\n", "\n").replace("\\r", "")
    val trimmed    = normalised.trim
    val m          = armour.matcher(trimmed)
    val body =
      if m.find() then Right(m.group(1))
      else if trimmed.contains("-----") then Left(s"PEM armour present but malformed (${keyText.length} chars)")
      else Right(trimmed)
    body.flatMap { b =>
      val bare = b.filterNot(c => c == ' ' || c == '\t' || c == '\n' || c == '\r')
      if bare.isEmpty then Left(s"no key material found (${keyText.length} chars)")
      else Try(Base64.getDecoder.decode(bare)).toOption.toRight(s"key body is not valid base64 (${keyText.length} chars)")
    }

  /** The Ed25519 private key, parsed strictly from the DER on every call (`signature.go:82-96`). */
  def privateKey(keyText: String): Either[String, PrivateKey] =
    der(keyText).flatMap { bytes =>
      Try(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes))).toOption
        .toRight("key is not a PKCS8 Ed25519 private key")
    }

  /**
   * The three headers over `<ts>.` + body (`signature.go:24-33`): `X-BounceMoney-Key-Id`,
   * `X-BounceMoney-Timestamp`, and `X-BounceMoney-Signature: v1=` + base64url of the Ed25519 signature.
   */
  def sign(keyId: String, keyText: String, ts: Long, body: String): Either[String, Vector[(String, String)]] =
    privateKey(keyText).map { key =>
      val signer = Signature.getInstance("Ed25519")
      signer.initSign(key)
      signer.update(s"$ts.".getBytes(UTF_8))
      signer.update(body.getBytes(UTF_8))
      Vector(
        "X-BounceMoney-Key-Id"    -> keyId,
        "X-BounceMoney-Timestamp" -> ts.toString,
        "X-BounceMoney-Signature" -> s"v1=${Digests.base64Url(signer.sign())}"
      )
    }

  // ── Initiation ─────────────────────────────────────────────────────────────────────────────────

  /** The session body in the original's field order (`adapter.go:38-47`, `:120-135`). */
  def sessionBody(charge: Charge, credential: Credential, customer: Customer): String =
    Json.render(
      Json.obj(
        "fiatAmount"        -> Render.decimalNumber(charge.amount, charge.currency),
        "fiat"              -> charge.currency.code,
        "referenceId"       -> charge.paymentId,
        "description"       -> s"Deposit ${charge.paymentId}",
        "redirectUrl"       -> charge.returnUrl,
        "email"             -> customer.email,
        "customerReference" -> charge.customerId.map(_.trim).filter(_.nonEmpty),
        "paymentMethodId"   -> credential.get("paymentMethodId")
      )
    )

  /**
   * `POST {base}/checkout/sessions` (`adapter.go:88-157`): below the minimum a player error; `keyId` and
   * `privateKey` required; `referenceId` and `Idempotency-Key` the payment id; signed at `now`.
   */
  def sessionRequest(charge: Charge, credential: Credential, customer: Customer, base: String, now: Instant)
      : Either[Initiation, Outbound] =
    if charge.amount < minimum then Left(Initiation.Refused("player_error", minimumMessage))
    else
      (credential.get("keyId"), credential.get("privateKey")) match
        case (Some(keyId), Some(key)) =>
          val body = sessionBody(charge, credential, customer)
          sign(keyId, key, now.getEpochSecond, body) match
            case Left(reason) => Left(Initiation.Unavailable(s"bouncemoney: sign request: $reason"))
            case Right(headers) =>
              Right(Outbound("POST", s"$base/checkout/sessions", headers :+ ("Idempotency-Key" -> charge.paymentId),
                Some(body), Some("application/json")))
        case _ =>
          Left(Initiation.Refused("missing_credentials", "bouncemoney: missing required credentials (keyId, privateKey)"))

  /**
   * The provider's error as the original reads it (`adapter.go:55-71`): `message` as a string, or the
   * first of an object's field errors, or `error`, or "payment unavailable".
   */
  def userMessage(node: JsonNode): String =
    val message = Option(node.get("message"))
    message.filter(_.isTextual).map(_.asText).filter(_.nonEmpty)
      .orElse(message.filter(m => m.isObject && m.properties.asScala.forall(_.getValue.isTextual))
        .flatMap(_.properties.asScala.headOption.map(_.getValue.asText)))
      .orElse(node.text("error"))
      .getOrElse("payment unavailable")

  /**
   * `201` with `data.url` (`adapter.go:170-203`). Anything else is the provider failing: the original
   * shows the player none of it (`adapter_test.go` `TestInitiate_InternalFailuresAreNotPlayerErrors`).
   */
  def redirectFrom(answered: Answered): Initiation =
    if answered.status != 201 then
      answered.json.filter(n => n.isObject && n.text("error").nonEmpty) match
        case Some(err) => Initiation.Unavailable(s"bouncemoney: ${userMessage(err)}")
        case None      => Initiation.Unavailable(s"bouncemoney: unexpected HTTP ${answered.status}")
    else
      answered.json.filter(_.isObject) match
        case None => Initiation.Unavailable("bouncemoney: session answer unreadable")
        case Some(node) =>
          val data = Option(node.get("data")).filter(_.isObject)
          data.flatMap(_.text("url")) match
            case None      => Initiation.Unavailable("bouncemoney: no url in session answer")
            case Some(url) => Initiation.Redirected(url, data.flatMap(_.text("id")))

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    initiateAt(charge, credential, customer, base, call, Instant.now())

  /** As `initiate`, signed at `now`: the trait gives no clock, so the one impure read is here, named. */
  def initiateAt(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call, now: Instant)
      : Initiation =
    sessionRequest(charge, credential, customer, base, now) match
      case Left(refused) => refused
      case Right(out) =>
        call(out) match
          case Left(CallFailure.TimedOut)       => Initiation.Unavailable("bouncemoney: session call timed out")
          case Left(CallFailure.Unreachable(r)) => Initiation.Unavailable(s"bouncemoney: session call failed: $r")
          case Right(answered)                  => redirectFrom(answered)

  // ── Webhooks ───────────────────────────────────────────────────────────────────────────────────

  /**
   * `X-BounceMoney-Signature: t=<seconds>,v1=<base64url>` over `<t>.` + the raw body under the signing
   * secret, within `window` of `now`, compared in constant time (`signature.go:43-63`). An empty secret
   * never verifies (the JDK takes no empty HMAC key; the webhook answers `500` before it gets here).
   */
  def verify(rawBody: String, header: String, signingSecret: String, now: Instant): Boolean =
    signingSecret.nonEmpty && verified(rawBody, header, signingSecret, now)

  private def verified(rawBody: String, header: String, signingSecret: String, now: Instant): Boolean =
    val parts = header.split(",", -1).toVector.flatMap { part =>
      part.trim.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
    (parts.get("t"), parts.get("v1")) match
      case (Some(t), Some(v1)) =>
        t.toLongOption.exists { ts =>
          val diff = now.getEpochSecond - ts
          diff >= -window && diff <= window && {
            val expected = Digests.base64Url(Digests.hmacSha256(signingSecret, s"$t.".getBytes(UTF_8) ++ rawBody.getBytes(UTF_8)))
            Digests.same(expected, v1)
          }
        }
      case _ => false

  /**
   * The event's type as the original maps it (`adapter.go:208-217`, `handler/webhook.go:1530-1549`):
   * completed captured; failed and expired failed with the type as the code; anything else acknowledged.
   */
  def outcome(node: JsonNode): Verdict =
    node.text("type").getOrElse("") match
      case t @ "checkout.session.completed" => Verdict.Settle(Outcome.Captured(), t)
      case t @ "checkout.session.failed"    => Verdict.Settle(Outcome.Failed(Some(t), "Payment declined"), t)
      case t @ "checkout.session.expired"   => Verdict.Settle(Outcome.Failed(Some(t), "Payment session expired"), t)
      case t                                => Verdict.Ignore(s"unhandled event type '$t'")

  /** Who can fix what went wrong, which decides whether BounceMoney redelivers (`handler/webhook.go:1404-1413`). */
  enum Answer:
    /** The request itself is bad (`400`, plain text as `http.Error` writes it). */
    case Malformed(text: String)
    /** It did not verify, or did not match its session (`400 {"error":"invalid_signature"}`). */
    case NotVerified
    /** The credential holds no signing secret (`500`): redelivered once one is set. */
    case NoSigningSecret
    /** The session store or `merchant` could not be read (`502`): `text` names which. */
    case Unreadable(text: String)
    /** Handled, unknown, or a type nothing acts on (`200`). */
    case Acknowledged

  def answer(a: Answer): Reply = a match
    case Answer.Malformed(text)  => Reply.text(400, s"$text\n")
    case Answer.NotVerified      => Reply.json(400, Json.obj("error" -> "invalid_signature"))
    case Answer.NoSigningSecret  => Reply.text(500, "signing secret not configured\n")
    case Answer.Unreadable(text) => Reply.text(502, s"$text\n")
    case Answer.Acknowledged     => Reply.ok

  /** The original's two `502` texts (`handler/webhook.go:1470`, `:1501`). */
  val sessionUnreadable: Reply    = answer(Answer.Unreadable("session lookup failed"))
  val credentialUnreadable: Reply = answer(Answer.Unreadable("credential lookup failed"))

  private val signatureHeader = "X-BounceMoney-Signature"

  /**
   * `data.referenceId` names the session (`handler/webhook.go:1437-1464`), after the body is JSON and
   * the signature header has the scheme's shape, so an unsigned request costs no lookup.
   */
  def locate(raw: Raw): Either[Reply, Located] =
    Json.parseObject(raw.body) match
      case None => Left(answer(Answer.Malformed("invalid request body")))
      case Some(node) =>
        val reference = Option(node.get("data")).filter(_.isObject).flatMap(_.text("referenceId"))
        val header    = raw.header(signatureHeader).getOrElse("")
        reference match
          case None => Left(answer(Answer.Malformed("missing data.referenceId")))
          case Some(_) if !header.contains("t=") || !header.contains("v1=") =>
            Left(answer(Answer.Malformed("missing or malformed signature header")))
          case Some(r) => Right(Located.Deposit(r))

  def unknown(raw: Raw): Reply = answer(Answer.Acknowledged)

  /**
   * The cross-check (FR-013, *new*): the reference is the session's, and the fiat amount and currency,
   * where the event carries them, the session's.
   */
  def crossCheck(node: JsonNode, charge: Charge): Option[String] =
    val data = Option(node.get("data")).filter(_.isObject).getOrElse(Json.obj())
    def mismatch(field: String, ok: String => Boolean) = data.text(field).exists(v => !ok(v))
    if !data.text("referenceId").exists(Digests.same(_, charge.paymentId)) then Some("reference does not match the session")
    else if mismatch("fiatAmount", a =>
        Try(java.math.BigDecimal(a)).toOption.exists(_.compareTo(Render.decimalNumber(charge.amount, charge.currency)) == 0))
    then Some("amount does not match the session")
    else if mismatch("fiat", _.equalsIgnoreCase(charge.currency.code)) then Some("currency does not match the session")
    else None

  /** Verifies under `signingSecret`, cross-checks against the session, then maps the type. */
  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    credential.get("signingSecret") match
      case None => Handled(Verdict.Reject("no signing secret"), answer(Answer.NoSigningSecret))
      case Some(secret) =>
        if !verify(raw.body, raw.header(signatureHeader).getOrElse(""), secret, now) then
          Handled(Verdict.Reject("invalid signature"), answer(Answer.NotVerified))
        else
          Json.parseObject(raw.body) match
            case None => Handled(Verdict.Reject("unreadable body"), answer(Answer.Malformed("invalid request body")))
            case Some(node) =>
              crossCheck(node, charge) match
                case Some(reason) => Handled(Verdict.Reject(reason), answer(Answer.NotVerified))
                case None         => Handled(outcome(node), answer(Answer.Acknowledged))
