package io.eitheror.casino.simulator.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.simulator.SimulatorTestKit
import io.eitheror.casino.simulator.SimulatorTestKit.Answer

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/simulator/catalogue-and-free-rounds.feature (US3): the operator API and free rounds. */
class CatalogueFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/simulator/catalogue-and-free-rounds.feature") with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit

  private var request = ""
  private var answer: Answer = null
  private var bonus   = ""
  private var player  = ""
  private var ticket  = ""

  private def json(text: String) = mapper.readTree(text)

  Given("a brand configured with the simulator as its game provider") { () =>
    kit.start()
    kit.brand.clear()
  }

  Given("the simulator's list of fake games") { () => () }

  When("the brand asks for the list of games") { () => answer = kit.get("/api/operator/v1/games?site=goldvibe") }

  Then("it receives games, developers and categories in the game provider's shape") { () =>
    assertEquals(answer.status, 200)
    val body = json(answer.body)
    assertEquals(body.fieldNames().asScala.toVector, Vector("categories", "developers", "games"))
    assertEquals(body.get("developers").get(0).fieldNames().asScala.toVector, Vector("developer_id", "name"))
    assertEquals(body.get("categories").get(0).fieldNames().asScala.toVector, Vector("id", "name"))
  }

  Then("the three games the original's stub advertises are among them, with their bonus types, game features and themes") { () =>
    val games = json(answer.body).get("games").elements().asScala.toVector
    assertEquals(games.map(_.get("code").asText()), Vector("book-of-ra", "fake-slot-002", "fake-table-001"))
    val ra = games.head
    assertEquals(ra.get("bonus_types").elements().asScala.map(_.asText()).toVector, Vector("free_bets", "bonus_buy"))
    assertEquals(ra.get("features").elements().asScala.map(_.asText()).toVector, Vector("expanding_symbol", "gamble"))
    assertEquals(ra.get("themes").elements().asScala.map(_.asText()).toVector, Vector("egypt", "adventure"))
  }

  Given("a request to provision free rounds on a game the simulator's list has") { () =>
    bonus   = s"b-$scenarioId"
    request = s"""{"bonus_id":"$bonus","players":["p1","p2"],"count":3,"value":"0.10","type":"free_bets","currency":"EUR","game_codes":["book-of-ra"],"site":"goldvibe"}"""
  }

  Given("a request to provision free rounds on a game the simulator's list does not have") { () =>
    request = s"""{"bonus_id":"b-$scenarioId","players":["p1"],"count":"3","game_codes":["no-such-game"]}"""
  }

  When("it is received") { () => answer = kit.post("/api/operator/v1/bonus/create", request) }

  Then("the simulator stores one instance per player with the count remaining") { () =>
    val stored = json(kit.get(s"/test/bonus/$bonus").body)
    assertEquals(stored.get("instances").get("p1").get("remaining").asInt(), 3)
    assertEquals(stored.get("instances").get("p2").get("status").asText(), "active")
  }

  Then("it answers {string} with the instances") { (word: String) =>
    assertEquals(answer.body,
      s"""{"bonus":{"bonus_id":"$bonus","instances":[{"player":"p1","remaining":3,"status":"active"},{"player":"p2","remaining":3,"status":"active"}]},"status":"$word"}""" + "\n")
  }

  Then("it answers {string} with {string}") { (word: String, error: String) =>
    assertEquals(answer.status, 200)
    assertEquals(answer.body, s"""{"error":"$error","status":"$word"}""" + "\n")
  }

  private def session(): Unit =
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    kit.open(ticket, player): Unit

  private def provision(count: Int, game: String = "book-of-ra"): Unit =
    bonus = s"b-$scenarioId-${System.nanoTime()}"
    val created = kit.post("/api/operator/v1/bonus/create",
      s"""{"bonus_id":"$bonus","players":["$player"],"count":$count,"value":"0.10","type":"free_bets","currency":"EUR","game_codes":["$game"]}""")
    assertEquals(json(created.body).get("status").asText(), "ok")

  Given("a player with an active free rounds instance on the session's game") { () =>
    session()
    provision(1)
    kit.brand.clear()
  }

  Given("a player with no active free rounds instance for the session's game") { () =>
    session()
    provision(1, game = "fake-slot-002")
    kit.brand.clear()
  }

  When("the tester spends a free round") { () =>
    answer = kit.post("/test/free-spin", s"""{"launchToken":"$ticket","winAmount":"0.50"}""")
  }

  Then("the simulator sends a debit of {string} of kind {string} and then a credit of the win on the same round") { (amount: String, kind: String) =>
    assertEquals(answer.status, 200, answer.body)
    val sent = kit.brand.requests.filter(_.text.contains(s"s-$ticket"))
    assertEquals(sent.map(_.path.split('/').last), Vector("debit", "credit"))
    val debit  = json(sent(0).text)
    val credit = json(sent(1).text)
    assertEquals((debit.get("amount").asText(), debit.get("provider_kind").asText()), (amount, kind))
    assertEquals((credit.get("amount").asText(), credit.get("provider_kind").asText()), ("0.50", "credit"))
    assertEquals(credit.get("round").asText(), debit.get("round").asText())
    assertEquals(json(answer.body).fieldNames().asScala.toVector, Vector("credit", "debit", "remaining", "roundId"))
  }

  Then("the instance is decremented, and marked {string} at zero") { (status: String) =>
    assertEquals(json(answer.body).get("remaining").asInt(), 0)
    assertEquals(json(kit.get(s"/test/bonus/$bonus").body).get("instances").get(player).get("status").asText(), status)
  }

  Then("the simulator refuses with {string}") { (error: String) =>
    assertEquals(answer.status, 409)
    assertEquals(answer.body, s"""{"error":"$error"}""" + "\n")
  }

  Then("it sends nothing") { () => assertEquals(kit.brand.requests.count(_.text.contains(s"s-$ticket")), 0) }

  Given("a provisioned free rounds instance of a player") { () =>
    session()
    provision(3)
  }

  When("the operator cancels it") { () =>
    answer = kit.post("/api/operator/v1/bonus/cancel", s"""{"bonus_id":"$bonus","player":"$player","site":"goldvibe"}""")
  }

  Then("the instance is {string}") { (status: String) =>
    assertEquals(json(answer.body).get("bonus").get("instances").get(0).get("status").asText(), status)
  }

  Then("later spends are refused") { () =>
    assertEquals(kit.post("/test/free-spin", s"""{"launchToken":"$ticket","winAmount":"0.50"}""").status, 409)
  }
