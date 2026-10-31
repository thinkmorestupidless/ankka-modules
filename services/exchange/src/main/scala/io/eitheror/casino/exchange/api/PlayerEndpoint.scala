package io.eitheror.casino.exchange.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.{Depth, ExchangeComponents, PlaceOrder, PlacementOutcome, PlacementState, PlacementWorkflow, PositionRow, PositionRows, Prices}
import io.eitheror.casino.exchange.reads.{OrderRow, OrderRows, TradeRow, TradeRows}
import io.eitheror.casino.exchange.terms.{KnownTerms, MarketTerms}
import org.apache.pekko.stream.scaladsl.Source
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.exchange.{ExchangeRuntime, Json}
import io.eitheror.casino.exchange.Json.*

import scala.concurrent.duration.*

import Answers.*

/**
 * `/v1/casino/exchange/<slug>` (contracts/routes.md): a player's orders, for themselves only. A
 * placement is the library's workflow, started under `<market>/<order>` as the claim and polled for
 * its outcome, as game-provider's endpoints wait on a bet's; a repeat of the same order is answered
 * as the first was. A book's prices and depth are the entity's own, current to its sequence; a
 * player's orders, positions and trades are views, each market's answer saying the sequence the view
 * has seen and whether it is behind the book (FR-005, R10).
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

  private def book(market: String) = clients.componentClient.forEventSourcedEntity(EntityId(market))

  get("/markets/{market}") { (market: String) =>
    request.query.raw("outcome").filter(_.nonEmpty) match
      case Some(o) => ok(PlayerEndpoint.depth(book(market).call(components.exchange.depth).invoke(OutcomeId(o))))
      case None    => ok(prices(market))
  }

  /** The book's prices and each outcome's depth, sent whenever its sequence moves; a client that sees a gap reads again. */
  sse("/stream/{market}") { (market: String) =>
    Source.tick(Duration.Zero, PlayerEndpoint.StreamEvery, ())
      .map(_ => prices(market))
      .statefulMap(() => -1L)((last, p) => {
        val seq = p.get("sequence").asLong
        (seq, Option.when(seq != last)(Json.render(p)))
      }, _ => None)
      .collect { case Some(text) => text }
  }

  get("/players/{player}") { (player: String) =>
    if !me.contains(player) then PlayerEndpoint.forbidden
    else
      val orders = clients.viewClient.forView(OrderRows).ask(OrderRows.byOwner, "owner" -> player).collect { case o: OrderRow.Order => o }
      request.query.raw("read").getOrElse("orders") match
        case "orders" =>
          ok(Json.obj("orders" -> Json.arr(orders.map(PlayerEndpoint.order)*), "markets" -> seen(orders.map(_.market))))
        case "trades" =>
          val legs = clients.viewClient.forView(TradeRows).ask(TradeRows.byOwner, "owner" -> player).collect { case l: TradeRow.Leg => l }
          ok(Json.obj("trades" -> Json.arr(legs.map(PlayerEndpoint.trade)*), "markets" -> seen(legs.map(_.market))))
        case "positions" =>
          val held = clients.viewClient.forView(PositionRows).ask(PositionRows.byOwner, "owner" -> player)
            .collect { case h: PositionRow.Held => h }
          val underSells = orders.filter(o => o.side == "sell" && Set("resting", "filled-in-part")(o.state))
            .groupMapReduce(o => (o.market, o.outcome))(o => o.quantity - o.filled)(_ + _)
          // The view's quantity is every share held, those resting under a sell among them (the
          // library's Position); how many rest is the orders' to say.
          val rows = held.map { h =>
            Json.obj("market" -> h.market.value, "outcome" -> h.outcome.value, "quantity" -> h.quantity,
              "restingUnderSells" -> underSells.getOrElse((h.market.value, h.outcome.value), 0L), "cost" -> h.cost)
          }
          ok(Json.obj("positions" -> Json.arr(rows*), "markets" -> seen(held.map(_.market.value))))
        case other => refusal(400, "field", s"read is orders, positions or trades, not $other", "field" -> "read")
  }

  /** Every outcome's last price, best bid, best ask and depth, current to the book's sequence. */
  private def prices(market: String) =
    val p      = book(market).call(components.exchange.prices).invoke()
    val terms  = clients.componentClient.forKeyValueEntity(EntityId(market)).call(io.eitheror.casino.exchange.terms.MarketTerms.get).invoke()
    val depths = terms.outcomes.map(o => book(market).call(components.exchange.depth).invoke(OutcomeId(o)))
    PlayerEndpoint.prices(market, p, depths)

  /**
   * For each market the rows came from, the sequence the orders view has seen and whether the book is
   * ahead of it. The library's positions view keeps no sequence; it reads the same journal, so the
   * orders view's stands for it.
   */
  private def seen(markets: Vector[String]) =
    Json.arr(markets.distinct.sorted.map { m =>
      val view = clients.viewClient.forView(OrderRows).ask(OrderRows.seen, "market" -> m).collectFirst { case s: OrderRow.Seen => s.sequence }
        .getOrElse(0L)
      PlayerEndpoint.seen(m, view, book(m).call(components.exchange.prices).invoke().sequence)
    }*)

  /**
   * The placement started and waited on to its end, which holds the outcome; a wait past `Wait` answers
   * pending. A book with no terms has not been opened by `markets` yet (its catalogue may already show
   * the market open), and is refused as the library refuses a book that is not open; terms are never
   * taken away, so the gate that reads them finds them.
   */
  private def placed(request: PlaceOrder): Reply =
    val flow = clients.componentClient.forWorkflow(PlacementWorkflow.id(request))
    val terms = KnownTerms.of(request.market.value)(clients.componentClient.forKeyValueEntity(EntityId(request.market.value)).call(MarketTerms.get).invoke())
    if !terms.known then refusal(409, "state", "state: state empty", "state" -> "empty")
    else try
      answer(flow.call(components.placement.place).thenAwaitEnd[PlacementState](PlayerEndpoint.Wait).invoke(request)) { end =>
        end.outcome match
          case PlacementOutcome.Accepted(events) =>
            ok(Json.obj("order" -> request.order.order.value, "outcome" -> "accepted", "events" -> events.size))
          case PlacementOutcome.Refused(r) => refused(r)
          case PlacementOutcome.Running    => pending(request)
      }
    catch
      case e: CommandError if e.code == ErrorCode.Timeout => pending(request)
      // The placement runs under the market and the order: another order under its id is the id reused.
      case e: CommandError if e.code == ErrorCode.Conflict => refused(Refusals.id(request.order.order))

  private def pending(request: PlaceOrder) =
    refusal(503, "pending", "The order is still being placed; ask again with the same order", "order" -> request.order.order.value)

