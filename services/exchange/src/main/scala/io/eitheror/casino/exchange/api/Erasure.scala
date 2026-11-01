package io.eitheror.casino.exchange.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.ExchangeComponents
import io.eitheror.casino.exchange.erasure.{Erased, ErasedEntity}
import io.eitheror.casino.exchange.reads.{OrderRow, OrderRows}

import java.time.Instant

object Erasure:
  /**
   * Cancels every order of `player` still resting, which releases a buy's reservation, then marks the
   * player. The markets are those of the player's orders in `orders-by-owner`; which of them rest is
   * each book's own to say, so a view behind its book cancels nothing short. Positions stand until their
   * market is final (FR-008).
   */
  def erase(components: ExchangeComponents, client: ComponentClient, views: ViewClient, player: String, now: () => Instant): Erased =
    val marked = client.forKeyValueEntity(EntityId(player)).call(ErasedEntity.get).invoke()
    if marked.at.nonEmpty then marked
    else
      val markets = views.forView(OrderRows).ask(OrderRows.byOwner, "owner" -> player).collect { case o: OrderRow.Order => o.market }.distinct
      val owner   = OwnerId(player)
      val (cancelled, kept) = markets.foldLeft((Vector.empty[String], 0)) { case ((done, positions), m) =>
        val book    = client.forEventSourcedEntity(EntityId(m))
        val ex      = book.call(components.exchange.get).invoke()
        val resting = ex.orders.values.filter(o => o.owner == owner && Set(OrderState.Resting, OrderState.FilledInPart)(o.state)).toVector
        resting.foreach(o => book.call(components.exchange.cancel).invoke(ExchangeCommand.Cancel(o.id, owner, now())))
        (done ++ resting.map(o => s"$m/${o.id.value}"), positions + ex.positions.get(owner).fold(0)(_.count(_._2.quantity > 0)))
      }
      client.forKeyValueEntity(EntityId(player)).call(ErasedEntity.mark).invoke(Erased(Some(now()), cancelled.sorted, kept))
