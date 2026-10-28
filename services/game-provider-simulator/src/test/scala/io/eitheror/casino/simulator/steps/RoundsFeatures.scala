package io.eitheror.casino.simulator.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.simulator.SimulatorTestKit
import io.eitheror.casino.simulator.SimulatorTestKit.Answer

import scala.concurrent.duration.*

/** features/simulator/rounds.feature against the simulator and a fake brand (US1). */
class RoundsFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/simulator/rounds.feature") with LogCapturing:

  override val munitTimeout = 3.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    "the original's recordings replay against the rebuilt brand with the simulator in its default simulator scenario, with the same calls in the same order and the same answers" ->
      "spec 003's parity kit, with the simulator as the provider (quickstart §3); it needs the rebuilt game-provider"
  )

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit

  private var ticket = ""
  private var player = ""
  private var answer: Answer = null
  private var round  = ""
  private var betTx  = ""

  private def json(text: String) = mapper.readTree(text)

  Given("a brand configured with the simulator as its game provider") { () =>
    kit.start()
    kit.brand.clear()
  }

  Given("a player of the brand") { () =>
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
  }

  When("the player launches a game") { () => answer = kit.launch(ticket, player) }

  Then("the simulator stores the session and returns a game page address") { () =>
    assertEquals(answer.status, 200)
    assertEquals(answer.body, s"""{"game_url":"http://simulator.test/game?token=$ticket"}""" + "\n")
    val s = kit.session(ticket)
    assertEquals((s.brand, s.playerId, s.currency, s.gameCode), ("goldvibe", player, "EUR", "book-of-ra"))
  }

  Then("on the page's load it checks the launch ticket with the brand and keeps the session ticket the brand returns") { () =>
    val page = kit.get(s"/game?token=$ticket")
    assertEquals(page.status, 200)
    assert(page.body.contains(s"""data-wallet-token="s-$ticket" data-launch-token="$ticket""""), page.body)
    val check = kit.brand.requestsTo("check").last
    assertEquals(check.text, s"""{"token":"$ticket"}""")
    assert(kit.brand.signedByListedKey(check))
    assertEquals(kit.session(ticket).walletToken, Some(s"s-$ticket"))
  }

  Given("an open session at the simulator") { () =>
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    kit.open(ticket, player): Unit
    kit.brand.clear()
  }

  When("the tester instructs a bet of {string} in {string}") { (amount: String, currency: String) =>
    answer = kit.post("/test/bet", s"""{"launchToken":"$ticket","amount":"$amount","currency":"$currency"}""")
  }

  Then("the simulator sends one signed debit carrying a new transaction id, a round id, the currency, the amount and the kind {string}") { (kind: String) =>
    val debits = kit.brand.requestsTo("debit")
    assertEquals(debits.size, 1)
    assert(kit.brand.signedByListedKey(debits.head))
    val body = json(debits.head.text)
    assert(body.get("transaction_id").asText().matches("[0-9a-f-]{36}"))
    assert(body.get("round").asText().nonEmpty)
    assertEquals((body.get("currency").asText(), body.get("amount").asText(), body.get("provider_kind").asText()), ("EUR", "1.50", kind))
  }

  Then("it returns the brand's answer and balance unchanged") { () =>
    assertEquals(answer.status, 200)
    assertEquals(answer.body, """{"status":"ok","balance":"98.50","currency":"EUR"}""")
    assert(answer.contentType.startsWith("application/json"))
  }

  Given("a bet in an open round at the simulator") { () =>
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    kit.open(ticket, player): Unit
    round = s"r-$scenarioId"
    betTx = s"t-$scenarioId"
    assertEquals(kit.post("/test/bet", s"""{"launchToken":"$ticket","amount":"1.00","roundId":"$round","transactionId":"$betTx"}""").status, 200)
    kit.brand.clear()
  }

  When("the tester instructs a win of {string}") { (amount: String) =>
    answer = kit.post("/test/win", s"""{"launchToken":"$ticket","amount":"$amount","roundId":"$round"}""")
  }

  Then("the simulator sends one signed credit for the round") { () =>
    val credits = kit.brand.requestsTo("credit")
    assertEquals(credits.size, 1)
    assert(kit.brand.signedByListedKey(credits.head))
    assertEquals(json(credits.head.text).get("round").asText(), round)
  }

  Then("it returns the brand's answer") { () =>
    assertEquals(answer.status, 200)
    assertEquals(json(answer.body).get("status").asText(), "ok")
  }

  When("the tester instructs a loss") { () =>
    answer = kit.post("/test/lose", s"""{"launchToken":"$ticket","roundId":"$round"}""")
  }

  Then("the simulator sends a credit of {string} for the round") { (amount: String) =>
    val credit = json(kit.brand.requestsTo("credit").last.text)
    assertEquals((credit.get("amount").asText(), credit.get("round").asText(), credit.get("provider_kind").asText()), (amount, round, "credit"))
  }

  Then("the brand settles the round as a loss") { () =>
    assertEquals(json(answer.body).get("status").asText(), "ok")
    assertEquals(kit.brand.balanceOf(player), BigDecimal("99.00"))
    assertEquals(kit.session(ticket).rounds(round).state, "lost")
  }

  When("the tester instructs a cancel") { () =>
    answer = kit.post("/test/cancel", s"""{"launchToken":"$ticket","roundId":"$round","transactionId":"$betTx"}""")
  }

  Then("the simulator sends one signed cancel naming the bet's transaction id and a new cancel id") { () =>
    val cancels = kit.brand.requestsTo("cancel")
    assertEquals(cancels.size, 1)
    assert(kit.brand.signedByListedKey(cancels.head))
    val body = json(cancels.head.text)
    assertEquals(body.get("transaction_id").asText(), betTx)
    assert(body.get("cancel_id").asText().matches("[0-9a-f-]{36}"))
    assertEquals(json(answer.body).get("status").asText(), "ok")
    assertEquals(kit.brand.balanceOf(player), BigDecimal("100.00"))
  }
