package io.eitheror.casino.rg.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, Respond}
import io.eitheror.casino.rg.{Json, RgRuntime, Settings}
import io.eitheror.casino.rg.profile.*
import io.eitheror.casino.rg.settings.{BrandSettings, BrandSettingsEntity}
import io.eitheror.casino.rg.usage.{UsageAt, UsageEntity, UsageSums}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Try

/**
 * How the routes answer (contracts/routes.md): a body, or a refusal as `{"error", "message", ...named
 * fields}`. The 401s and 403s of a caller the ACL refuses are the ACL's, written before a handler runs.
 */
object Answers:
  type Reply = Respond[Bytes]

  def json(status: Int, body: JsonNode): Reply = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  def ok(body: JsonNode): Reply             = json(200, body)
  def noContent: Reply                       = Respond(Bytes("application/json", Array.emptyByteArray), 204, Vector.empty)

  def refusal(status: Int, error: String, message: String, fields: (String, Any)*): Reply =
    json(status, Json.obj((Seq[(String, Any)]("error" -> error, "message" -> message) ++ fields)*))

  val badBody: Reply = refusal(400, "invalid_body", "The body is not a JSON object")

  /** The status and message of each refusal code; the profile's refusals carry their named fields. */
  private val Statuses: Map[String, (Int, String)] = Map(
    "unknown_limit_type"            -> (400, "Unknown limit type"),
    "value_required"                -> (400, "A value is required"),
    "invalid_value"                 -> (400, "The value must be a positive whole number"),
    "below_minimum_session_minutes" -> (400, "The session limit is below the brand's minimum"),
    "invalid_currency"              -> (400, "The currency is not one of the player's"),
    "no_limit"                      -> (404, "No such limit is set"),
    "break_active"                  -> (409, "A break is in place; only an operator may change it"),
    "break_too_short"               -> (400, "A break must last at least one hour"),
    "break_too_long"                -> (400, "A break may not exceed the brand's maximum"),
    "no_break"                      -> (404, "No break is in place"),
    "reason_required"               -> (400, "A reason is required"),
    "already_excluded"              -> (409, "The player is already excluded"),
    "invalid_period"                -> (400, "The period is not permanent, PnD or nd"),
    "period_too_short"              -> (400, "A self-exclusion lasts at least 183 days"),
    "not_excluded"                  -> (409, "The player is not excluded"),
    "exclusion_permanent"           -> (409, "A permanent exclusion cannot be returned from"),
    "exclusion_not_ended"           -> (409, "The exclusion has not ended"),
    "return_requested"              -> (409, "A return is already requested"),
    "invalid_interval"              -> (400, "The interval must be 15, 30, 45 or 60 minutes"),
    "session_not_found"             -> (404, "No such session of the player"),
    "invalid_case_type"             -> (400, "Unknown case type"),
    "case_not_found"                -> (404, "No such case"),
    "case_closed"                   -> (409, "The case is already closed")
  )

  def refused(r: Refusal): Reply =
    val (status, message) = Statuses.getOrElse(r.code, (400, r.code))
    val named: Seq[(String, Any)] = r.numbers.toSeq ++ r.times.toSeq.map((k, v) => k -> v.toString) ++
      Option.when(r.code == "invalid_interval")("allowed" -> Json.arr(RealityChecks.Allowed*)).toSeq
    refusal(status, r.code, message, named*)

  /** A body's instant, as ISO-8601 text or epoch milliseconds. */
  def instant(node: JsonNode, field: String): Option[Instant] =
    import Json.*
    node.long(field).filter(_ => node.get(field).isNumber).map(Instant.ofEpochMilli)
      .orElse(node.text(field).flatMap(t => Try(Instant.parse(t)).toOption))

