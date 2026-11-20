package io.eitheror.casino.rg.cases

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.RgCodecs
import io.eitheror.casino.rg.profile.{ProfileEntity, ProfileEvent, RgCase}

import java.time.Instant

/**
 * One case of the brand's, as the brand-wide list shows it (spec 030 G2). It holds no personal value: a case's
 * notes and its close's note stay on the player's profile, so erasure has nothing to change here.
 */
final case class CaseRow(
    caseId: String,
    playerId: String,
    caseType: String,
    trigger: String,
    status: String,
    openedAt: Long,
    closesAt: Option[Long] = None,
    closedAt: Option[Long] = None,
    closeReason: Option[String] = None
)

object CaseRow:
  def of(playerId: String, c: RgCase): CaseRow =
    CaseRow(c.caseId, playerId, c.caseType, c.trigger, c.status, c.openedAt.toEpochMilli, c.closesAt.map(_.toEpochMilli),
      c.closedAt.map(_.toEpochMilli), c.closeReason)

/**
 * The brand's cases, a row per case under its id, from the profiles' events: a case is opened, its end moved
 * when its break is, and closed. A profile holds many cases, so the view is keyed, not one row per profile.
 */
final class CasesViewRows extends KeyedView[CaseRow]:
  import ProfileEvent.*

  def onProfile(event: ProfileEvent, change: Change): Effect = event match
    case e: CaseOpened => effects.updateRow(e.rgCase.caseId, CaseRow.of(e.playerId, e.rgCase))
    case e: BreakSet if e.replaces =>
      change.rows.get(e.caseId).fold(effects.ignore())(r => effects.updateRow(e.caseId, r.copy(closesAt = Some(e.until.toEpochMilli))))
    case e: CaseClosed =>
      change.rows.get(e.caseId).fold(effects.ignore())(r =>
        effects.updateRow(e.caseId, r.copy(status = RgCase.Closed, closedAt = Some(e.at.toEpochMilli), closeReason = Some(e.reason))))
    case _ => effects.ignore()

object CasesView extends KeyedView.Companion[CasesViewRows, CaseRow](
      ComponentId("rg-cases"),
      RgCodecs.serializer[CaseRow]("case-row")
    ):
  def create(ctx: ViewComponentContext) = new CasesViewRows

  val profiles = source(ChangeSource.eventsOf(ProfileEntity))(_.onProfile)

  private val opened = "COALESCE((payload::jsonb->>'openedAt')::bigint, 0)"

  /** A status's cases (`''` for every one), oldest opened first, from `:offset`. */
  val byStatus: DeclaredQuery = query("by-status")(
    s"SELECT payload FROM $table WHERE (:status = '' OR payload::jsonb->>'status' = :status) ORDER BY $opened ASC, row_key ASC OFFSET CAST(:offset AS int)")

  val Statuses: Set[String] = Set(RgCase.Open, RgCase.Closed)

  final case class Page(rows: Vector[CaseRow], total: Long, page: Int, pageSize: Int)

  val MaxPageSize = 200

  /** One page of a status (none for every one), oldest opened first, with the status's total. */
  def page(views: ViewClient, status: Option[String], page: Int, pageSize: Int): Page =
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
    val q     = views.forView(CasesView)
    val rows  = q.ask(byStatus, pageSize, "status" -> status.getOrElse(""), "offset" -> ((page - 1).toLong * pageSize).toString)
    val total = status.fold(q.count(sql"1 = 1"))(s => q.count(jsonText("status") ++ sql" = $s"))
    Page(rows, total, page, pageSize)

  def instant(millis: Long): String = Instant.ofEpochMilli(millis).toString