object PlayerEndpoint:
  val StreamEvery: FiniteDuration = 250.millis

  private def levels(ls: Vector[Level]) = Json.arr(ls.map(l => Json.obj("price" -> l.price, "quantity" -> l.quantity))*)

  def depth(d: Depth) =
    Json.obj("outcome" -> d.outcome.value, "bestBid" -> d.buys.headOption.map(_.price), "bestAsk" -> d.sells.headOption.map(_.price),
      "buys" -> levels(d.buys), "sells" -> levels(d.sells), "sequence" -> d.sequence)

  def prices(market: String, p: Prices, depths: Vector[Depth]) =
    Json.obj("market" -> market, "sequence" -> p.sequence, "outcomes" -> Json.arr(depths.map { d =>
      val o = depth(d)
      Json.set(o, "lastPrice", p.prices.get(d.outcome))
      o
    }*))

  def seen(market: String, view: Long, book: Long) = Json.obj("market" -> market, "sequence" -> view, "stale" -> (view < book))

  def order(o: OrderRow.Order) =
    Json.obj("market" -> o.market, "order" -> o.order, "outcome" -> o.outcome, "side" -> o.side, "price" -> o.price,
      "quantity" -> o.quantity, "filled" -> o.filled, "state" -> o.state, "at" -> o.at.toString)

  def trade(l: TradeRow.Leg) =
    Json.obj("market" -> l.market, "trade" -> l.trade, "order" -> l.order, "outcome" -> l.outcome, "side" -> l.side, "price" -> l.price,
      "quantity" -> l.quantity, "through" -> l.through, "at" -> l.at.toString)

  /** Longer than the ledger's patience (5 s), so an order that will be answered is. */
  val Wait: FiniteDuration = 15.seconds

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
