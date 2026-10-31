package io.eitheror.casino.payouts.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.PayoutsRuntime
import io.eitheror.casino.payouts.limits.Counted
import io.eitheror.casino.payouts.player.{CountRequest, PlayerPayouts, Uncount}
import io.eitheror.casino.payouts.request.{RequestEvent, WithdrawalRequest}
import io.eitheror.casino.payouts.request.RequestEvent.*

/**
 * A request's life into the limits the player's record counts (FR-005a): an opened request counts; one
 * cancelled, rejected, failed or reversed stops counting. The record answers a repeat with nothing, so a
 * redelivery changes nothing. The events after `Opened` do not carry the player, so it is read from the request.
 */
final class RequestsConsumer(client: ComponentClient) extends Consumer[RequestEvent, Nothing]:
  def onMessage(event: RequestEvent): Effect =
    val requestId = messageContext.subject
    RequestsConsumer.change(event, requestId) match
      case Some(Left((playerId, c))) =>
        client.forEventSourcedEntity(EntityId(playerId)).call(PlayerPayouts.countRequest).invoke(c): Unit
        effects.done()
      case Some(Right(c)) =>
        val playerId = client.forEventSourcedEntity(EntityId(requestId)).call(WithdrawalRequest.get).invoke().playerId
        client.forEventSourcedEntity(EntityId(playerId)).call(PlayerPayouts.uncount).invoke(c): Unit
        effects.done()
      case None => effects.ignore()

object RequestsConsumer extends Consumer.Companion[RequestsConsumer, RequestEvent, Nothing](
      ComponentId("payouts-requests-consumer"), ChangeSource.eventsOf(WithdrawalRequest)):
  def create(ctx: ConsumerContext) = new RequestsConsumer(ctx.componentClient)

  /** What an event changes in the count: a request counted for its player, or one uncounted; nothing for the others. */
  def change(event: RequestEvent, requestId: String): Option[Either[(String, CountRequest), Uncount]] = event match
    case o: Opened =>
      Some(Left(o.playerId -> CountRequest(Counted(o.requestId, o.amount, Currency.unsafe(o.currency), o.at), PayoutsRuntime.now())))
    case Cancelled(_, _, at) => Some(Right(Uncount(requestId, at)))
    case Rejected(_, _, at)  => Some(Right(Uncount(requestId, at)))
    case f: Failed           => Some(Right(Uncount(requestId, f.at)))
    case Reversed(_, _, at)  => Some(Right(Uncount(requestId, at)))
    case _                   => None
