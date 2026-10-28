package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

/** One fact of a network's feed, numbered (research R14). */
final case class FeedRow(network: String, seq: Long, factId: String, kind: String, fact: String)

object FeedRow:
  given Serializer[FeedRow] = AttributionCodecs.serializer[FeedRow]("feed-row")

/**
 * The feed's facts, for the page (contracts/routes.md). A plain view keeps one row per source id, and the
 * source is the network's feed, so this is a keyed view: each `Appended` is a row of its own, keyed
 * `<network>/<seq>`.
 */
final class FeedRowsView extends KeyedView[FeedRow]:
  def onFeed(event: FeedEvent, change: Change): Effect = event match
    case FeedEvent.Appended(seq, f) =>
      effects.updateRow(FeedRows.key(change.subject, seq), FeedRow(change.subject, seq, f.factId, f.kind, f.json))

object FeedRows extends KeyedView.Companion[FeedRowsView, FeedRow](
      ComponentId("feed-rows"),
      FeedRow.given_Serializer_FeedRow
    ):
  def key(network: String, seq: Long): String = s"$network/$seq"

  val feeds = source(ChangeSource.eventsOf(NetworkFeed))(_.onFeed)

  /** A network's facts after `after`, in order; the caller's limit is the page's, at most 1,000. */
  val page = query("page")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'network' = :network AND (payload::jsonb->>'seq')::bigint > (:after)::bigint ORDER BY (payload::jsonb->>'seq')::bigint")

  def create(ctx: ViewComponentContext) = new FeedRowsView
