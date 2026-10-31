package io.eitheror.casino.exchange.features

import com.thinkmorestupidless.predictions.exchange.{OutcomeId, OwnerId}
import io.eitheror.casino.contract.facts as f

/**
 * features/predictions/facts.feature (US5, FR-006, FR-007): the facts as the in-memory broker holds
 * them, keyed by the market. The kit's brand names no reasoning service, so nothing is mirrored; the
 * mirroring scenario waits on ankka-reasoning 0.2.0 (R14), its writes and its throttle covered by
 * `MarketMirrorSuite` and `PriceObservationsSuite`.
 */
class FactsFeatures extends ExchangeFeatures("facts.feature"):

  override protected def ranOutside: Map[String, String] = Map(
    "a brand with a reasoning service named mirrors each approved market and writes price observations at most once a minute per outcome" ->
      "the reasoning mirror waits on ankka-reasoning 0.2.0 (research R14)"
  )

  private def ofMarket[F <: f.Fact](topic: f.Topic[F], m: String) = kit.facts(topic).filter(_._1.contains(id(m)))

  Then("a fact for order {string} is written on the brand's exchange orders topic, keyed by the market") { (o: String) =>
    kit.eventually(s"the fact for $o") {
      kit.facts(f.ExchangeOrders).collectFirst { case (Some(k), x: f.OrderPlaced) if x.orderId == o => (k, x) }
    } match
      case (k, x) =>
        assertEquals(k, x.marketId)
        assertEquals((x.playerId, x.side, x.price, x.quantity, x.costCents.value), ("ada", "buy", 60L, 10L, 600L))
  }

  /** A partner's complementary buy matches the whole order as a split. */
  When("order {string} is matched by a trade {string}") { (o: String, _: String) =>
    kit.wallet.real.put(s"partner-$run", 10000L)
    ok(kit.place(s"partner-$run", id("m1"), s"partner-$run-1", "buy", "no", 10, 40))
    kit.eventually(s"$o matched")(Option.when(kit.book(id("m1")).position(OwnerId("ada"), OutcomeId("yes")).quantity == 10)(()))
  }

  Then("a fact for trade {string} is written on the brand's exchange trades topic, keyed by the market") { (_: String) =>
    val made = kit.eventually("the trade's fact")(ofMarket(f.ExchangeTrades, "m1").collectFirst { case (_, t: f.TradeMade) => t })
    assertEquals(made.through, "split")
    assertEquals(made.legs.map(l => (l.playerId, l.outcomeId, l.chargeCents.value)).toSet, Set(("ada", "yes", 600L), (s"partner-$run", "no", 400L)))
  }

  Then("a fact for the new last price of {string} is written on the brand's exchange prices topic, keyed by the market") { (o: String) =>
    val p = kit.eventually("the prices' fact")(ofMarket(f.ExchangePrices, "m1").collectFirst { case (_, p: f.PricesChanged) => p })
    assertEquals(p.prices.find(_.outcomeId == o).map(_.price), Some(60L))
  }

  Given("the brand's values name no reasoning service") { () => assertEquals(kit.base.reasoningService, None) }

  /** The approval reaches the exchange as `markets` opening the book. */
  When("an operator approves a market {string} with the outcomes {string} and {string}") { (m: String, a: String, b: String) =>
    ok(kit.open(id(m), Vector(a, b), "EUR")): Unit
  }

  Then("nothing is mirrored") { () =>
    assert(!kit.exchange.descriptors.exists(_.componentId.toString == "exchange-price-observations"))
  }
