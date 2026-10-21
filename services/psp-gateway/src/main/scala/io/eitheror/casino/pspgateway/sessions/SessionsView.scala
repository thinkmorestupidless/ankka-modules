package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonNumber, jsonText, sql}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.pspgateway.GatewayCodecs

import java.time.Instant

/**
 * A session as the operators' listings read it (data-model.md, FR-016): what it is, who receives its
 * outcome, and how its delivery stands. No amount, no customer, no provider data: nothing personal
 * and nothing secret (FR-022). `since` is when the delivery came to stand as it does, in epoch
 * milliseconds, so the listings order oldest first.
 */
final case class SessionRow(
    reference: String,
    kind: String,
    provider: String,
    receiver: String,
    outcome: Option[String] = None,
    delivery: String = "NotYet",
    attempts: Int = 0,
    lastAnswer: Option[String] = None,
    since: Long = 0
)

object SessionRow:
  val Deposit = "deposit"
  val Payout  = "payout"

  def delivered(r: SessionRow, d: Delivery, n: Int, at: Instant): SessionRow =
    val answer = d match
      case Delivery.Refused(_, status, text)     => Some(s"$status $text".trim)
      case Delivery.Undelivered(_, _, lastAnswer) => Some(lastAnswer)
      case _                                     => None
    r.copy(delivery = d.word, attempts = r.attempts + n, lastAnswer = answer, since = at.toEpochMilli)

/**
 * The deposit sessions as rows. Two plain views, one per session kind, in place of one keyed view over
 * both: each row is its own session's, so a plain view does it, and a keyed view would take one change at
 * a time across both (ankka `views.md`, "use a plain view wherever one will do").
 */
final class DepositRowsView extends View[DepositEvent, SessionRow]:
  def onChange(event: DepositEvent): Effect =
    val id = updateContext.subject
    (event, rowState) match
      case (DepositEvent.Claimed(c, at), None) =>
        effects.updateRow(SessionRow(id, SessionRow.Deposit, c.provider.wire, Receiver.Merchant(id).describe, since = at.toEpochMilli))
      case (DepositEvent.Settled(o, _, at), Some(r)) =>
        effects.updateRow(r.copy(outcome = Some(o.word), delivery = Delivery.NotYet.word, since = at.toEpochMilli))
      case (DepositEvent.DeliveryRecorded(d, n, at), Some(r)) => effects.updateRow(SessionRow.delivered(r, d, n, at))
      case _                                                  => effects.ignore()

object DepositRows extends View.Companion[DepositRowsView, DepositEvent, SessionRow](
      componentId = ComponentId("deposit-rows"),
      source = ChangeSource.eventsOf(DepositSession),
      rowSerializer = GatewayCodecs.serializer[SessionRow]("session-row")
    ):
  def create(ctx: ViewComponentContext) = new DepositRowsView

/** The payout sessions as rows, as `DepositRows` does for deposits. */
final class PayoutRowsView extends View[PayoutEvent, SessionRow]:
  def onChange(event: PayoutEvent): Effect =
    val id = updateContext.subject
    (event, rowState) match
      case (PayoutEvent.Claimed(c, at), None) =>
        effects.updateRow(SessionRow(id, SessionRow.Payout, c.provider.wire, Receiver.Payouts(c.brand, id).describe,
          since = at.toEpochMilli))
      case (PayoutEvent.Settled(o, _, _, at), Some(r)) =>
        effects.updateRow(r.copy(outcome = Some(o.word), delivery = Delivery.NotYet.word, since = at.toEpochMilli))
      case (PayoutEvent.DeliveryRecorded(d, n, at), Some(r)) => effects.updateRow(SessionRow.delivered(r, d, n, at))
      case _                                                 => effects.ignore()

object PayoutRows extends View.Companion[PayoutRowsView, PayoutEvent, SessionRow](
      componentId = ComponentId("payout-rows"),
      source = ChangeSource.eventsOf(PayoutSession),
      rowSerializer = GatewayCodecs.serializer[SessionRow]("session-row")
    ):
  def create(ctx: ViewComponentContext) = new PayoutRowsView

/** The listings over both kinds: the oldest first, at most `limit` of them. */
object SessionsView:
  def undelivered(views: ViewClient, limit: Int): Vector[SessionRow] = inState(views, "Undelivered", limit)
  def refused(views: ViewClient, limit: Int): Vector[SessionRow]     = inState(views, "Refused", limit)

  def count(views: ViewClient, state: String): Long =
    views.forView(DepositRows).count(condition(state)) + views.forView(PayoutRows).count(condition(state))

  private def condition(state: String) = jsonText("delivery") ++ sql" = $state"

  private def inState(views: ViewClient, state: String, limit: Int): Vector[SessionRow] =
    val order = jsonNumber("since") ++ SqlFragment.raw(" ASC")
    val both  = views.forView(DepositRows).ordered(condition(state), order, limit) ++
      views.forView(PayoutRows).ordered(condition(state), order, limit)
    both.toVector.sortBy(_.since).take(limit)
