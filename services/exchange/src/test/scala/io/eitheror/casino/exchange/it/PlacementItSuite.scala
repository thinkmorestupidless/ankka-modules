package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.predictions.exchange.{OrderId, OrderState}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A placement over Postgres (T026): a wallet that has no answer yet is asked again under the same key
 * and the order is funded once; a gate that cannot be reached refuses the order closed.
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
    assertEquals(kit.wallet.calls("reservations").map(_._1), Vector("o1", "o1"))
    assertEquals(Option(kit.wallet.reservations.get("o1")), Some("ada" -> 600L))
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
