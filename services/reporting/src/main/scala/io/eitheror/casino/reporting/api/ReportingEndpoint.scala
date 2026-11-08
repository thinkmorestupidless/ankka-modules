package io.eitheror.casino.reporting.api

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.reporting.{ReportingRuntime, Settings}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.settings.{ReportingSettings, ReportingSettingsState}

import java.time.{Instant, LocalDate}
import scala.jdk.CollectionConverters.*
import scala.util.Try

import Wire.*

/**
 * What every route is built with: the service's clients, the authenticator and the clock. The settings are read at
 * each request from `ReportingRuntime`, so a service restarted on another generation answers from it.
 */
final class Ctx(val clients: EndpointClients, val authenticate: () => Acl, val now: () => Instant):
  def settings: Settings = ReportingRuntime.settings.get()
  def slug: String       = settings.brand
  def components         = clients.componentClient
  def views              = clients.viewClient

  /** Today, a reporting day in the brand's zone (FR-006). */
  def today: LocalDate = LocalDate.parse(Days.dayOf(now(), settings.reportingZone))

  /** The brand's reporting settings (FR-024). */
  def reporting: ReportingSettingsState =
    components.forKeyValueEntity(EntityId(ReportingSettings.Id)).call(ReportingSettings.get).invoke()

/**
 * `/v1/casino/brands/<slug>/reporting` (research R13): the one endpoint of the service, each route admitted as
 * contracts/routes.md's table says through `withAcl`. A path no route serves is judged by the endpoint's own ACL, every
 * operator of the brand, so it answers alike whether the route exists or not. Each user story adds its routes here.
 */
final class ReportingEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/reporting"):
  private val access = Access(ctx.slug, ctx.authenticate)

  val acl: Acl = access.operator

  withAcl(access.reports) {
    get("/reports/ggr") { () =>
      Reports.ggr(ctx, Reports.Asked(query.raw("from"), query.raw("to"), query.raw("currency"), query.raw("format"), principal.subject))
    }
  }

  // The dashboard admits every operator of the brand and is never hidden by the Reports tab's flag (FR-023, FR-024).
  withAcl(access.operator) {
    get("/dashboard/kpis") { () => Dashboard.kpis(ctx, query.raw("currency")) }
    get("/dashboard/trend") { () => Dashboard.trend(ctx, query.raw("range"), query.raw("currency")) }
    get("/dashboard/kpi-history") { () => Dashboard.history(ctx, query.raw("currency")) }
    get("/dashboard/geo") { () => Dashboard.geo(ctx) }
    get("/dashboard/rankings") { () => Dashboard.rankings(ctx, query.raw("range"), query.raw("currency")) }
    get("/dashboard/activity") { () => Dashboard.activity(ctx) }
    get("/dashboard/bonus-summary") { () => Dashboard.bonusSummary(ctx, query.raw("currency")) }
    get("/dashboard/payment-health") { () => Dashboard.paymentHealth(ctx, query.raw("currency")) }
  }

  withAcl(access.status) {
    get("/status") { () => ok(StatusRoute.of(ctx)) }
  }

  withAcl(access.settings) {
    get("/settings") { () => ok(SettingsRoute.shown(ctx.reporting)) }
    putBody("/settings") { (body: String) => SettingsRoute.put(ctx, body) }
  }

/** `GET /status` (FR-004): the generation served, and per view the first complete day and whether it is rebuilding. */
final case class ViewStatus(name: String, completeFrom: Option[String], rebuilding: Boolean)
final case class StatusAnswer(generation: Int, views: Vector[ViewStatus])
object StatusAnswer:
  given JsonValueCodec[StatusAnswer] = Wire.answer[StatusAnswer]

object StatusRoute:
  /**
   * ankka 0.16 tells a service nothing of its own sources' lag (it is on the management port and in the metrics), so
   * `rebuilding` is false; `completeFrom` is null until a topic of the view has folded a fact in this generation.
   */
  def of(ctx: Ctx): StatusAnswer =
    val s = ctx.settings
    StatusAnswer(s.generation, Completeness.viewTopics(s).map((view, topics) =>
      ViewStatus(view, Completeness.from(ctx.components, s, topics).map(_.toString), rebuilding = false)))

