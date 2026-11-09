package io.eitheror.casino.audit.store

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewQueries
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs.given
import io.eitheror.casino.audit.query.{Cursor, Filters, Query}
import io.eitheror.casino.audit.stored.Stored

import java.time.Instant

/**
 * One row per record in the store (data-model.md `Records`), the stored record itself. A record's
 * deletion by the sweep removes its row: the view's default for a deleted source (research R4).
 */
final class RecordsUpdater extends View[RecordState, Stored]:
  def onChange(s: RecordState): Effect =
    s.stored match
      case Some(row) if !rowState.contains(row) => effects.updateRow(row)
      case _                                    => effects.ignore()

object Records extends View.Companion[RecordsUpdater, RecordState, Stored](
      componentId = ComponentId("records"),
      source = ChangeSource.stateOf(Record),
      rowSerializer = io.eitheror.casino.audit.AuditCodecs.given_Serializer_Stored
    ):
  def create(ctx: ViewComponentContext) = new RecordsUpdater

  /** A page in order of occurrence, oldest first, and the cursor after its last row when there may be more. */
  final case class Page(rows: Vector[Stored], next: Option[Cursor])

  def page(view: ViewQueries[Stored], filters: Filters, after: Option[Cursor], limit: Int): Page =
    // One more than asked says whether another page follows without a count.
    val rows = view.ordered(Query.condition(filters, after), Query.Oldest, limit + 1)
    val shown = rows.take(limit)
    Page(shown, Option.when(rows.size > limit)(shown.last).map(r => Cursor(r.occurredAt, r.key)))

  def count(view: ViewQueries[Stored], filters: Filters): Long = view.count(Query.condition(filters))

  /** The sweep's page: a brand's records older than the cutoff, 500 at a time. */
  val SweepPage = 500

  def olderThan(view: ViewQueries[Stored], brand: String, cutoff: Instant, after: Option[Cursor]): Vector[Stored] =
    view.ordered(Query.condition(Filters(brands = Some(Set(brand)), to = Some(cutoff)), after), Query.Oldest, SweepPage)

  /** Every record that matches, oldest first, as a stream: an export's. */
  def stream(view: ViewQueries[Stored], filters: Filters) = view.orderedStream(Query.condition(filters), Query.Oldest, None)
