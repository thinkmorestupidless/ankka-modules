package io.eitheror.casino.reporting.documents

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs

import java.time.Instant

/** A record as `GET /documents`, the sweep and an erasure read it: the record itself, which holds no personal field. */
final class DocumentRows extends View[DocumentEvent, DocumentState]:
  def onChange(event: DocumentEvent): Effect =
    effects.updateRow(Document.applied(rowState.getOrElse(DocumentState()), event))

/**
 * The records (data-model.md `Documents`): those still kept, those whose lifetime is over, and every one of a kind in a
 * period, newest first. Documents belong to no generation: a rebuild leaves them as they are.
 */
object Documents extends View.Companion[DocumentRows, DocumentEvent, DocumentState](
      componentId = ComponentId("documents"),
      source = ChangeSource.eventsOf(Document),
      rowSerializer = ReportingCodecs.row[DocumentState]("document-row")
    ):
  /** Every document still kept: generating or ready. */
  val kept = query("kept")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'status' IN ('${Document.Generating}', '${Document.Ready}') ORDER BY row_key")

  /** The ready documents whose lifetime ended before `at`: what the sweep deletes. */
  val pastLifetime = query("past-lifetime")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'status' = '${Document.Ready}' AND (payload::jsonb->>'expiresAt')::timestamptz <= (:at)::timestamptz ORDER BY row_key")

  /** The records of `kind` (`all` for every kind) generated from `from` (inclusive) to `to` (exclusive), newest first. */
  val byKind = query("by-kind")(
    s"SELECT payload FROM $table WHERE (:kind = 'all' OR payload::jsonb->>'kind' = :kind) AND (payload::jsonb->>'generatedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'generatedAt')::timestamptz < (:to)::timestamptz ORDER BY (payload::jsonb->>'generatedAt')::timestamptz DESC, row_key DESC")

  def create(ctx: ViewComponentContext) = new DocumentRows

  private val Every = 10_000_000

  def keptNow(views: ViewClient): Vector[DocumentState] = views.forView(Documents).ask(kept, Every)

  def expiredBy(views: ViewClient, at: Instant): Vector[DocumentState] =
    views.forView(Documents).ask(pastLifetime, Every, "at" -> at.toString)

  def listed(views: ViewClient, kind: String, from: Instant, to: Instant): Vector[DocumentState] =
    views.forView(Documents).ask(byKind, Every, "kind" -> kind, "from" -> from.toString, "to" -> to.toString)
