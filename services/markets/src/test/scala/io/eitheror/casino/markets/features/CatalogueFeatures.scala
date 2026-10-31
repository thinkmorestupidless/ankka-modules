package io.eitheror.casino.markets.features

import io.eitheror.casino.markets.Json.*

/**
 * features/predictions/catalogue.feature's catalogue scenario (US4, FR-005): markets made through the
 * operator's routes, read through the player's. A player's own orders and the prices are `exchange`'s.
 */
class CatalogueFeatures extends MarketsFeatures("catalogue.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a player sees each outcome's last price, best bid, best ask and depth of an open market" ->
      "services/exchange/…/features/CatalogueFeatures",
    "a player sees their own orders, positions and trades and no other player's" -> "services/exchange/…/features/CatalogueFeatures"
  )

  Given("a player {string} with an active account") { (_: String) => () }

  Given("an open market {string} with the outcomes {string} and {string} in {string}") { (m: String, a: String, b: String, c: String) =>
    ok(draft(m, "outcomes" -> Vector(a, b), "currency" -> c))
    ok(command(m, "approve"))
  }

  Given("a draft market {string} with the outcomes {string} and {string} in {string}") { (m: String, a: String, b: String, c: String) =>
    ok(draft(m, "outcomes" -> Vector(a, b), "currency" -> c))
  }

  Given("an approved market {string} that has not opened") { (m: String) =>
    ok(draft(m, "openTime" -> kit.now().plusSeconds(3600).toString))
    ok(command(m, "approve"))
  }

  private var seen = Vector.empty[com.fasterxml.jackson.databind.JsonNode]

  When("{string} reads the market catalogue") { (p: String) =>
    val r = kit.request("GET", kit.playerPath("/catalogue"), headers = kit.asPlayer(p))
    ok(r)
    seen = r.json.objects("markets")
  }

  Then("{string} sees market {string} with its outcomes") { (p: String, m: String) =>
    val market = kit.eventually(s"$m in the catalogue") {
      seen = kit.request("GET", kit.playerPath("/catalogue"), headers = kit.asPlayer(p)).json.objects("markets")
      seen.find(_.text("market").contains(id(m)))
    }
    assertEquals(market.strings("outcomes"), Vector("yes", "no"))
    assertEquals(market.text("state"), Some("open"))
    assertEquals(kit.request("GET", kit.playerPath(s"/catalogue/${id(m)}"), headers = kit.asPlayer(p)).status, 200)
  }

  Then("{string} does not see market {string} or market {string}") { (p: String, a: String, b: String) =>
    assert(!seen.exists(n => n.text("market").exists(Set(id(a), id(b)))), seen.toString)
    Seq(a, b).foreach(m => assertEquals(kit.request("GET", kit.playerPath(s"/catalogue/${id(m)}"), headers = kit.asPlayer(p)).status, 404))
  }