/** `GET`/`PUT /settings` (FR-024): `ReportingSettings` in camelCase; a value out of its range `400 invalid_setting`. */
final case class SettingsAnswer(reportsTab: Boolean, excludeTestAccounts: Boolean, defaultCurrency: Option[String], syncRows: Int,
    syncDays: Int, lifetimeHours: Map[String, Int])

object SettingsAnswer:
  given JsonValueCodec[SettingsAnswer] = Wire.answer[SettingsAnswer]

object SettingsRoute:
  private val mapper = ObjectMapper()

  /** Every field, a document kind with no lifetime of its own at the default. */
  def shown(s: ReportingSettingsState): SettingsAnswer =
    SettingsAnswer(s.reportsTab, s.excludeTestAccounts, s.defaultCurrency, s.syncRows, s.syncDays,
      ReportingSettings.Kinds.toVector.sorted.map(k => k -> s.lifetimeOf(k)).toMap)

  /**
   * The body's fields over what is stored: a field left out keeps its value, `defaultCurrency: null` clears it. A field
   * of the wrong type is refused naming it; a value out of its range is the entity's refusal, naming the field and the
   * range.
   */
  def parse(body: JsonNode, current: ReportingSettingsState): Either[(String, String), ReportingSettingsState] =
    def field(name: String): Option[JsonNode] = Option(body.get(name))
    def bool(name: String, was: Boolean): Either[(String, String), Boolean] = field(name) match
      case None                    => Right(was)
      case Some(v) if v.isBoolean  => Right(v.asBoolean)
      case Some(_)                 => Left(name -> s"$name is true or false")
    def int(name: String, was: Int): Either[(String, String), Int] = field(name) match
      case None                                     => Right(was)
      case Some(v) if v.isIntegralNumber && v.canConvertToInt => Right(v.asInt)
      case Some(_)                                  => Left(name -> s"$name is a whole number")
    val currency: Either[(String, String), Option[String]] = field("defaultCurrency") match
      case None                    => Right(current.defaultCurrency)
      case Some(v) if v.isNull     => Right(None)
      case Some(v) if v.isTextual  => Right(Some(v.asText.trim.toUpperCase).filter(_.nonEmpty))
      case Some(_)                 => Left("defaultCurrency" -> "defaultCurrency is a three-letter code")
    val lifetimes: Either[(String, String), Map[String, Int]] = field("lifetimeHours") match
      case None                   => Right(current.lifetimeHours)
      case Some(v) if v.isObject  =>
        v.properties.asScala.toVector.foldLeft[Either[(String, String), Map[String, Int]]](Right(current.lifetimeHours)) {
          case (Left(e), _) => Left(e)
          case (Right(m), e) if e.getValue.isIntegralNumber && e.getValue.canConvertToInt => Right(m.updated(e.getKey, e.getValue.asInt))
          case (Right(_), e) => Left(s"lifetimeHours.${e.getKey}" -> s"lifetimeHours.${e.getKey} is a whole number of hours")
        }
      case Some(_) => Left("lifetimeHours" -> "lifetimeHours is an object of hours by document kind")
    for
      tab      <- bool("reportsTab", current.reportsTab)
      exclude  <- bool("excludeTestAccounts", current.excludeTestAccounts)
      c        <- currency
      rows     <- int("syncRows", current.syncRows)
      days     <- int("syncDays", current.syncDays)
      lives    <- lifetimes
    yield ReportingSettingsState(tab, exclude, c, rows, days, lives)

  def put(ctx: Ctx, text: String): Answer =
    Try(mapper.readTree(text)).toOption.filter(n => n != null && n.isObject) match
      case None => invalidBody
      case Some(body) =>
        parse(body, ctx.reporting) match
          case Left((field, message)) => invalidSetting(field, message)
          case Right(next) =>
            try ok(shown(ctx.components.forKeyValueEntity(EntityId(ReportingSettings.Id)).call(ReportingSettings.put).invoke(next)))
            catch
              case e: CommandError if e.code == ErrorCode.BadRequest && e.message.startsWith(ReportingSettings.Code) =>
                val message = e.message.stripPrefix(s"${ReportingSettings.Code}:").trim
                invalidSetting(message.takeWhile(_ != ' '), message)