/** What every endpoint is built with: the service's clients, the authenticator, the settings and the clock. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings = RgRuntime.settings
  def slug: String       = settings.brand
  def now(): Instant     = RgRuntime.now()

  def profile(playerId: String) = clients.componentClient.forEventSourcedEntity(EntityId(playerId))
  def state(playerId: String): ProfileState = profile(playerId).call(ProfileEntity.get).invoke()
  def brandSettings: BrandSettings =
    clients.componentClient.forKeyValueEntity(EntityId(slug)).call(BrandSettingsEntity.get).invoke()
  def usage(playerId: String, currency: String): UsageSums =
    clients.componentClient.forKeyValueEntity(EntityId(UsageEntity.id(playerId, currency))).call(UsageEntity.sums).invoke(UsageAt(now()))

/** The bodies the player's and the operator's reads share (contracts/routes.md *The status*). */
object Render:
  import Answers.*

  /** A minor-unit amount as the original showed `usedAmount`: major units to the currency's places. */
  def major(amount: Long, currency: String): BigDecimal =
    val places = Try(java.util.Currency.getInstance(currency).getDefaultFractionDigits).toOption.filter(_ >= 0).getOrElse(2)
    BigDecimal(amount, places)

  /** The currencies the player's money limits name, and the one a request names. */
  def currencies(s: ProfileState, asked: Option[String]): Vector[String] =
    (s.limits.values.flatMap(_.key.currency) ++ s.pending.values.flatMap(_.key.currency) ++ asked).toVector.distinct.sorted

  def status(ctx: Ctx, s: ProfileState, asked: Option[String]): ObjectNode =
    val now = ctx.now()
    val limits = s.limits.values.toVector.sortBy(_.key.id).map(l =>
      Json.obj("limitType" -> l.key.limitType, "value" -> l.value, "currency" -> l.key.currency))
    val pending = s.pending.values.toVector.sortBy(_.key.id).map { p =>
      val o = Json.obj("limitType" -> p.key.limitType, "appliesAt" -> p.appliesAt.toString, "currency" -> p.key.currency)
      p.value.fold(o.putNull("pendingValue"))(v => o.put("pendingValue", v.toString))
      o
    }
    val used = Try(currencies(s, asked).flatMap { c =>
      val u = ctx.usage(s.playerId, c)
      def entry(t: LimitType, amount: Long) =
        val o = Json.obj("limitType" -> t.name, "currency" -> c)
        o.put("usedAmount", major(amount, c).bigDecimal)
        o
      LimitType.deposits.map(t => entry(t, u.deposits.of(t.period.get))) ++ LimitType.losses.map(t => entry(t, u.losses.of(t.period.get)))
    }).getOrElse(Vector.empty)
    val brk = s.onBreak(now)
    val o = Json.obj(
      "limits"             -> Json.arr(limits*),
      "pendingChanges"     -> Json.arr(pending*),
      "usedAmounts"        -> Json.arr(used*),
      "isCoolingOff"       -> brk.isDefined,
      "activeRestrictions" -> Json.arr((brk.map(_ => "COOLING_OFF").toSeq ++ s.exclusion.map(_ => "SELF_EXCLUDED").toSeq)*)
    )
    brk.fold(o.putNull("coolingOffUntil"))(b => o.put("coolingOffUntil", b.until.toString))
    s.realityCheck.fold(o.putNull("realityCheckIntervalMinutes"))(i => o.put("realityCheckIntervalMinutes", i))
    o

  def history(s: ProfileState): JsonNode =
    Json.arr(s.history.reverse.map { h =>
      val o = Json.obj("eventType" -> h.eventType, "actor" -> h.actor, "occurredAt" -> h.occurredAt.toString, "description" -> h.description)
      Seq("limitType" -> h.limitType, "previousValue" -> h.previousValue, "newValue" -> h.newValue).foreach((k, v) =>
        v.fold(o.putNull(k))(x => o.put(k, x)))
      o
    }*)

  def rgCase(c: RgCase): ObjectNode =
    Json.obj("caseId" -> c.caseId, "caseType" -> c.caseType, "trigger" -> c.trigger, "status" -> c.status,
      "openedAt" -> c.openedAt.toString, "closesAt" -> c.closesAt.map(_.toString), "closedAt" -> c.closedAt.map(_.toString),
      "closeReason" -> c.closeReason, "closeNote" -> c.closeNote.map(_.reveal).filter(_.nonEmpty), "permanent" -> c.permanent,
      "notes" -> Json.arr(c.notes.map(n => Json.obj("at" -> n.at.toString, "operatorId" -> n.operatorId, "text" -> n.text.reveal))*))

  /** The operator's read (FR-025): the status, the exclusion, the return, the cases and the history. */
  def full(ctx: Ctx, s: ProfileState, asked: Option[String]): ObjectNode =
    val o = status(ctx, s, asked)
    o.set[JsonNode]("exclusion", s.exclusion.fold(Json.mapper.nullNode(): JsonNode)(e =>
      Json.obj("permanent" -> e.permanent, "until" -> e.until.map(_.toString), "since" -> e.since.toString, "trigger" -> e.trigger)))
    o.set[JsonNode]("returnRequest", s.returnRequest.fold(Json.mapper.nullNode(): JsonNode)(r =>
      Json.obj("requestedAt" -> r.at.toString, "reinstatesAt" -> r.reinstatesAt.toString, "actor" -> r.actor.kind)))
    o.set[JsonNode]("cases", Json.arr(s.cases.map(rgCase)*))
    o.set[JsonNode]("history", history(s))
    o
