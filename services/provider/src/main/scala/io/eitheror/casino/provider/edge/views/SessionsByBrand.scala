package io.eitheror.casino.provider.edge.views

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.provider.core.{Session, SessionEntity, SessionEvent}

/**
 * Every session, its brand's to read (FR-021): the row is the session as its events fold it,
 * through the entity's own fold, so a round reads here as it does on the entity. Queried by brand
 * in launch order with keyset paging, since a view has a row limit and no offset. Eventually
 * consistent: a game is answered from the entity, never from here.
 */
final class SessionsByBrandView extends View[SessionEvent, Session]:

  def onChange(event: SessionEvent): Effect =
    event match
      case SessionEvent.Discarded => effects.deleteRow()
      case e =>
        val before = rowState.getOrElse(Session.empty(updateContext.subject))
        val after  = Session.applied(before, e)
        if after.launched then effects.updateRow(after) else effects.ignore()

object SessionsByBrand
    extends View.Companion[SessionsByBrandView, SessionEvent, Session](
      componentId = ComponentId("sessions-by-brand"),
      source = ChangeSource.eventsOf(SessionEntity),
      rowSerializer = Codecs.serializer[Session]("sessions-by-brand-row")
    ):
  def create(ctx: ViewComponentContext) = new SessionsByBrandView

  /** A brand's sessions in launch order, after a cursor: `after` is the last row's `launchedAt:launchToken`. */
  val byBrand = query("by-brand")(
    s"""SELECT payload FROM $table
       |WHERE payload::jsonb->>'brand' = :brand
       |  AND ((payload::jsonb->>'launchedAt')::bigint, payload::jsonb->>'launchToken') > ((:after_at)::bigint, :after_id)
       |ORDER BY (payload::jsonb->>'launchedAt')::bigint, payload::jsonb->>'launchToken'""".stripMargin
  )

  /** The cursor after `s`, as `byBrand` reads it. */
  def cursorAfter(s: Session): String = s"${s.launchedAt}:${s.launchToken}"

  /** `after` as `(at, id)`; none or malformed starts at the beginning. */
  def cursor(after: Option[String]): (String, String) =
    after.flatMap { a =>
      a.split(":", 2) match
        case Array(at, id) if at.toLongOption.isDefined => Some((at, id))
        case _                                         => None
    }.getOrElse(("-1", ""))
