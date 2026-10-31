package io.eitheror.casino.exchange.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.predictions.exchange.{OutcomeId, OwnerId}

import scala.jdk.CollectionConverters.*

/**
 * features/predictions/catalogue.feature's prices and the player's own reads (US4, FR-005), through
 * the player's routes. The catalogue itself is `markets`'.
 */
class CatalogueFeatures extends ExchangeFeatures("catalogue.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a player sees open markets and no draft or unopened market" -> "services/markets/…/features/CatalogueFeatures"
  )

  private var read: Map[String, JsonNode] = Map.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    read = Map.empty

  Given("a player {string} with an active account") { (p: String) => kit.wallet.real.put(p, 10000L): Unit }

  /** A split with a partner of their own makes the position and the trade; a buy below the book rests. */
  Given("{string} has an order, a position and a trade in market {string}") { (p: String, m: String) =>
    val partner = s"partner-$p-$run"
    kit.wallet.real.put(partner, 10000L)
    ok(kit.place(p, id(m), s"$p-$run-1", "buy", "yes", 5, 60))
    ok(kit.place(partner, id(m), s"$partner-1", "buy", "no", 5, 40))
    kit.eventually(s"$p holds 5")(Option.when(kit.book(id(m)).position(OwnerId(p), OutcomeId("yes")).quantity == 5)(()))
    ok(kit.place(p, id(m), s"$p-$run-2", "buy", "yes", 2, 30)): Unit
  }

  When("{string} reads the prices and depth of market {string}") { (p: String, m: String) =>
    read += "prices" -> ok(kit.request("GET", kit.playerPath(s"/markets/${id(m)}"), headers = kit.asPlayer(p))).json
  }

  Then("{string} sees the last price, best bid, best ask and depth of each outcome of market {string}") { (_: String, m: String) =>
    val p = read("prices")
    assertEquals(p.get("market").asText, id(m))
    val outcomes = p.get("outcomes").elements.asScala.map(o => o.get("outcome").asText -> o).toMap
    assertEquals(outcomes.keySet, Set("yes", "no"))
    val yes = outcomes("yes")
    assertEquals((yes.get("lastPrice").asLong, yes.get("bestBid").asLong), (60L, 30L))
    assert(Option(yes.get("bestAsk")).forall(_.isNull), yes.toString)
    assertEquals(yes.get("buys").get(0).get("quantity").asLong, 2L)
    assertEquals(outcomes("no").get("lastPrice").asLong, 40L)
  }

  When("{string} reads her orders, positions and trades") { (p: String) =>
    def get(what: String) = ok(kit.request("GET", kit.playerPath(s"/players/$p?read=$what"), headers = kit.asPlayer(p))).json
    kit.eventually(s"$p's two orders and one trade") {
      Option.when(mine(get("orders"), "orders").size == 2 && mine(get("trades"), "trades").nonEmpty)(())
    }
    Seq("orders", "positions", "trades").foreach(w => read += w -> get(w))
    // Another player's reads are not hers to make.
    assertEquals(kit.request("GET", kit.playerPath(s"/players/bob?read=orders"), headers = kit.asPlayer(p)).status, 403)
  }

  private def mine(n: JsonNode, rows: String) = n.get(rows).elements.asScala.filter(_.get("market").asText == id("m1")).toVector

  Then("{string} sees her own and nothing of {string}") { (p: String, other: String) =>
    val orders = mine(read("orders"), "orders")
    assertEquals(orders.map(_.get("order").asText).sorted, Vector(s"$p-$run-1", s"$p-$run-2"))
    assertEquals(mine(read("positions"), "positions").map(r => r.get("outcome").asText -> r.get("quantity").asLong), Vector("yes" -> 5L))
    assertEquals(mine(read("trades"), "trades").map(_.get("order").asText), Vector(s"$p-$run-1"))
    val everything = read.values.mkString
    assert(!everything.contains(other), everything)
  }
