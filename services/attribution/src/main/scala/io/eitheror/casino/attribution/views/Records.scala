package io.eitheror.casino.attribution.views

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs
import io.eitheror.casino.attribution.record.{AttributionRecord, Record}
import io.eitheror.casino.attribution.record.Record.*

import java.time.Instant

/** One player's record as the operator lists it (data-model.md *Views*). */
final case class RecordRow(
    playerId: String,
    network: Option[String] = None,
    affiliate: Option[String] = None,
    bound: Boolean = false,
    touches: Int = 0,
    firstDepositAt: Option[Instant] = None,
    closed: Option[String] = None,
    erased: Boolean = false
)

object RecordRow:
  given Serializer[RecordRow] = AttributionCodecs.serializer[RecordRow]("record-row")

  def next(row: RecordRow, event: Record.Event): RecordRow = event match
    case Bound(b, _, _, _)            => row.copy(network = Some(b.network), affiliate = Some(b.affiliate), bound = true)
    case TouchRecorded(_)             => row.copy(touches = math.min(row.touches + 1, Record.MaxTouches))
    case PendingRecorded(e, true)     => row.copy(firstDepositAt = row.firstDepositAt.orElse(Some(e.at)))
    case DepositRecorded(e, true, _)  => row.copy(firstDepositAt = row.firstDepositAt.orElse(Some(e.at)))
    case Closed(category, _, _)       => row.copy(closed = Some(category))
    case Erased(_)                    => row.copy(erased = true)
    case _                            => row

final class RecordsView extends View[Record.Event, RecordRow]:
  def onChange(event: Record.Event): Effect =
    val row  = rowState.getOrElse(RecordRow(updateContext.subject))
    val next = RecordRow.next(row, event)
    if rowState.isDefined && next == row then effects.ignore() else effects.updateRow(next)

object Records extends View.Companion[RecordsView, Record.Event, RecordRow](
      componentId = ComponentId("records"),
      source = ChangeSource.eventsOf(AttributionRecord),
      rowSerializer = RecordRow.given_Serializer_RecordRow
    ):
  /** One player's row; the row's key is the player id. */
  val byPlayer = query("by-player")(s"SELECT payload FROM $table WHERE row_key = :player")

  /** The players bound to a network, for `countsByNetwork` and the operator's listing. */
  val boundTo = query("bound-to")(s"SELECT payload FROM $table WHERE payload::jsonb->>'network' = :network ORDER BY row_key")

  /**
   * How many players each network holds: a declared query answers rows of the view's own type, so the
   * counts are the view client's `count` per network.
   */
  def countsByNetwork(rows: com.thinkmorestupidless.ankka.runtime.ViewQueries[RecordRow], networks: Iterable[String]): Map[String, Long] =
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
    networks.map(n => n -> rows.count(jsonText("network") ++ sql" = $n")).toMap

  def create(ctx: ViewComponentContext) = new RecordsView
