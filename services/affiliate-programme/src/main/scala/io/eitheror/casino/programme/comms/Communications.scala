package io.eitheror.casino.programme.comms

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.programme.accounts.Membership
import io.eitheror.casino.programme.settlement.{Payout, PayoutState}

import java.time.Instant

/** What an affiliate is told of one change: in the portal, and by email when `email` names a message kind. */
final case class Notice(affiliate: String, brand: String, kind: String, body: String, email: Option[String], data: Map[String, String] = Map.empty)

/**
 * The notifications and emails a membership's changes send (FR-035; the original's types and its
 * Customer.io messages): an application received, approved or rejected, and a sub's pending application
 * to its parent; a payout approved, rejected or paid.
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

  /**
   * A payout's changes, as the original's notifications (`PAYOUT_APPROVED`, `PAYOUT_REJECTED`) and its
   * `AffiliatePaymentProcessed` email when paid. The original wrote no notification for a payment; the
   * feature asks for one, `PAYOUT_PAID`.
   */
  def ofPayout(e: Payout.Event, p: PayoutState): Vector[Notice] =
    val total = Currency.from(p.currency).fold(_ => p.total.toString, Amounts.render(p.total, _))
    e match
      case Payout.PayoutApproved(_, _) =>
        Vector(Notice(p.affiliate, p.brand, "PAYOUT_APPROVED", s"Your payout for ${p.period} ($total ${p.currency}) has been approved.", None))
      case Payout.PayoutRejected(_, reason, _) =>
        Vector(Notice(p.affiliate, p.brand, "PAYOUT_REJECTED", s"Your payout for ${p.period} was rejected. Reason: ${reason.getOrElse("N/A")}", None))
      case Payout.PayoutPaid(_, at) =>
        Vector(Notice(p.affiliate, p.brand, "PAYOUT_PAID", s"Your payout for ${p.period} ($total ${p.currency}) has been paid.",
          Some("AffiliatePaymentProcessed"),
          Map("amount" -> total, "currency" -> p.currency, "paid_at" -> at.getEpochSecond.toString, "period_label" -> p.period)))
      case _ => Vector.empty

  /** Writes a notice's notification and starts its email, both under `id`: once each, however often it is told. */
  def tell(client: ComponentClient, id: String, n: Notice, at: Instant): Unit =
    client.forKeyValueEntity(EntityId(id)).call(Notification.write).invoke(WriteNotification(n.affiliate, Some(n.brand), n.kind, n.body, at)): Unit
    n.email.foreach(kind => client.forWorkflow(EntityId(id)).call(EmailSend.start).invoke(SendEmail(n.affiliate, n.brand, kind, n.data)): Unit)

  /** The change's notification and email id: the same on every delivery of it. */
  def idOf(subject: String, n: Notice, at: Instant): String = Notification.idFor(s"membership/$subject/${n.affiliate}/${n.kind}/$at")

final class Communications(context: ConsumerContext) extends Consumer[Membership.Event, Unit]:
  def onMessage(event: Membership.Event): Effect =
    val subject = messageContext.subject
    val (affiliate, brand) = subject.split('/') match
      case Array(a, b) => (a, b)
      case _           => (subject, "")
    Notices.ofMembership(event, (affiliate, brand), Wiring.settings.get().portalBaseUrl)
      .foreach(n => Notices.tell(context.componentClient, Notices.idOf(subject, n, event.at), n, event.at))
    effects.done()

object Communications extends Consumer.Companion[Communications, Membership.Event, Unit](
      componentId = ComponentId("communications"),
      source = ChangeSource.eventsOf(Membership)
    ):
  def create(ctx: ConsumerContext) = new Communications(ctx)

final class PayoutNotices(context: ConsumerContext) extends Consumer[Payout.Event, Unit]:
  def onMessage(event: Payout.Event): Effect =
    val subject = messageContext.subject
    lazy val state = context.componentClient.forEventSourcedEntity(EntityId(subject)).call(Payout.get).invoke()
    val at = event match
      case Payout.PayoutApproved(_, at)    => Some(at)
      case Payout.PayoutRejected(_, _, at) => Some(at)
      case Payout.PayoutPaid(_, at)        => Some(at)
      case _                               => None
    at.foreach(at => Notices.ofPayout(event, state).foreach(n => Notices.tell(context.componentClient, Notification.idFor(s"payout/$subject/${n.kind}/$at"), n, at)))
    effects.done()

object PayoutNotices extends Consumer.Companion[PayoutNotices, Payout.Event, Unit](
      componentId = ComponentId("payout-notices"),
      source = ChangeSource.eventsOf(Payout)
    ):
  def create(ctx: ConsumerContext) = new PayoutNotices(ctx)
