package io.eitheror.casino.reporting.settings

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs

/**
 * A brand's reporting settings (data-model.md, key value, `brand`): whether the reports tab shows, whether the
 * figures leave the test accounts out, the currency a report opens in, the sync export's page and reach, and how
 * long each kind of document is kept before the sweep deletes it (research R9).
 */
final case class ReportingSettingsState(
    reportsTab: Boolean = true,
    excludeTestAccounts: Boolean = false,
    defaultCurrency: Option[String] = None,
    syncRows: Int = ReportingSettings.SyncRows.default,
    syncDays: Int = ReportingSettings.SyncDays.default,
    lifetimeHours: Map[String, Int] = Map.empty
):
  /** A kind with no setting of its own keeps the default. */
  def lifetimeOf(kind: String): Int = lifetimeHours.getOrElse(kind, ReportingSettings.LifetimeHours.default)

final class ReportingSettings(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ReportingSettingsState]:
  def emptyState: ReportingSettingsState = ReportingSettingsState()

  /** The whole settings, as `PUT /settings` sends them; refused whole when any field is out of its range. */
  def put(next: ReportingSettingsState): Effect[ReportingSettingsState] =
    ReportingSettings.validate(next) match
      case Some(problem) => effects.error(problem, ErrorCode.BadRequest)
      case None          => effects.updateState(next).thenReplyState

  def get: ReadOnlyEffect[ReportingSettingsState] = effects.reply(currentState)

object ReportingSettings extends KeyValueEntity.Companion[ReportingSettings, ReportingSettingsState](
      componentId = ComponentId("reporting-settings"),
      stateSerializer = ReportingCodecs.serializer[ReportingSettingsState]("settings")
    ):
  final case class Range(field: String, min: Int, max: Int, default: Int):
    def problem(value: Int): Option[String] =
      Option.when(value < min || value > max)(s"$Code: $field is $min to $max, not $value")

  /** The refusal's code, which the endpoint answers with `400`. */
  val Code = "invalid_setting"

  val SyncRows      = Range("syncRows", 1, 10_000, 500)
  val SyncDays      = Range("syncDays", 1, 365, 7)
  val LifetimeHours = Range("lifetimeHours", 1, 2_160, 24)

  /** The document kinds a lifetime is set for (data-model.md `Document`). */
  val Kinds: Set[String] = Set("report-export", "dispute-report", "day-end-export", "compliance-export")

  /** The first field out of its range, naming it and the range; a currency is three capital letters. */
  def validate(s: ReportingSettingsState): Option[String] =
    SyncRows.problem(s.syncRows)
      .orElse(SyncDays.problem(s.syncDays))
      .orElse(s.lifetimeHours.toSeq.sortBy(_._1).collectFirst {
        case (kind, _) if !Kinds(kind) => s"$Code: lifetimeHours.$kind is not a document kind (${Kinds.toSeq.sorted.mkString(", ")})"
        case (kind, h) if LifetimeHours.problem(h).nonEmpty =>
          s"$Code: lifetimeHours.$kind is ${LifetimeHours.min} to ${LifetimeHours.max}, not $h"
      })
      .orElse(s.defaultCurrency.filterNot(_.matches("[A-Z]{3}")).map(c => s"$Code: defaultCurrency is a three-letter code, not $c"))

  /** The settings' one entity id: there is one per brand project. */
  val Id = "brand"

  given Serializer[ReportingSettingsState] = ReportingCodecs.serializer[ReportingSettingsState]("settings-reply")

  def create(context: KeyValueEntityContext) = new ReportingSettings(context)

  val put = command("put")(_.put)
  val get = query("get")(_.get)
