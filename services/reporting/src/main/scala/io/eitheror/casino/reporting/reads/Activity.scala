package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, Grant, GrantEvent, PlayerLog, PlayerLogEvent}

import java.time.Instant

/**
 * One entry of the dashboard's activity feed (data-model.md `Activity`; FR-014): a registration, an award, a
 * self-exclusion or a suspension, under the original's event id (`reg-<pid>`, `bonus-<grant>`, `selfex-<pid>`,
 * `susp-<pid>`, `C/DashboardRoutes.scala:712-790`), so a player's later self-exclusion replaces their earlier one as the
 * original's twin property did. No personal value: the feed's truncated email is read from `Players`.
 */
final case class ActivityRow(generation: Int, eventId: String = "", eventType: String = "", player: String = "",
    occurredAt: Instant = Instant.EPOCH)

object ActivityRow:
  given Serializer[ActivityRow] = ReportingCodecs.row[ActivityRow]("activity-row")

object Activities:
  val Registration  = "registration"
  val BonusGrant    = "bonus_grant"
  val SelfExclusion = "self_exclusion"
  val Suspension    = "suspension"

  def ofPlayer(e: PlayerLogEvent): Option[(String, String, Instant)] = e match
    case r: PlayerLogEvent.Registered                                           => Some((s"reg-${r.player}", Registration, r.registeredAt))
    case s: PlayerLogEvent.StatusChanged if s.family == "player_self_excluded" => Some((s"selfex-${s.player}", SelfExclusion, s.at))
    case s: PlayerLogEvent.StatusChanged if s.family == "player_suspended"     => Some((s"susp-${s.player}", Suspension, s.at))
    case _                                                                      => None

  def ofGrant(e: GrantEvent): Option[(String, String, Instant)] = e match
    case g: GrantEvent.Granted => Some((s"bonus-${g.bonusId}", BonusGrant, g.at))
    case _                     => None

final class ActivityView extends KeyedView[ActivityRow]:
  private def row(subject: String, player: String, entry: Option[(String, String, Instant)]): Effect =
    (Generation.of(subject), entry) match
      case (Some(g), Some((id, kind, at))) => effects.updateRow(Generation.id(g, id), ActivityRow(g, id, kind, player, at))
      case _                               => effects.ignore()

  def onPlayer(event: PlayerLogEvent, change: Change): Effect = row(change.subject, event.player, Activities.ofPlayer(event))
  def onGrant(event: GrantEvent, change: Change): Effect      = row(change.subject, event.player, Activities.ofGrant(event))

object Activity extends KeyedView.Companion[ActivityView, ActivityRow](
      ComponentId("activity"),
      ActivityRow.given_Serializer_ActivityRow
    ):
  val players = source(ChangeSource.eventsOf(PlayerLog))(_.onPlayer)
  val grants  = source(ChangeSource.eventsOf(Grant))(_.onGrant)

  /** The entries from `at` on, newest first. */
  val since = query("since")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'occurredAt')::timestamptz >= (:at)::timestamptz ORDER BY (payload::jsonb->>'occurredAt')::timestamptz DESC, row_key")

  def create(ctx: ViewComponentContext) = new ActivityView

  def from(views: ViewClient, generation: Int, at: Instant, limit: Int): Vector[ActivityRow] =
    views.forView(Activity).ask(since, limit, "generation" -> generation.toString, "at" -> at.toString)
