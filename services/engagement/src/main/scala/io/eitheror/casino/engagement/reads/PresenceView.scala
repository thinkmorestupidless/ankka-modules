package io.eitheror.casino.engagement.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs
import io.eitheror.casino.engagement.channel.{Channel, ChannelEvent}
import io.eitheror.casino.engagement.channel.ChannelEvent.*

import java.time.Instant

/**
 * Whether a player has a connection anywhere, and since when (`GET /presence`), and the instance each open connection
 * is held on (the status's connections per instance, FR-027). The player by id only.
 */
final case class PresenceRow(playerId: String, online: Boolean = false, since: Option[Instant] = None,
    connections: Map[String, String] = Map.empty)

object PresenceRow:
  given Serializer[PresenceRow] = EngagementCodecs.row[PresenceRow]("presence-row")

  /** Online at the first open, offline when the last connection closes or expires (the channel's counts, R10). */
  def next(row: PresenceRow, event: ChannelEvent): PresenceRow = event match
    case Opened(id, instance, n, at) =>
      val opened = row.copy(connections = row.connections.updated(id, instance))
      if n == 1 then opened.copy(online = true, since = Some(at)) else opened
    case Closed(id, _, n, _)  => gone(row, id, n)
    case Expired(id, _, n, _) => gone(row, id, n)
    case _                    => row

  private def gone(row: PresenceRow, id: String, left: Int): PresenceRow =
    val after = row.copy(connections = row.connections - id)
    if left == 0 then after.copy(online = false, since = None, connections = Map.empty) else after

final class PresenceViewUpdater extends View[ChannelEvent, PresenceRow]:
  def onChange(event: ChannelEvent): Effect =
    val next = PresenceRow.next(rowState.getOrElse(PresenceRow(updateContext.subject)), event)
    if rowState.contains(next) then effects.ignore() else effects.updateRow(next)

/**
 * The brand's players with an open push connection, read from every channel's events: cluster-wide, as the channel
 * is (research R10). Not in data-model.md's list; the presence route needs a list the per-player channel cannot give
 * (research, *Found during implementation*).
 */
object PresenceView extends View.Companion[PresenceViewUpdater, ChannelEvent, PresenceRow](
      componentId = ComponentId("presence-view"),
      source = ChangeSource.eventsOf(Channel),
      rowSerializer = PresenceRow.given_Serializer_PresenceRow
    ):
  def create(ctx: ViewComponentContext) = new PresenceViewUpdater

  /** The most players one answer lists; the count is the whole. */
  val Listed = 1000

  private val isOnline = SqlFragment.raw("payload::jsonb->>'online' = 'true'")

  /** More online players than a brand holds: each instance takes at most `MAX_CONNECTIONS`. */
  private val Counted = 1000000

  /** The open connections across the cluster, by the instance holding them (FR-027). */
  def connectionsByInstance(view: ViewQueries[PresenceRow]): Map[String, Long] =
    view.where(isOnline, Counted).flatMap(_.connections.values).groupMapReduce(identity)(_ => 1L)(_ + _)

  /** The players online, longest first, and how many there are. */
  def online(view: ViewQueries[PresenceRow]): (Vector[PresenceRow], Long) =
    (view.ordered(isOnline, SqlFragment.raw("payload::jsonb->>'since' ASC, payload::jsonb->>'playerId' ASC"), Listed),
      view.count(isOnline))
