package io.eitheror.casino.audit.exports

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import io.eitheror.casino.audit.AuditCodecs.given
import io.eitheror.casino.audit.stored.{Stored, StoredChange}

/**
 * An export's lines (research R10). CSV: the record's fields in FR-001's order, its changes flattened
 * as `field:before>after;…`, a personal field written `field:`. JSON lines: one record per line as the
 * store holds it. Either way the persons are pseudonyms: the store holds nothing else.
 */
object Csv:
  val Columns: Vector[String] = Vector("occurredAt", "brand", "actorKind", "actor", "approver", "subjectKind", "subject", "service",
    "action", "outcome", "reasonCode", "changes", "amount", "currency", "causation", "correlation", "category", "visibility",
    "source", "key")

  val header: String = Columns.mkString(",")

  def changes(cs: Vector[StoredChange]): String =
    cs.map { c =>
      if c.before.isEmpty && c.after.isEmpty then s"${c.field}:" else s"${c.field}:${c.before.getOrElse("")}>${c.after.getOrElse("")}"
    }.mkString(";")

  def line(s: Stored): String =
    Vector(s.occurredAt.toString, s.brand, s.actorKind, s.actor, s.approver.getOrElse(""), s.subjectKind, s.subject, s.service,
      s.action, s.outcome, s.reasonCode.getOrElse(""), changes(s.changes), s.amount.fold("")(_.toString), s.currency.getOrElse(""),
      s.causation, s.correlation, s.category, s.visibility, s.source, s.key).map(cell).mkString(",")

  /** RFC 4180: a cell holding a comma, a quote or a line break is quoted, its quotes doubled. */
  def cell(v: String): String =
    if v.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then "\"" + v.replace("\"", "\"\"") + "\"" else v

object JsonLines:
  def line(s: Stored): String = writeToString(s)
