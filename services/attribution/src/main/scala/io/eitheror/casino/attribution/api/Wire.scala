package io.eitheror.casino.attribution.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Try

/**
 * The routes' request and answer shapes (contracts/routes.md) and how a refusal is written: a JSON body
 * with a `code`, a `message` and, for `INVALID_REQUEST`, the `field`. The click route keeps the
 * original's own shapes, verbatim.
 */
object Wire:
  // ── refusals ──────────────────────────────────────────────────────────────────────────────────

  final case class Refusal(code: String, message: String, field: Option[String] = None)

  object Codes:
    val InvalidRequest = "INVALID_REQUEST"
    val NotFound       = "not_found"
    val AlreadyExists  = "ALREADY_EXISTS"
    val Conflict       = "CONFLICT"
    val NotBuilt       = "not_built"

  def refuse(status: Int, code: String, message: String, field: Option[String] = None): Respond[Bytes] =
    answer(status, Refusal(code, message, field))

  def invalid(field: String, message: String): Respond[Bytes] = refuse(400, Codes.InvalidRequest, message, Some(field))

  /** A component's refusal as the route's: `Conflict` 409, `NotFound` 404, anything else 400. */
  def refused(e: com.thinkmorestupidless.ankka.core.CommandError): Respond[Bytes] =
    import com.thinkmorestupidless.ankka.core.ErrorCode
    e.code match
      case ErrorCode.Conflict => refuse(409, Codes.Conflict, e.getMessage)
      case ErrorCode.NotFound => refuse(404, Codes.NotFound, e.getMessage)
      case _                  => refuse(400, Codes.InvalidRequest, e.getMessage)

  /** A route declared and not yet built: 501 naming it. */
  def notBuilt(route: String): Respond[Bytes] = refuse(501, Codes.NotBuilt, route)

  // ── answers ───────────────────────────────────────────────────────────────────────────────────

  def answer[A](status: Int, a: A)(using c: JsonValueCodec[A]): Respond[Bytes] =
    Respond(Bytes("application/json", writeToString(a).getBytes(UTF_8)), status)

  def ok[A: JsonValueCodec](a: A): Respond[Bytes] = answer(200, a)

  def raw(status: Int, json: String): Respond[Bytes] = Respond(Bytes("application/json", json.getBytes(UTF_8)), status)

  def noContent: Respond[Bytes] = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def read[A](body: String)(using c: JsonValueCodec[A]): Either[String, A] =
    Try(readFromString[A](body)).toEither.left.map(e => Option(e.getMessage).getOrElse("the body is not the route's shape"))

  // ── the click (the original's shapes) ─────────────────────────────────────────────────────────

  final case class ClickRequest(
      btag: Option[String] = None,
      clickId: Option[String] = None,
      deviceType: Option[String] = None,
      country: Option[String] = None,
      ip: Option[String] = None,
      userAgent: Option[String] = None,
      utmSource: Option[String] = None,
      utmMedium: Option[String] = None,
      utmCampaign: Option[String] = None,
      utmContent: Option[String] = None,
      utmTerm: Option[String] = None,
      landingPage: Option[String] = None,
      referrer: Option[String] = None,
      brandId: Option[String] = None
  )
  final case class ClickAnswer(ok: Boolean, clickId: String, reason: Option[String] = None)
  final case class ClickError(error: String)

  // ── the erasure ───────────────────────────────────────────────────────────────────────────────

  final case class ErasureAnswer(player: String, erased: Boolean, personalFields: Vector[String] = Vector.empty)

  // ── the operator's ────────────────────────────────────────────────────────────────────────────

  final case class Windows(attributionWindowDays: Int, bonusCodeWindowDays: Int)

  final case class NetworkBody(
      id: Option[String] = None,
      keyDocument: Option[String] = None,
      revocationList: Option[String] = None,
      legacyTagDocument: Option[String] = None,
      codeDocument: Option[String] = None,
      roundStream: Option[Boolean] = None,
      machine: Option[String] = None
  )

  final case class DocumentStatus(kind: String, fetchedAt: Option[Instant], expiresAt: Option[Instant], fresh: Boolean,
      lastError: Option[String])

  final case class NetworkAnswer(
      id: String,
      keyDocument: String,
      revocationList: String,
      legacyTagDocument: Option[String],
      codeDocument: Option[String],
      roundStream: Boolean,
      machine: Option[String],
      pseudonymKeyMade: Boolean,
      removed: Option[Instant],
      documents: Vector[DocumentStatus] = Vector.empty
  )
  final case class Networks(networks: Vector[NetworkAnswer])

  final case class PseudonymKeyAnswer(network: String, made: Boolean)

  final case class Transition(firstDay: Option[String], endsAt: Option[String], endedOn: Option[String], endedBy: Option[String],
      legacyTagsLast30Days: Long)
  final case class NetworkStatus(id: String, reachable: Boolean, lastFetch: Option[Instant], boundPlayers: Long, undelivered: Long)
  final case class Status(networks: Vector[NetworkStatus], transition: Transition, openDays: Long,
      legacyPerDay: Map[String, Long] = Map.empty)

  final case class TouchAnswer(kind: String, value: String, at: Instant, outcome: String)
  final case class BindingAnswer(network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String],
      clickId: Option[String], boundAt: Instant, touch: String)
  final case class RefusedAnswer(tag: String, reason: String, at: Instant)
  final case class RecordAnswer(player: String, registeredAt: Option[Instant], binding: Option[BindingAnswer],
      refused: Option[RefusedAnswer], touches: Vector[TouchAnswer], firstDepositAt: Option[Instant], closed: Option[String],
      erased: Option[Instant])

  final case class SigningKeyAnswer(kid: String, publicKey: String, madeAt: Instant, retiredAt: Option[Instant])
  final case class SigningKeys(keys: Vector[SigningKeyAnswer])

  // ── the webhooks ──────────────────────────────────────────────────────────────────────────────

  final case class SubscriptionBody(network: Option[String] = None, address: Option[String] = None, secret: Option[String] = None,
      active: Option[Boolean] = None)
  final case class SubscriptionAnswer(id: String, network: String, address: String, active: Boolean, createdAt: Option[Instant],
      updatedAt: Option[Instant])
  final case class Subscriptions(subscriptions: Vector[SubscriptionAnswer])

  final case class UndeliveredAnswer(delivery: String, subscription: String, network: String, factId: String, kind: String,
      attempts: Int, lastStatus: Option[Int], lastError: Option[String])
  final case class Undelivered(undelivered: Vector[UndeliveredAnswer])

  /** The wire's codec settings: an absent value is left out, an empty list and a default are written. */
  inline def wire[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false).withTransientNone(true))

  // ── codecs ────────────────────────────────────────────────────────────────────────────────────

  given JsonValueCodec[Refusal]            = wire[Refusal]
  given JsonValueCodec[ClickRequest]       = wire[ClickRequest]
  given JsonValueCodec[ClickAnswer]        = wire[ClickAnswer]
  given JsonValueCodec[ClickError]         = wire[ClickError]
  given JsonValueCodec[ErasureAnswer]      = wire[ErasureAnswer]
  given JsonValueCodec[Windows]            = wire[Windows]
  given JsonValueCodec[NetworkBody]        = wire[NetworkBody]
  given JsonValueCodec[NetworkAnswer]      = wire[NetworkAnswer]
  given JsonValueCodec[Networks]           = wire[Networks]
  given JsonValueCodec[PseudonymKeyAnswer] = wire[PseudonymKeyAnswer]
  given JsonValueCodec[Status]             = wire[Status]
  given JsonValueCodec[RecordAnswer]       = wire[RecordAnswer]
  given JsonValueCodec[SigningKeys]        = wire[SigningKeys]
  given JsonValueCodec[SigningKeyAnswer]   = wire[SigningKeyAnswer]
  given JsonValueCodec[SubscriptionBody]   = wire[SubscriptionBody]
  given JsonValueCodec[SubscriptionAnswer] = wire[SubscriptionAnswer]
  given JsonValueCodec[Subscriptions]      = wire[Subscriptions]
  given JsonValueCodec[Undelivered]        = wire[Undelivered]
  given JsonValueCodec[UndeliveredAnswer]  = wire[UndeliveredAnswer]
