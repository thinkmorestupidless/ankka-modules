package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewQueries
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

/** One quarantined message's row: its source and why, never its bytes. */
final case class QuarantineRow(id: String, source: String, why: String)

object QuarantineRow:
  given Serializer[QuarantineRow] = AuditCodecs.row[QuarantineRow]("quarantine-row")

final class QuarantineUpdater extends View[QuarantinedState, QuarantineRow]:
  def onChange(s: QuarantinedState): Effect =
    val row = QuarantineRow(updateContext.subject, s.source, s.why)
    if s.held && !rowState.contains(row) then effects.updateRow(row) else effects.ignore()

/** The quarantine by source (data-model.md `Quarantine`), for the status. */
object Quarantine extends View.Companion[QuarantineUpdater, QuarantinedState, QuarantineRow](
      componentId = ComponentId("quarantine"),
      source = ChangeSource.stateOf(Quarantined),
      rowSerializer = QuarantineRow.given_Serializer_QuarantineRow
    ):
  def create(ctx: ViewComponentContext) = new QuarantineUpdater

  def countBySource(view: ViewQueries[QuarantineRow], source: String): Long = view.count(jsonText("source") ++ sql" = $source")
