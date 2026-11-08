package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.comms.{Notification, NotificationState}

import java.time.Instant

/** One entry of an affiliate's activity, typed by `kind` (data-model.md *Activity*). */
final case class ActivityRow(
    kind: String = "",
    id: String = "",
    affiliate: String = "",
    brand: Option[String] = None,
    action: String = "",
    actor: String = "",
    at: Instant = Instant.EPOCH,
    details: Map[String, String] = Map.empty
)

object ActivityRow:
  given Serializer[ActivityRow] = ProgrammeCodecs.serializer[ActivityRow]("activity-row")

/** A row per entry, by affiliate and brand: audit entries, notes, notifications and fraud flags. */
final class ActivityView extends KeyedView[ActivityRow]:
  def onAudit(s: AuditState, change: Change): Effect =
    s.entry match
      case Some(a) =>
        effects.updateRow(s"audit|${change.subject}",
          ActivityRow(Activity.Audit, change.subject, a.affiliate, a.brand, a.action, a.actor, a.at, a.details))
      case None => effects.ignore()

  def onNote(s: NoteState, change: Change): Effect =
    if !s.exists then effects.ignore()
    else
      effects.updateRow(s"note|${change.subject}",
        ActivityRow(Activity.NoteKind, change.subject, s.affiliate, Some(s.brand), "NOTE", s.author, s.createdAt.getOrElse(Instant.EPOCH),
          Map("text" -> s.text)))

  /** `read` set once read; `type` the original's notification type. */
  def onNotification(s: NotificationState, change: Change): Effect =
    if !s.exists then effects.ignore()
    else
      effects.updateRow(s"notification|${change.subject}",
        ActivityRow(Activity.NotificationKind, change.subject, s.affiliate, s.brand, s.kind, "", s.createdAt.getOrElse(Instant.EPOCH),
          Map("body" -> s.body) ++ s.readAt.map(r => "readAt" -> r.toString)))

  def onFlag(s: FlagState, change: Change): Effect =
    if !s.raised then effects.ignore()
    else
      effects.updateRow(s"flag|${change.subject}",
        ActivityRow(Activity.FlagKind, s.flagId, s.affiliate, Some(s.brand), if s.dismissed then "FRAUD_DISMISSED" else "FRAUD_FLAGGED",
          s.dismissedBy.getOrElse(""), s.raisedAt.getOrElse(Instant.EPOCH), Map("reason" -> s.reason, "pseudonym" -> s.pseudonym)))

object Activity extends KeyedView.Companion[ActivityView, ActivityRow](
      componentId = ComponentId("activity"),
      rowSerializer = ActivityRow.given_Serializer_ActivityRow
    ):
  val Audit            = "audit"
  val NoteKind         = "note"
  val NotificationKind = "notification"
  val FlagKind         = "flag"

  val audits        = source(ChangeSource.stateOf(AuditEntry))(_.onAudit)
  val notes         = source(ChangeSource.stateOf(Note))(_.onNote)
  val notifications = source(ChangeSource.stateOf(Notification))(_.onNotification)
  val flags         = source(ChangeSource.stateOf(FraudFlag))(_.onFlag)

  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY payload::jsonb->>'at', row_key")
  /** One kind of an affiliate's entries, newest first. */
  val ofKind = query("of-kind")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate AND payload::jsonb->>'kind' = :kind " +
      "ORDER BY payload::jsonb->>'at' DESC, row_key")

  /** Entries of one action, whoever they are about: the export's `payment_details_read` names no one affiliate. */
  val byAction = query("by-action")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'action' = :action ORDER BY payload::jsonb->>'at', row_key")

  def create(ctx: ViewComponentContext) = new ActivityView
