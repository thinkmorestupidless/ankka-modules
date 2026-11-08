package io.eitheror.casino.engagement.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs
import io.eitheror.casino.engagement.send.{Outcome, Send, SendState}

import java.time.Instant

/**
 * One send's row (data-model.md `SendsView`), from the workflow's state. The player is named by id only: the
 * recipient, the data and a discard's reason, which are personal, are not here.
 */
final case class SendRow(
    key: String,
    playerId: String = "",
    trigger: String = "",
    event: String = "",
    sendClass: String = "",
    channel: String = "",
    outcome: String = Outcome.Pending,
    reason: String = "",
    factId: String = "",
    occurredAt: Instant = Instant.EPOCH,
    updatedAt: Instant = Instant.EPOCH,
    parked: Boolean = false,
    parkedAt: Option[Instant] = None
)

object SendRow:
  given Serializer[SendRow] = EngagementCodecs.row[SendRow]("send-row")

  def of(key: String, s: SendState): Option[SendRow] =
    s.request.map(r => SendRow(key, r.playerId, r.trigger, r.event.getOrElse(""), r.sendClass, r.channel, s.outcome,
      s.reason.getOrElse(""), r.factId, r.occurredAt, s.updatedAt.getOrElse(r.occurredAt), s.outcome == Outcome.Parked,
      s.parkedAt.filter(_ => s.outcome == Outcome.Parked)))

final class SendsViewUpdater extends View[SendState, SendRow]:
  def onChange(s: SendState): Effect =
    SendRow.of(updateContext.subject, s) match
      case Some(row) if !rowState.contains(row) => effects.updateRow(row)
      case _                                    => effects.ignore()

object SendsView extends View.Companion[SendsViewUpdater, SendState, SendRow](
      componentId = ComponentId("sends-view"),
      source = ChangeSource.stateOf(Send),
      rowSerializer = SendRow.given_Serializer_SendRow
    ):
  def create(ctx: ViewComponentContext) = new SendsViewUpdater

  /** The routes' page (contracts/routes.md): 50 rows. */
  val PageSize = 50

  final case class Page(rows: Vector[SendRow], total: Long, page: Int)

  private def and(parts: Vector[SqlFragment]): SqlFragment =
    if parts.isEmpty then sql"1 = 1" else parts.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)
  private def at(field: String) = SqlFragment.raw(s"(payload::jsonb->>'$field')::timestamptz")
  private val newest            = at("updatedAt") ++ SqlFragment.raw(" DESC, payload::jsonb->>'key' DESC")
  private val isParked          = SqlFragment.raw("payload::jsonb->>'parked' = 'true'")
  private def player(id: String) = jsonText("playerId") ++ sql" = $id"

  /** `%text%` for ILIKE, the pattern's own characters escaped. */
  private def containing(text: String): String =
    "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

  private def paged(view: ViewQueries[SendRow], condition: SqlFragment, page: Int): Page =
    val p = math.max(1, page)
    Page(view.ordered(condition, newest, p * PageSize).drop((p - 1) * PageSize), view.count(condition), p)

  /** A player's sends, newest first (`GET /players/{player}/sends`). */
  def byPlayer(view: ViewQueries[SendRow], playerId: String, page: Int = 1): Page = paged(view, player(playerId), page)

  /** A player's sends not yet ended, pending or parked, for erasure: one query, however many sends they have. */
  def open(view: ViewQueries[SendRow], playerId: String): Vector[SendRow] =
    view.ordered(and(Vector(player(playerId), jsonText("outcome") ++ sql" IN (${Outcome.Pending}, ${Outcome.Parked})")), newest,
      OpenLimit)

  /** More open sends than any one player has: a player's sends end within the attempts' minutes or park. */
  private val OpenLimit = 10000

  /** The parked sends, newest first, `search` on the trigger and the reason, any case (`GET /parked-sends`). */
  def parked(view: ViewQueries[SendRow], search: Option[String] = None, page: Int = 1): Page =
    val term = search.map(_.trim).filter(_.nonEmpty).map(containing)
    paged(view, and(Vector(Some(isParked), term.map(t =>
      SqlFragment.raw("(") ++ jsonText("trigger") ++ sql" ILIKE $t OR " ++ jsonText("reason") ++ sql" ILIKE $t)")).flatten), page)

  /** How many sends reached each outcome since `since` (the dashboard). */
  def counts(view: ViewQueries[SendRow], since: Instant): Map[String, Long] =
    Outcome.all.map(o => o -> view.count(and(Vector(jsonText("outcome") ++ sql" = $o", at("updatedAt") ++ sql" >= ${since.toString}::timestamptz"))))
      .filter(_._2 > 0).toMap

  /** The send parked longest (the alert). */
  def oldestParked(view: ViewQueries[SendRow]): Option[SendRow] =
    view.ordered(isParked, at("parkedAt") ++ SqlFragment.raw(" ASC, payload::jsonb->>'key' ASC"), 1).headOption
