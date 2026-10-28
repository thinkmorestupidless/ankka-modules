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

  /** Cases by status (`''` for every one), newest first: the original's `?status=`. */
  val byStatus: DeclaredQuery = query("by-status")(
    s"SELECT payload FROM $table WHERE (:status = '' OR payload::jsonb->>'status' = :status) ORDER BY $opened DESC, row_key ASC")

  /** The cases naming a player. */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE COALESCE(payload::jsonb->'playerIds', '[]'::jsonb) @> jsonb_build_array(CAST(:player AS text)) ORDER BY $opened DESC, row_key ASC")

  val byId: DeclaredQuery = query("one-case")(s"SELECT payload FROM $table WHERE row_key = :id")

  def withStatus(views: ViewClient, status: Option[String], limit: Int = 1000): Vector[FraudCaseRow] =
    views.forView(FraudCasesView).ask(byStatus, limit, "status" -> status.fold("")(_.toLowerCase))

  def ofPlayer(views: ViewClient, playerId: String): Vector[FraudCaseRow] =
    views.forView(FraudCasesView).ask(byPlayer, 1000, "player" -> playerId)

  def one(views: ViewClient, caseId: String): Option[FraudCaseRow] =
    views.forView(FraudCasesView).ask(byId, 1, "id" -> caseId).headOption
