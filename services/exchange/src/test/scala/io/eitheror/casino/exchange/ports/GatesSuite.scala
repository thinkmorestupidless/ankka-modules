package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.GateAnswer
import io.eitheror.casino.exchange.Gate as Switch

import java.time.Instant

/** Every row of the gate port's table (contracts/ports.md) against scripted neighbours. */
class GatesSuite extends munit.FunSuite:
  private val brand = "goldvibe"

  private def order(side: Side, quantity: Long = 10, price: Long = 60): ExchangeCommand.Place =
    ExchangeCommand.Place(OrderId("o1"), OwnerId("ada"), OutcomeId("yes"), side, price, quantity, OrderDuration.RestUntilClose, Instant.EPOCH)

  /** The gate port over `answers`, with the gates `on`; the scripted clients, to read what was asked. */
  private def port(answers: PartialFunction[String, Option[(Int, String)]], on: Set[Switch] = Switch.values.toSet) =
    val s = Scripted((_, path) => answers.applyOrElse(path, (_: String) => Some(200 -> """{"status":"active","allowed":true,"decision":"allowed"}""")))
    (Gates(() => Neighbours(s, brand), () => on, _ => "EUR"), s)

  private def ask(answers: PartialFunction[String, Option[(Int, String)]], side: Side = Side.Buy, on: Set[Switch] = Switch.values.toSet) =
    val (g, s) = port(answers, on)
    (g.mayPlace(OwnerId("ada"), MarketId("m1"), order(side)), s.calls.map(_._1))

  private val status = s"/v1/casino/brands/$brand/players/ada/status"
  private val play   = s"/v1/casino/brands/$brand/kyc/ada/play-check"
  private val bet    = s"/v1/casino/brands/$brand/rg/may-bet"

  test("a buy asks the account, the play gate and the bet gate, in that order, with its cost") {
    val (answer, asked) = ask(PartialFunction.empty)
    assertEquals(answer, GateAnswer.Allow)
    assertEquals(asked, Vector("players", "kyc", "responsible-gambling"))
    val (g, s) = port(PartialFunction.empty)
    g.mayPlace(OwnerId("ada"), MarketId("m1"), order(Side.Buy)): Unit
    assert(s.calls.last._4.contains("\"amount\":600"), s.calls.last._4)
    assert(s.calls.last._4.contains("\"currency\":\"EUR\""), s.calls.last._4)
  }

  test("a sell skips the bet gate") {
    assertEquals(ask(PartialFunction.empty, Side.Sell)._2, Vector("players", "kyc"))
  }

  test("an account that is not active refuses by its status and asks nothing more") {
    val (answer, asked) = ask({ case `status` => Some(200 -> """{"status":"suspended"}""") })
    assertEquals(answer, GateAnswer.Refused("account: suspended"))
    assertEquals(asked, Vector("players"))
  }

  test("the play gate refuses by the KYC status") {
    val (answer, asked) = ask({ case `play` => Some(403 -> """{"allowed":false,"kycStatus":"pending"}""") })
    assertEquals(answer, GateAnswer.Refused("play gate: pending"))
    assertEquals(asked, Vector("players", "kyc"))
  }

  test("the bet gate refuses by RG's decision") {
    assertEquals(ask({ case `bet` => Some(200 -> """{"decision":"cooling_off"}""") })._1, GateAnswer.Refused("bet gate: cooling_off"))
  }

  test("each gate fails closed: unreachable, a 5xx or an answer it cannot read is unavailable") {
    assertEquals(ask({ case `status` => None })._1, GateAnswer.Refused("account: unavailable"))
    assertEquals(ask({ case `play` => Some(503 -> "{}") })._1, GateAnswer.Refused("play gate: unavailable"))
    assertEquals(ask({ case `play` => Some(200 -> "{}") })._1, GateAnswer.Refused("play gate: unavailable"))
    assertEquals(ask({ case `bet` => Some(200 -> """{"decision":"maybe"}""") })._1, GateAnswer.Refused("bet gate: unavailable"))
  }

  test("the brand's gates name which of the play gate and the bet gate run") {
    assertEquals(ask(PartialFunction.empty, on = Set(Switch.MayBet))._2, Vector("players", "responsible-gambling"))
    assertEquals(ask(PartialFunction.empty, on = Set(Switch.PlayGate))._2, Vector("players", "kyc"))
    assertEquals(ask(PartialFunction.empty, on = Set.empty)._2, Vector("players"))
  }
