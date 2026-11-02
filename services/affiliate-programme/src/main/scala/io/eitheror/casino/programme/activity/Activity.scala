package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

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

/** A row per entry, by affiliate and brand. Audit entries now; US6 adds notes, notifications and flags. */
final class ActivityView extends KeyedView[ActivityRow]:
  def onAudit(s: AuditState, change: Change): Effect =
    s.entry match
      case Some(a) =>
        effects.updateRow(s"audit|${change.subject}",
          ActivityRow(Activity.Audit, change.subject, a.affiliate, a.brand, a.action, a.actor, a.at, a.details))
      case None => effects.ignore()

object Activity extends KeyedView.Companion[ActivityView, ActivityRow](
      componentId = ComponentId("activity"),
      rowSerializer = ActivityRow.given_Serializer_ActivityRow
    ):
  val Audit = "audit"

  val audits = source(ChangeSource.stateOf(AuditEntry))(_.onAudit)

  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY payload::jsonb->>'at', row_key")

  def create(ctx: ViewComponentContext) = new ActivityView
