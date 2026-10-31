package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.predictions.exchange.{OrderId, OrderState}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A placement over Postgres (T026): a wallet that has no answer yet is asked again under the same key
 * and the order is funded once; a gate that cannot be reached refuses the order closed; a book with
 * no terms yet refuses it naming the state.
 */
class PlacementItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  private def market() =
    val m = s"placement-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    m

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  test("a wallet that answers OUTCOME_PENDING, then moves, funds the order once") {
    val m = market()
    kit.wallet.real.put("ada", 1000L)
    kit.wallet.script.put("reservations", List(503 -> """{"error":"OUTCOME_PENDING"}"""))
    val r = kit.place("ada", m, "o1", "buy", "yes", 10, 60)
    assertEquals(r.status, 200, r.text)
    assertEquals(kit.wallet.calls("reservations").map(_._1), Vector(s"$m/o1", s"$m/o1"))
    assertEquals(Option(kit.wallet.reservations.get(s"$m/o1")), Some("ada" -> 600L))
    assertEquals(kit.book(m).orders.get(OrderId("o1")).map(_.state), Some(OrderState.Resting))
  }

  test("an unreachable kyc refuses the order as the play gate unavailable, and reserves nothing") {
    val m = market()
    kit.wallet.real.put("ada", 1000L)
    kit.gates.down = Set("kyc")
    val r = kit.place("ada", m, "o2", "buy", "yes", 10, 60)
    assertEquals(r.status, 403, r.text)
    assertEquals(r.str("error"), "play gate: unavailable")
    assert(kit.wallet.calls("reservations").isEmpty)
    assert(kit.book(m).orders.isEmpty)
  }

  test("another player's order cannot be cancelled") {
    val m = market()
    kit.wallet.real.put("ada", 1000L)
    assertEquals(kit.place("ada", m, "o3", "buy", "yes", 1, 60).status, 200)
    val r = kit.json("POST", kit.playerPath(s"/orders/$m/o3"), io.eitheror.casino.exchange.Json.obj("cancel" -> true), kit.asPlayer("bob"))
    assertEquals(r.status, 400, r.text)
    assertEquals(r.str("error"), "owner")
    assertEquals(kit.book(m).orders.get(OrderId("o3")).map(_.state), Some(OrderState.Resting))
  }

  test("an order on a book markets has not opened yet is refused naming the state, with nothing asked of the wallet") {
    val m = s"placement-${UUID.randomUUID().toString.take(8)}"
    kit.wallet.real.put("ada", 1000L)
    val r = kit.place("ada", m, "o4", "buy", "yes", 10, 60)
    assertEquals(r.status, 409, r.text)
    assertEquals((r.str("error"), r.str("state")), ("state", "empty"))
    assert(kit.wallet.calls("reservations").isEmpty)
  }

  test("a second open of a book keeps its first terms") {
    val m = market()
    kit.open(m, Vector("yes", "no"), "GBP"): Unit
    val terms = kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(m))
      .call(io.eitheror.casino.exchange.terms.MarketTerms.get).invoke()
    assertEquals(terms.currency, "EUR")
  }

  test("a repeat of an order, sent later, is answered as the first; another order under its id is refused naming the id") {
    val m = market()
    kit.wallet.real.put("ada", 1000L)
    val first = kit.place("ada", m, "o5", "buy", "yes", 2, 60)
    assertEquals(first.status, 200, first.text)
    Thread.sleep(5)
    val again = kit.place("ada", m, "o5", "buy", "yes", 2, 60)
    assertEquals((again.status, again.text), (first.status, first.text))
    val other = kit.place("ada", m, "o5", "buy", "yes", 3, 60)
    assertEquals(other.status, 409, other.text)
    assertEquals((other.str("error"), other.str("id")), ("id", "o5"))
    assertEquals(kit.wallet.calls("reservations").map(_._1), Vector(s"$m/o5"))
  }
