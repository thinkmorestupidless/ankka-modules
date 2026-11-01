package io.eitheror.casino.markets.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.predictions.markets.{Market, MarketState}
import com.thinkmorestupidless.predictions.markets.components.{CatalogueRows, MarketsComponents}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.markets.{Json, MarketsRuntime}

import Answers.*

/**
 * `/v1/casino/markets/<slug>` (contracts/routes.md): the brand's players read the catalogue. A list
 * shows open markets only; a market by its id is shown once it has opened, and a draft, an approved
 * market or an unknown id is `404`, so nothing unpublished is seen (FR-005). Prices and depth are
 * `exchange`'s.
 */
final class PlayerEndpoint(components: MarketsComponents, clients: EndpointClients, authenticate: () => Acl)
    extends HttpEndpoint(s"/v1/casino/markets/${MarketsRuntime.settings.brand}"):
  val acl: Acl = Routes.player(MarketsRuntime.settings.brand, authenticate())

  private def rows = clients.viewClient.forView(CatalogueRows)

  get("/catalogue") { () =>
    val open = request.query.raw("category").filter(_.nonEmpty) match
      case Some(c) => rows.ask(CatalogueRows.byCategory, "category" -> c).filter(_.state == MarketState.Open)
      case None    => rows.ask(CatalogueRows.byState, "state" -> MarketState.word(MarketState.Open))
    ok(Json.obj("markets" -> Json.arr(open.map(PlayerEndpoint.render)*)))
  }

  get("/catalogue/{market}") { (id: String) =>
    val m = clients.componentClient.forEventSourcedEntity(EntityId(id)).call(components.market.get).invoke()
    if PlayerEndpoint.Shown(m.state) then ok(PlayerEndpoint.render(m)) else notFound
  }

object PlayerEndpoint:
  /** The states a player may see a market in (contracts/routes.md). */
  val Shown: Set[MarketState] = Set(MarketState.Open, MarketState.Suspended, MarketState.Closed, MarketState.Resolved)

  def render(m: Market): JsonNode =
    val d = m.definition
    Json.obj("market" -> m.market.value, "state" -> MarketState.word(m.state), "question" -> d.map(_.question),
      "description" -> d.map(_.description), "category" -> d.map(_.category), "outcomes" -> d.toVector.flatMap(_.outcomes.map(_.value)),
      "currency" -> d.map(_.currency), "close" -> d.map(_.close.toString), "expectedResolution" -> d.map(_.expectedResolution.toString),
      "criteria" -> d.map(_.criteria), "authority" -> d.map(_.authority))
