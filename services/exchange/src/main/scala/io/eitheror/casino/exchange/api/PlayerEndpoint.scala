package io.eitheror.casino.exchange.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.{ExchangeComponents, PlaceOrder, PlacementOutcome, PlacementWorkflow}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.exchange.{ExchangeRuntime, Json}
import io.eitheror.casino.exchange.Json.*

import scala.annotation.tailrec

import Answers.*

/**
 * `/v1/casino/exchange/<slug>` (contracts/routes.md): a player's orders, for themselves only. A
 * placement is the library's workflow, started under `<market>/<order>` as the claim and polled for
 * its outcome, as game-provider's endpoints wait on a bet's; a repeat of the same order is answered
 * as the first was.
 */
final class PlayerEndpoint(components: ExchangeComponents, clients: EndpointClients, authenticate: () => Acl)
    extends HttpEndpoint(s"/v1/casino/exchange/${ExchangeRuntime.settings.brand}"):
  val acl: Acl = Routes.player(ExchangeRuntime.settings.brand, authenticate())

  private def me: Option[String] = Routes.playerId(principal)

  postBody("/orders/{market}") { (market: String, text: String) =>
    me.fold(PlayerEndpoint.forbidden) { player =>
      Json.parseObject(text).fold(badBody)(b =>
        PlayerEndpoint.place(b, player).fold(field, order => placed(PlaceOrder(MarketId(market), order))))
    }
  }

  postBody("/orders/{market}/{order}") { (market: String, order: String, text: String) =>
    me.fold(PlayerEndpoint.forbidden) { player =>
      Json.parseObject(text).fold(badBody) { b =>
        if !b.bool("cancel").contains(true) then field("cancel")
        else
          answer(clients.componentClient.forEventSourcedEntity(EntityId(market)).call(components.exchange.cancel)
            .invoke(ExchangeCommand.Cancel(OrderId(order), OwnerId(player), ExchangeRuntime.now())))(events =>
            ok(Json.obj("order" -> order, "cancelled" -> true, "events" -> events.size)))
      }
    }
  }

  private def placed(request: PlaceOrder): Reply =
    val flow = clients.componentClient.forWorkflow(PlacementWorkflow.id(request))
    val deadline = System.nanoTime() + PlayerEndpoint.Wait.toNanos
    @tailrec def poll(o: PlacementOutcome): PlacementOutcome =
      if o != PlacementOutcome.Running || System.nanoTime() > deadline then o
      else
        Thread.sleep(10)
        poll(flow.call(components.placement.outcome).invoke())
    answer(flow.call(components.placement.place).invoke(request)) { first =>
      poll(first) match
        case PlacementOutcome.Accepted(events) =>
          ok(Json.obj("order" -> request.order.order.value, "outcome" -> "accepted", "events" -> events.size))
        case PlacementOutcome.Refused(r) => refused(r)
        case PlacementOutcome.Running =>
          refusal(503, "pending", "The order is still being placed; ask again with the same order", "order" -> request.order.order.value)
    }

object PlayerEndpoint:
  /** Longer than the ledger's patience (5 s), so an order that will be answered is. */
  val Wait: java.time.Duration = java.time.Duration.ofSeconds(15)

  val forbidden: Reply = refusal(403, "forbidden", "A player acts for themselves only")

  private val sides     = Side.values.map(s => Side.word(s) -> s).toMap
  private val durations = OrderDuration.values.map(d => OrderDuration.word(d) -> d).toMap

  /** The order a body asks for, or the field that cannot be read; what each must be is the library's to refuse. */
  def place(b: JsonNode, player: String): Either[String, ExchangeCommand.Place] =
    for
      order    <- b.text("order").toRight("order")
      outcome  <- b.text("outcome").toRight("outcome")
      side     <- b.text("side").flatMap(sides.get).toRight("side")
      price    <- b.long("price").toRight("price")
      quantity <- b.long("quantity").toRight("quantity")
      duration <- b.text("duration").fold(Right(OrderDuration.RestUntilClose))(d => durations.get(d).toRight("duration"))
    yield ExchangeCommand.Place(OrderId(order), OwnerId(player), OutcomeId(outcome), side, price, quantity, duration, ExchangeRuntime.now())
