package io.eitheror.casino.exchange.reads

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.it.SharedKit

import java.util.UUID
import scala.concurrent.duration.*

/**
 * `orders-by-owner` and `trades-by-owner` (T034) from the exchange's journal: an order's fills and its
 * end, a trade's legs by owner, and the last sequence of the market each view has seen.
 */
class ReadRowsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def views = kit.testKit.service.viewClient
  private def orders(p: String) = views.forView(OrderRows).ask(OrderRows.byOwner, "owner" -> p).collect { case o: OrderRow.Order => o }
  private def legs(p: String)   = views.forView(TradeRows).ask(TradeRows.byOwner, "owner" -> p).collect { case l: TradeRow.Leg => l }

  test("each owner's orders with their fills and ends, each owner's legs of a trade, and the sequence seen") {
    val m = s"rows-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    Seq("ada", "bob").foreach(kit.wallet.real.put(_, 10000L))
    assertEquals(kit.place("ada", m, s"$m-a1", "buy", "yes", 10, 60).status, 200)
    assertEquals(kit.place("bob", m, s"$m-b1", "buy", "no", 4, 40).status, 200)
    assertEquals(kit.place("ada", m, s"$m-a2", "buy", "yes", 5, 30).status, 200)
    assertEquals(kit.json("POST", kit.playerPath(s"/orders/$m/$m-a2"), Json.obj("cancel" -> true), kit.asPlayer("ada")).status, 200)

    val sequence = kit.components.forEventSourcedEntity(EntityId(m)).call(kit.exchange.components.exchange.prices).invoke().sequence
    kit.eventually("the orders view at the book's sequence") {
      views.forView(OrderRows).ask(OrderRows.seen, "market" -> m).collectFirst { case s: OrderRow.Seen if s.sequence == sequence => () }
    }
    kit.eventually("the trades view's legs")(Option.when(legs("bob").nonEmpty)(()))
    assertEquals(orders("ada").map(o => (o.order, o.filled, o.state)).sortBy(_._1),
      Vector((s"$m-a1", 4L, "filled-in-part"), (s"$m-a2", 0L, "cancelled")))
    assertEquals(orders("bob").map(o => (o.order, o.filled, o.state)), Vector((s"$m-b1", 4L, "filled")))
    assertEquals(legs("ada").map(l => (l.order, l.outcome, l.quantity, l.through)), Vector((s"$m-a1", "yes", 4L, "split")))
    assertEquals(legs("bob").map(l => (l.order, l.outcome, l.quantity, l.price)), Vector((s"$m-b1", "no", 4L, 40L)))
    assert(orders("cat").isEmpty && legs("cat").isEmpty)
  }
