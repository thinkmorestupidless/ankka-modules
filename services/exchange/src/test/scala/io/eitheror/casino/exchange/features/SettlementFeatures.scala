package io.eitheror.casino.exchange.features

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.LedgerAnswer
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.ports.{WalletCall, WalletClient}
import io.eitheror.casino.exchange.retry.SettlementRetry

/**
 * features/predictions/settlement.feature (US2, FR-003): trades and finality as the exchange makes
 * them, the wallet's moves as the fake wallet keeps them. A trade's name in the feature is the trade
 * the scenario made; a refused settlement is sent through the ledger port as the library's consumer
 * sends it.
 */
class SettlementFeatures extends ExchangeFeatures("settlement.feature"):

  override protected def ranOutside: Map[String, String] = Map(
    "a taker's fee moves to the brand's fee account keyed by the trade, and the maker pays none" ->
      "the taker fee is ankka-predictions spec 007 (v0.2.0); this module builds on v0.1.0 with the rate 0 (research R8)"
  )

  /** The trades the scenario named, by the feature's name: the wallet's key for each. */
  private var trades = Map.empty[String, String]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    trades = Map.empty

  Then("trade {string} is settled through the wallet from order {string} of {string} to {string} for {string}") {
    (t: String, o: String, buyer: String, seller: String, amount: String) =>
      val (key, body) = kit.eventually(s"the settlement of $o") {
        kit.wallet.calls("settlements").find(_._2.get("orderId").asText == o)
      }
      assertEquals((body.get("buyer").asText, body.get("seller").asText, body.get("amount").asLong), (buyer, seller, amount.toLong))
      trades += t -> key
  }

  Then("the settlement of trade {string} is sent once however many times the exchange retries") { (t: String) =>
    val key  = trades(t)
    val body = kit.wallet.calls("settlements").find(_._1 == key).get._2
    val before = (kit.wallet.real.get("ada"), kit.wallet.real.get("bob"))
    val client = WalletClient(io.eitheror.casino.exchange.ExchangeRuntime.services, kit.slug)
    (1 to 3).foreach(_ => assertEquals(client.send(WalletCall(key, s"/v1/casino/brands/${kit.slug}/settlements", Json.render(body))), LedgerAnswer.Done))
    assertEquals((kit.wallet.real.get("ada"), kit.wallet.real.get("bob")), before)
    assertEquals(before, (400L, 1000L))
  }

  /** As a Given, the holdings asked for are made first; as a Then, it waits for the payments. */
  Given("the pool account of market {string} in {string} holds {string}") { (m: String, _: String, n: String) =>
    made()
    kit.eventually(s"the pool of $m at $n")(Option.when(kit.wallet.pools.getOrDefault(id(m), 0L) == n.toLong)(()))
  }

  When("market {string} is final with the outcome {string}") { (m: String, o: String) =>
    made()
    def drive(command: String, fields: (String, Any)*) =
      ok(kit.json("POST", kit.servicePath(s"/markets/${id(m)}/$command"), Json.obj((fields :+ ("at" -> kit.now().toString))*),
        kit.asService("markets"))): Unit
    drive("close")
    drive("finalise", "outcome" -> o)
  }

  Then("a resolution payment of {string} is paid to {string} from the pool account of market {string}") { (n: String, p: String, m: String) =>
    val paid = kit.eventually(s"the payment to $p")(kit.wallet.calls("payments").find(_._2.get("player").asText == p))
    assertEquals(paid._2.get("amount").asLong, n.toLong)
    assert(paid._1.startsWith(s"${id(m)}/"), paid._1)
  }

  Then("nothing is paid to {string}") { (p: String) =>
    Thread.sleep(500)
    assert(!kit.wallet.calls("payments").exists(_._2.get("player").asText == p), kit.wallet.calls("payments").toString)
  }

  Given("the wallet refuses the settlement of trade {string}") { (t: String) =>
    trades += t -> s"$t-$run"
    kit.wallet.script.put("settlements", List(404 -> """{"error":"WALLET_NOT_FOUND"}"""))
  }

  When("trade {string} is sent for settlement") { (t: String) =>
    val key = trades(t)
    kit.wallet.real.put("ada", 1000L); kit.wallet.real.put("bob", 0L)
    kit.wallet.reservations.put(s"o-$key", "ada" -> 600L)
    val legs = Vector(Leg(OwnerId("ada"), OrderId(s"o-$key"), OutcomeId("yes"), Side.Buy, 600, 0, 0, 0),
      Leg(OwnerId("bob"), OrderId(s"s-$key"), OutcomeId("yes"), Side.Sell, 0, 600, 0, 0))
    last = null
    assertEquals(kit.exchange.ledger.settle(MarketId(id("m1")), TradeId(key), Through.Direct, legs), LedgerAnswer.Refused("WALLET_NOT_FOUND"))
  }

  private def retry(t: String) = kit.components.forWorkflow(EntityId(trades(t))).call(SettlementRetry.get).invoke()

  Then("the refusal is recorded against trade {string}") { (t: String) =>
    assertEquals(retry(t).lastRefusal, Some("WALLET_NOT_FOUND"))
  }

  Then("the settlement is retried") { () =>
    val t = trades.keys.head
    kit.eventually("the retry's success")(Option.when(retry(t).status == SettlementRetry.Succeeded)(()))
    assertEquals(kit.wallet.calls("settlements").count(_._1 == trades(t)), 2)
    assertEquals(kit.wallet.real.get("bob"), 600L)
  }

  Then("a record naming trade {string} is written on the brand's platform audit topic") { (t: String) =>
    kit.eventually("the refusal's record")(kit.audit.find(r => r.subjectId == trades(t) && r.action == "exchange.settlement_refused")): Unit
  }

  Then("a fee of {string} moves from order {string} of {string} to the brand's fee account in {string}, keyed by trade {string}") {
    (_: String, _: String, _: String, _: String, _: String) => fail("the taker fee waits on ankka-predictions spec 007")
  }

  Then("{string} pays no fee") { (_: String) => fail("the taker fee waits on ankka-predictions spec 007") }
