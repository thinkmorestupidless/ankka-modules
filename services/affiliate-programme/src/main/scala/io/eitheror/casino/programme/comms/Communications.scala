package io.eitheror.casino.programme.comms

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.accounts.Membership

import java.time.Instant

/** What an affiliate is told of one change: in the portal, and by email when `email` names a message kind. */
final case class Notice(affiliate: String, brand: String, kind: String, body: String, email: Option[String], data: Map[String, String] = Map.empty)

/**
 * The notifications and emails a membership's changes send (FR-035; the original's types and its
 * Customer.io messages): an application received, approved or rejected, and a sub's pending application
 * to its parent. US7 adds the rest.
 */
object Notices:
  def ofMembership(e: Membership.Event, current: (String, String), portal: String): Vector[Notice] =
    val (affiliate, brand) = current
    e match
      case Membership.Applied(_, _, app) =>
        Notice(affiliate, brand, "APPLICATION_SUBMITTED", s"Your application to $brand was received.", Some("AffiliateApplicationReceived")) +:
          app.parent.toVector.map(p => Notice(p, brand, "SUB_AFFILIATE_PENDING", s"An affiliate you invited applied to $brand.", Some("AffiliateSubPending")))
      case Membership.Approved(_, _, _) =>
        Vector(Notice(affiliate, brand, "APPLICATION_APPROVED", s"Your application to $brand was approved.", Some("AffiliateApplicationApproved"),
          Map("dashboard_url" -> portal)))
      case Membership.ApplicationRejected(reason, _, _) =>
        Vector(Notice(affiliate, brand, "APPLICATION_REJECTED", s"Your application to $brand was not approved.", Some("AffiliateApplicationRejected"),
          reason.map("reason" -> _).toMap))
      case _ => Vector.empty

  /** The change's notification and email id: the same on every delivery of it. */
  def idOf(subject: String, n: Notice, at: Instant): String = Notification.idFor(s"membership/$subject/${n.affiliate}/${n.kind}/$at")

final class Communications(context: ConsumerContext) extends Consumer[Membership.Event, Unit]:
  def onMessage(event: Membership.Event): Effect =
    val subject = messageContext.subject
    val (affiliate, brand) = subject.split('/') match
      case Array(a, b) => (a, b)
      case _           => (subject, "")
    Notices.ofMembership(event, (affiliate, brand), Wiring.settings.get().portalBaseUrl).foreach { n =>
      val id = Notices.idOf(subject, n, event.at)
      context.componentClient.forKeyValueEntity(EntityId(id)).call(Notification.write)
        .invoke(WriteNotification(n.affiliate, Some(n.brand), n.kind, n.body, event.at)): Unit
      n.email.foreach { kind =>
        context.componentClient.forWorkflow(EntityId(id)).call(EmailSend.start).invoke(SendEmail(n.affiliate, n.brand, kind, n.data)): Unit
      }
    }
    effects.done()

object Communications extends Consumer.Companion[Communications, Membership.Event, Unit](
      componentId = ComponentId("communications"),
      source = ChangeSource.eventsOf(Membership)
    ):
  def create(ctx: ConsumerContext) = new Communications(ctx)
