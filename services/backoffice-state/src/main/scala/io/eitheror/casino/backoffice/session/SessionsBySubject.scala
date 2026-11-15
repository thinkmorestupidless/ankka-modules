package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.backoffice.StateCodecs

/**
 * A session's row: its id, the realm's subject it belongs to, and whether it is open. The subject is the
 * data subject's id, in the clear in every envelope already; nothing else of the operator is here.
 */
final case class SessionRow(session: String, subject: String, open: Boolean)

final class SessionsBySubjectRows extends View[SessionEvent, SessionRow]:
  def onChange(event: SessionEvent): Effect = event match
    case e: SessionEvent.Opened =>
      effects.updateRow(SessionRow(updateContext.subject, Session.subjectOf(e.subject.subject), open = true))
    case _: SessionEvent.Ended =>
      rowState.fold(effects.ignore())(r => if r.open then effects.updateRow(r.copy(open = false)) else effects.ignore())
    case _: SessionEvent.Refreshed => effects.ignore()

/** Session ids by subject (data-model.md), for sign-out everywhere and erasure. A deleted session's row goes with it. */
object SessionsBySubject
    extends View.Companion[SessionsBySubjectRows, SessionEvent, SessionRow](
      componentId = ComponentId("sessions-by-subject"),
      source = ChangeSource.eventsOf(Session),
      rowSerializer = StateCodecs.serializer[SessionRow]("session-row")
    ):
  def create(ctx: ViewComponentContext) = new SessionsBySubjectRows

  val openOfSubject: DeclaredQuery = query("open-of-subject")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'subject' = :subject AND (payload::jsonb->>'open')::boolean ORDER BY row_key")

  /** The subject's open sessions. */
  def open(views: ViewClient, subject: String): Vector[SessionRow] =
    views.forView(SessionsBySubject).ask(openOfSubject, 1000, "subject" -> subject)
