package io.eitheror.casino.players.fraud

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/** A suspected duplicate as the operator lists one (FR-036). */
final case class FraudCaseRow(caseId: String, playerIds: Vector[String], signals: Vector[String], status: String, openedAt: Long)

final class FraudCasesViewRows extends View[FraudCaseEvent, FraudCaseRow]:
  def onChange(event: FraudCaseEvent): Effect = (rowState, event) match
    case (None, e: FraudCaseEvent.Opened) =>
      effects.updateRow(FraudCaseRow(updateContext.subject, e.playerIds.toVector.sorted, e.signals.toVector.sorted,
        FraudCaseStatus.Open, e.at.toEpochMilli))
    case (Some(r), _: FraudCaseEvent.Reviewed)  => effects.updateRow(r.copy(status = FraudCaseStatus.Reviewed))
    case (Some(r), _: FraudCaseEvent.Dismissed) => effects.updateRow(r.copy(status = FraudCaseStatus.Dismissed))
    case _                                      => effects.ignore()

object FraudCasesView extends View.Companion[FraudCasesViewRows, FraudCaseEvent, FraudCaseRow](
      componentId = ComponentId("fraud-cases"),
      source = ChangeSource.eventsOf(FraudCaseEntity),
      rowSerializer = PlayersCodecs.serializer[FraudCaseRow]("fraud-case-row")
    ):
  def create(ctx: ViewComponentContext) = new FraudCasesViewRows

  private val opened = "COALESCE((payload::jsonb->>'openedAt')::bigint, 0)"

  /** One page of a status (`''` for every one), newest first, from `:offset` (spec 030 P11). */
  val byStatusPage: DeclaredQuery = query("by-status-page")(
    s"SELECT payload FROM $table WHERE (:status = '' OR payload::jsonb->>'status' = :status) ORDER BY $opened DESC, row_key ASC OFFSET CAST(:offset AS int)")

  /** One page of a status, oldest first, from `:offset`: the open cases' order, the longest waiting first (spec 030 P11). */
  val byStatusOldest: DeclaredQuery = query("by-status-oldest")(
    s"SELECT payload FROM $table WHERE (:status = '' OR payload::jsonb->>'status' = :status) ORDER BY $opened ASC, row_key ASC OFFSET CAST(:offset AS int)")

  /** The cases naming a player. */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE COALESCE(payload::jsonb->'playerIds', '[]'::jsonb) @> jsonb_build_array(CAST(:player AS text)) ORDER BY $opened DESC, row_key ASC")

  val byId: DeclaredQuery = query("one-case")(s"SELECT payload FROM $table WHERE row_key = :id")

  /**
   * Cases of a status from `offset`, at most `limit`: the open ones oldest first, the others (and every status
   * at once) newest first (spec 030 P11).
   */
  def withStatus(views: ViewClient, status: Option[String], offset: Int, limit: Int): Vector[FraudCaseRow] =
    val wire = status.fold("")(_.toLowerCase)
    views.forView(FraudCasesView).ask(if wire == FraudCaseStatus.Open then byStatusOldest else byStatusPage, limit,
      "status" -> wire, "offset" -> offset.max(0).toString)

  /** How many cases a status (none for every one) holds. */
  def total(views: ViewClient, status: Option[String]): Long =
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
    val q = views.forView(FraudCasesView)
    status.map(_.toLowerCase).fold(q.count(sql"1 = 1"))(s => q.count(jsonText("status") ++ sql" = $s"))

  def ofPlayer(views: ViewClient, playerId: String): Vector[FraudCaseRow] =
    views.forView(FraudCasesView).ask(byPlayer, 1000, "player" -> playerId)

  def one(views: ViewClient, caseId: String): Option[FraudCaseRow] =
    views.forView(FraudCasesView).ask(byId, 1, "id" -> caseId).headOption
