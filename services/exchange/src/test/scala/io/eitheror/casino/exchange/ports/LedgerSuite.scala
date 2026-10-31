package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.{LedgerAnswer, Money}

/** The ledger port's routes and keys (research R4), and every wallet answer mapped to the port's. */
class LedgerSuite extends munit.FunSuite:
  private val brand = "goldvibe"
  private val m     = MarketId("m1")
  private val ada   = OwnerId("ada")

  /** The calls handed to the retry, with the answer that handed them. */
  private val retried = java.util.concurrent.ConcurrentLinkedQueue[(WalletCall, LedgerAnswer)]()

  override def beforeEach(context: BeforeEach): Unit = retried.clear()

  private def ledger(answer: (String, String) => Option[(Int, String)] = (_, _) => Some(200 -> """{"txnId":"t"}""")) =
    val s = Scripted(answer)
    (Ledger(() => WalletClient(s, brand), _ => "EUR", (c, a) => retried.add(c -> a): Unit), s)

  private def leg(owner: String, order: String, side: Side, charge: Long = 0, paid: Long = 0, released: Long = 0) =
    Leg(OwnerId(owner), OrderId(order), OutcomeId("yes"), side, charge, paid, released, if released > 0 then 1 else 0)

  test("a reservation is keyed by the market and the order, in the market's currency") {
    val (l, s) = ledger()
    assertEquals(l.reserve(m, OrderId("o1"), Money(600, ada)), LedgerAnswer.Done)
    val (svc, method, path, body) = s.calls.head
    assertEquals((svc, method, path), ("wallet", "POST", s"/v1/casino/brands/$brand/wallets/ada/EUR/reservations"))
    assert(body.contains("\"key\":\"m1/o1\"") && body.contains("\"amount\":600"), body)
  }

  test("a release names the order and its ordinal; nothing to release makes no call") {
    val (l, s) = ledger()
    assertEquals(l.release(m, OrderId("o1"), 2, Money(100, ada)), LedgerAnswer.Done)
    assert(s.calls.head._3.endsWith("/wallets/ada/EUR/reservations/release"))
    assert(s.calls.head._4.contains("\"orderId\":\"m1/o1\"") && s.calls.head._4.contains("\"sequence\":2"), s.calls.head._4)
    assertEquals(l.release(m, OrderId("o1"), 3, Money(0, ada)), LedgerAnswer.Done)
    assertEquals(s.calls.size, 1)
  }

  test("every wallet answer maps to the port's: done, insufficient, refused, or a throw to retry") {
    def with1(status: Int, code: String) = ledger((_, _) => Some(status -> s"""{"error":"$code"}"""))._1.reserve(m, OrderId("o1"), Money(1, ada))
    assertEquals(with1(409, "INSUFFICIENT_FUNDS"), LedgerAnswer.Insufficient)
    assertEquals(with1(409, "KEY_REUSED"), LedgerAnswer.Refused("KEY_REUSED"))
    assertEquals(with1(404, "WALLET_NOT_FOUND"), LedgerAnswer.Refused("WALLET_NOT_FOUND"))
    intercept[WalletPending](with1(503, "OUTCOME_PENDING"))
    intercept[WalletPending](with1(500, "BOOM"))
    intercept[WalletPending](ledger((_, _) => None)._1.reserve(m, OrderId("o1"), Money(1, ada)))
  }

  test("an explicit split or merge is not offered") {
    val (l, s) = ledger()
    assertEquals(l.charge(m, SetKey("k"), Money(100, ada)), LedgerAnswer.Refused("not offered"))
    assertEquals(l.pay(m, SetKey("k"), Money(100, ada)), LedgerAnswer.Refused("not offered"))
    assert(s.calls.isEmpty)
  }

  test("a direct trade is one settlement: the buyer's charge to the seller, what it frees unused") {
    val (l, s) = ledger()
    val legs = Vector(leg("ada", "o1", Side.Buy, charge = 500, released = 100), leg("bob", "o2", Side.Sell, paid = 500))
    assertEquals(l.settle(m, TradeId("t1"), Through.Direct, legs), LedgerAnswer.Done)
    assertEquals(s.calls.map(_._3), Vector(s"/v1/casino/brands/$brand/settlements"))
    val body = s.calls.head._4
    Seq("\"tradeId\":\"m1/t1\"", "\"buyer\":\"ada\"", "\"orderId\":\"m1/o1\"", "\"seller\":\"bob\"", "\"amount\":500", "\"unused\":100", "\"currency\":\"EUR\"")
      .foreach(f => assert(body.contains(f), s"$f in $body"))
  }

  test("a split trade charges each buy leg from its reservation into the pool, keyed by the trade and the owner") {
    val (l, s) = ledger()
    val legs = Vector(leg("ada", "o1", Side.Buy, charge = 600), leg("bob", "o2", Side.Buy, charge = 400, released = 50))
    assertEquals(l.settle(m, TradeId("t2"), Through.Split, legs), LedgerAnswer.Done)
    assertEquals(s.calls.map(_._3).distinct, Vector(s"/v1/casino/brands/$brand/accounts/pool/m1/splits"))
    assert(s.calls(0)._4.contains("\"key\":\"m1/t2/ada\"") && s.calls(0)._4.contains("\"orderId\":\"m1/o1\""), s.calls(0)._4)
    assert(s.calls(1)._4.contains("\"key\":\"m1/t2/bob\"") && s.calls(1)._4.contains("\"unused\":50"), s.calls(1)._4)
  }

  test("a merge trade pays each sell leg from the pool") {
    val (l, s) = ledger()
    val legs = Vector(leg("ada", "o1", Side.Sell, paid = 600), leg("bob", "o2", Side.Sell, paid = 400))
    assertEquals(l.settle(m, TradeId("t3"), Through.Merge, legs), LedgerAnswer.Done)
    assertEquals(s.calls.map(_._3).distinct, Vector(s"/v1/casino/brands/$brand/accounts/pool/m1/merges"))
    assert(s.calls(1)._4.contains("\"amount\":400") && s.calls(1)._4.contains("\"key\":\"m1/t3/bob\""), s.calls(1)._4)
  }

  test("a leg the wallet refuses stops the trade at it, is handed to the retry, and is answered as refused") {
    val (l, s) = ledger((_, _) => Some(409 -> """{"error":"RESERVATION_SHORT"}"""))
    val legs = Vector(leg("ada", "o1", Side.Buy, charge = 600), leg("bob", "o2", Side.Buy, charge = 400))
    assertEquals(l.settle(m, TradeId("t4"), Through.Split, legs), LedgerAnswer.Refused("RESERVATION_SHORT"))
    assertEquals(s.calls.size, 1)
    import scala.jdk.CollectionConverters.*
    val handed = retried.asScala.toVector
    assertEquals(handed.map(_._1.key), Vector("m1/t4/ada"))
    assertEquals(handed.map(_._2), Vector(LedgerAnswer.Refused("RESERVATION_SHORT")))
    assertEquals(handed.head._1.path, s.calls.head._3)
  }

  test("a payout the wallet cannot fund is handed to the retry; a reservation it refuses is not") {
    val (l, _) = ledger((_, _) => Some(409 -> """{"error":"INSUFFICIENT_FUNDS"}"""))
    assertEquals(l.payout(m, PositionKey.of(ada, OutcomeId("yes")), Money(1000, ada)), LedgerAnswer.Insufficient)
    assertEquals(l.reserve(m, OrderId("o9"), Money(1000, ada)), LedgerAnswer.Insufficient)
    import scala.jdk.CollectionConverters.*
    assertEquals(retried.asScala.toVector.map(_._1.key), Vector("m1/ada/yes"))
  }

  test("a payout is paid from the pool under the position; a position worth nothing makes no call") {
    val (l, s) = ledger()
    assertEquals(l.payout(m, PositionKey.of(ada, OutcomeId("yes")), Money(1000, ada)), LedgerAnswer.Done)
    assertEquals(s.calls.head._3, s"/v1/casino/brands/$brand/accounts/pool/m1/payments")
    assert(s.calls.head._4.contains("\"positionId\":\"ada/yes\""), s.calls.head._4)
    assertEquals(l.payout(m, PositionKey.of(ada, OutcomeId("no")), Money(0, ada)), LedgerAnswer.Done)
    assertEquals(s.calls.size, 1)
  }

  test("two markets' orders and trades under the same ids reach the wallet under different keys") {
    val (l, s) = ledger()
    val m2 = MarketId("m2")
    Seq(m, m2).foreach { x =>
      l.reserve(x, OrderId("o-1"), Money(600, ada)): Unit
      l.settle(x, TradeId("t-1"), Through.Split, Vector(leg("ada", "o-1", Side.Buy, charge = 600))): Unit
    }
    val keys = s.calls.map(_._4).map(b => "\"(key|orderId)\":\"([^\"]*)\"".r.findAllMatchIn(b).map(_.group(2)).toVector)
    assertEquals(keys, Vector(Vector("m1/o-1"), Vector("m1/t-1/ada", "m1/o-1"), Vector("m2/o-1"), Vector("m2/t-1/ada", "m2/o-1")))
  }
