package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.predictions.exchange.{OrderId, OrderState, OutcomeId, OwnerId}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A player's erasure at the exchange (T041, R13, FR-008): their resting orders cancelled first, which
 * releases a buy's reservation, their positions left to pay or void, the player marked; a repeat
 * answers the first, and only `players` may ask.
 */
class ErasureItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def erase(p: String, as: String = "players") = kit.request("POST", kit.servicePath(s"/erasure/players/$p"), headers = kit.asService(as))

  test("an erasure cancels the player's resting orders, releases their reservation, keeps their position and answers again the same") {
    val m = s"erase-${UUID.randomUUID().toString.take(8)}"
    val p = s"eve-${UUID.randomUUID().toString.take(6)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    Seq(p, "bob").foreach(kit.wallet.real.put(_, 10000L))
    assertEquals(kit.place(p, m, s"$m-1", "buy", "yes", 5, 60).status, 200)
    assertEquals(kit.place("bob", m, s"$m-2", "buy", "no", 5, 40).status, 200)
    kit.eventually(s"$p holds 5")(Option.when(kit.book(m).position(OwnerId(p), OutcomeId("yes")).quantity == 5)(()))
    assertEquals(kit.place(p, m, s"$m-3", "buy", "yes", 2, 30).status, 200)
    kit.eventually("the resting buy's reservation")(Option.when(kit.wallet.reserved(p) == 60L)(()))
    kit.eventually("the orders view")(Option.when(kit.request("GET", kit.playerPath(s"/players/$p?read=orders"),
      headers = kit.asPlayer(p)).json.get("orders").size == 2)(()))

    assertEquals(erase(p, as = "markets").status, 403)
    val first = erase(p)
    assertEquals(first.status, 200, first.text)
    assertEquals((first.json.get("ordersCancelled").asInt, first.json.get("positionsKept").asInt), (1, 1))
    assertEquals(kit.book(m).orders.get(OrderId(s"$m-3")).map(_.state), Some(OrderState.Cancelled))
    kit.eventually("the reservation released")(Option.when(kit.wallet.reserved(p) == 0L)(()))
    assertEquals(kit.book(m).position(OwnerId(p), OutcomeId("yes")).quantity, 5L)
    assertEquals(erase(p).text, first.text)
  }
