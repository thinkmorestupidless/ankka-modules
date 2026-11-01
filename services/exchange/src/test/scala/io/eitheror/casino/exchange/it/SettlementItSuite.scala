package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.predictions.exchange.{OutcomeId, OwnerId}
import io.eitheror.casino.exchange.Json

import java.util.UUID
import scala.concurrent.duration.*

/**
 * Settlement over Postgres (T032), on a kit of its own since it restarts the service: a trade whose
 * settlement the wallet has not answered when the service stops is settled once after it starts; a
 * final market pays every position from the pool. A refused settlement retried under its key is
 * `RetryItSuite`'s.
 */
class SettlementItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private lazy val kit: Kit = Kit()
  override def beforeEach(context: BeforeEach): Unit = kit.reset()
  override def afterAll(): Unit = kit.stop()

  private def market() =
    val m = s"settle-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    m

  /** `a` buys `n` of yes at 60 and `b` `n` of no at 40: one split trade, settled. */
  private def split(m: String, a: String, b: String, n: Long): Unit =
    assertEquals(kit.place(a, m, s"$m-$a", "buy", "yes", n, 60).status, 200)
    assertEquals(kit.place(b, m, s"$m-$b", "buy", "no", n, 40).status, 200)
    kit.eventually(s"$a and $b hold") {
      Option.when(kit.book(m).position(OwnerId(a), OutcomeId("yes")).quantity == n && kit.wallet.reserved(a) == 0 &&
        kit.wallet.reserved(b) == 0)(())
    }

  test("a trade the wallet has not settled when the service stops is settled once after it starts") {
    val m = market()
    Seq("ada" -> 1000L, "bob" -> 1000L, "pat" -> 1000L).foreach(kit.wallet.real.put(_, _))
    split(m, "bob", "pat", 10)
    kit.wallet.script.put("settlements", List.fill(10000)(503 -> """{"error":"OUTCOME_PENDING"}"""))
    assertEquals(kit.place("bob", m, s"$m-sell", "sell", "yes", 10, 60).status, 200)
    assertEquals(kit.place("ada", m, s"$m-buy", "buy", "yes", 10, 60).status, 200)
    kit.eventually("the settlement asked for")(Option.when(kit.wallet.calls("settlements").nonEmpty)(()))

    kit.testKit.restartService(downFor = 1.second, whileStopped = kit.wallet.script.clear())

    kit.eventually("settled after the restart", 60.seconds)(Option.when(kit.wallet.real.get("bob") == 1000L)(()))
    Thread.sleep(1000)
    assertEquals(kit.wallet.calls("settlements").map(_._1).distinct.size, 1)
    assertEquals((kit.wallet.real.get("ada"), kit.wallet.real.get("bob")), (400L, 1000L))
    assertEquals(kit.wallet.reserved("ada"), 0L)
  }

  test("a final market pays every position from the pool, and the pool ends empty") {
    val m = market()
    Seq("ada", "bob", "cat", "dan").foreach(kit.wallet.real.put(_, 1000L))
    split(m, "ada", "bob", 10)
    split(m, "cat", "dan", 5)
    kit.eventually("the pool at 1500")(Option.when(kit.wallet.pools.getOrDefault(m, 0L) == 1500L)(()))
    Seq("close" -> Json.obj("at" -> kit.now().toString), "finalise" -> Json.obj("outcome" -> "yes", "at" -> kit.now().toString))
      .foreach((c, b) => assertEquals(kit.json("POST", kit.servicePath(s"/markets/$m/$c"), b, kit.asService("markets")).status, 200))
    kit.eventually("every winner paid")(Option.when(kit.wallet.calls("payments").size == 2)(()))
    assertEquals(kit.wallet.calls("payments").map(c => c._2.get("player").asText -> c._2.get("amount").asLong).toMap,
      Map("ada" -> 1000L, "cat" -> 500L))
    assertEquals(kit.wallet.pools.get(m), 0L)
    assertEquals((kit.wallet.real.get("ada"), kit.wallet.real.get("cat")), (1400L, 1200L))
  }
