package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.settlement.SettlementWorkflow

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/**
 * features/wallet/settlement.feature (spec 022 User Story 2): a trade settled across two wallets in
 * one keyed command, over HTTP against Postgres, including a restart between the two wallets.
 */
class SettlementFeaturesItSuite extends LedgerFeatures("settlement.feature"):

  private def settle(trade: String, amount: Long, order: String, buyer: String, seller: String, unused: Long = 0L): (Int, String) =
    send("POST", s"/v1/casino/brands/$brand/settlements",
      Some(s"""{"tradeId":"${p(trade)}","currency":"$currency","buyer":"${p(buyer)}","orderId":"${p(order)}","seller":"${p(seller)}",
              |"amount":$amount,"unused":$unused}""".stripMargin), as = exchange)

  private def settling(trade: String, amount: String, order: String, buyer: String, seller: String, unused: Long = 0L): Unit =
    acted = true
    last = settle(trade, amount.toLong, order, buyer, seller, unused)
    if first._1 == 0 then first = last

  override def afterEach(context: AfterEach): Unit =
    SettlementWorkflow.faults.set(_ => ())
    super.afterEach(context)

  When("trade {string} of {string} is settled from order {string} of {string} to {string}") {
    (t: String, a: String, o: String, b: String, s: String) => settling(t, a, o, b, s)
  }

  When("trade {string} of {string} is settled from order {string} of {string} to {string} again") {
    (t: String, a: String, o: String, b: String, s: String) => settling(t, a, o, b, s)
  }

  /** The last trade of the order: what the reservation holds beyond the trade's cost is released (FR-004). */
  When("trade {string} of {string} is settled from order {string} of {string} to {string} as the last trade of the order") {
    (t: String, a: String, o: String, b: String, s: String) =>
      val reserved = amountIn(balanceOf(p(b)), "reserved")
      settling(t, a, o, b, s, unused = reserved - a.toLong)
  }

  /**
   * The credit step is held where it stands, after the buyer's debit is written, and the service is
   * stopped under it: the flow's journal says the debit happened and the credit has not.
   */
  Given("trade {string} of {string} is settled from order {string} of {string} to {string} and the service stops after the debit") {
    (t: String, a: String, o: String, b: String, s: String) =>
      val reached = CountDownLatch(1)
      val release = CountDownLatch(1)
      SettlementWorkflow.faults.set { step =>
        if step == "credit" then
          reached.countDown()
          release.await(30, TimeUnit.SECONDS): Unit
          throw IllegalStateException("the service stopped under this step")
      }
      last = settle(t, a.toLong, o, b, s)
      assert(reached.await(10, TimeUnit.SECONDS), s"the credit step was never reached: $last")
      assertEquals(amountIn(balanceOf(p(b)), "reserved"), 650L - a.toLong, "the debit is written before the stop")
      assertEquals(amountIn(balanceOf(p(s)), "realBalance"), 0L, "the credit is not")
      testKit.stopService()
      SettlementWorkflow.faults.set(_ => ())
      release.countDown()
  }

  When("the service restarts") { () =>
    acted = true
    testKit.startService()
    val flow = testKit.componentClient.forWorkflow(SettlementWorkflow.id(currency, p("t1")))
    eventually(flow.lifecycle(SettlementWorkflow).invoke().status.toString, 30.seconds)(_ == "Completed")
  }

  Then("a settlement transaction of {string} is written on each wallet") { (a: String) =>
    val debit  = moneyFacts(s"${p("ada")}/$currency", "settlement_debit")
    val credit = moneyFacts(s"${p("bob")}/$currency", "settlement_credit")
    assert(debit.exists(_.contains(s""""amount_cents":$a""")), debit.mkString("\n"))
    assert(credit.exists(_.contains(s""""amount_cents":$a""")), credit.mkString("\n"))
    assert(debit.exists(_.contains(s""""trade_id":"${p("t1")}"""")), debit.mkString("\n"))
  }
