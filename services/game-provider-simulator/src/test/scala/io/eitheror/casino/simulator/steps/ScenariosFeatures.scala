package io.eitheror.casino.simulator.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.simulator.SimulatorTestKit
import io.eitheror.casino.simulator.SimulatorTestKit.staff
import io.eitheror.casino.simulator.core.Scenario

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/simulator/scenarios.feature (US2). Each scenario is the shipped one with its schedule and
 * waits made fast (data, as a tester would put it, FR-019), selected for a fresh session before
 * launch; the fake brand is set to misbehave, then the game page loads and the scenario runs.
 */
class ScenariosFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/simulator/scenarios.feature"):

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit

  private var ticket = ""
  private var player = ""
  private var name   = ""

  /** The shipped scenario, with its schedule and waits shortened: data a tester could put. */
  private def fast(scenario: String): String =
    val shipped = Scenario.shipped.find(_.name == scenario).getOrElse(fail(s"no shipped scenario $scenario"))
    val root    = mapper.readTree(shipped.json).asInstanceOf[ObjectNode]
    val schedule = root.get("schedule").asInstanceOf[ObjectNode]
    schedule.put("wait", "1500ms")
    if schedule.get("intervals").size() > 0 then
      val intervals = mapper.createArrayNode()
      intervals.addObject().put("every", "300ms")
      schedule.set("intervals", intervals): Unit
      schedule.put("cap", "10s")
    root.get("steps").asInstanceOf[ArrayNode].elements().asScala.foreach {
      case step: ObjectNode if step.path("kind").asText() == "wait" && step.has("for") => step.put("for", "500ms"): Unit
      case _ =>
    }
    root.put("version", shipped.version + 100)
    mapper.writeValueAsString(root)

  private def scenarioName(text: String): String = text match
    case "unknown session ticket" => "unknown-token"
    case other                    => other.replace(' ', '-')

  /** Loads the game page, which checks the ticket and starts the scenario, and waits for its verdict. */
  private def play(): String =
    assertEquals(kit.get(s"/game?token=$ticket").status, 200)
    kit.verdict(ticket).body

  private def verdict = mapper.readTree(kit.verdict(ticket).body)
  private def passed() =
    val v = verdict
    assert(v.get("passed").asBoolean(), s"the run failed at step ${v.path("firstFailingStep")}: ${v.get("steps")}")

  private def run = mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket", Some(staff("brand_goldvibe"))).body).get("callbacks")

  Given("a brand configured with the simulator as its game provider") { () =>
    kit.start()
    kit.brand.clear()
  }

  Given("the simulator scenario {string}") { (text: String) =>
    name   = scenarioName(text)
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    assertEquals(kit.putScenario(fast(name)).status, 204)
    assertEquals(kit.select(ticket, name).status, 204)
    assertEquals(kit.launch(ticket, player).status, 200)
  }

  // --- slow operator ---

  When("the brand's answer to a debit takes longer than the provider waits") { () =>
    kit.brand.slow("debit", 1, 2500)
    play(): Unit
  }

  Then("the simulator abandons the request and sends the same debit again with the same transaction id on the scenario's retry schedule") { () =>
    val debits = kit.brand.requestsTo("debit").filter(_.text.contains(s"s-$ticket"))
    assert(debits.size >= 2, s"${debits.size} debits")
    assertEquals(debits.map(d => mapper.readTree(d.text).get("transaction_id").asText()).distinct.size, 1)
  }

  Then("the simulator run records every attempt and every answer") { () =>
    val debit = kit.eventually()(run.elements().asScala.find(_.get("kind").asText() == "debit").filter(_.get("attempts").asInt() >= 2))
    val attempts = debit.get("attemptsDetail").elements().asScala.toVector
    assert(attempts.head.get("error").asText().contains("no answer"), attempts.head.toString)
    assertEquals(attempts.last.get("status").asText(), "ok")
  }

  Then("the simulator run passes only if the brand's answer to the retry equals its first and the player's balance moved once") { () =>
    passed()
    assertEquals(kit.brand.balanceOf(player), BigDecimal("98.50"))
  }

  // --- cancel after credit ---

  When("a credit has been answered {string}") { (_: String) => play(): Unit }

  Then("the simulator sends a cancel of the bet") { () =>
    val debit  = mapper.readTree(kit.brand.requestsTo("debit").filter(_.text.contains(s"s-$ticket")).head.text)
    val cancel = mapper.readTree(kit.brand.requestsTo("cancel").filter(_.text.contains(s"s-$ticket")).head.text)
    assertEquals(cancel.get("transaction_id").asText(), debit.get("transaction_id").asText())
  }

  Then("the simulator run passes only if the brand answers as the original does for a cancel of a settled round and the balance is what the original would leave") { () =>
    passed()
    assertEquals(kit.brand.balanceOf(player), BigDecimal("120.00"))
  }

  // --- out of order ---

  When("a round's credit is sent before its debit") { () => play(): Unit }

  Then("the simulator run passes only if the brand answers the credit as the original answers a credit for an unknown round, and later accepts the debit") { () =>
    passed()
    val kinds = kit.brand.requests.filter(_.text.contains(s"s-$ticket")).map(_.path.split('/').last)
    assertEquals(kinds.filter(k => k == "credit" || k == "debit"), Vector("credit", "debit"))
  }

  // --- retry storm ---

  When("the brand answers {string}") { (_: String) =>
    kit.brand.failing("debit", 3)
    play(): Unit
  }

  Then("the simulator resends on the provider's schedule until the brand answers a word that is not {string} or the scenario's cap is reached") { (_: String) =>
    assertEquals(kit.brand.requestsTo("debit").count(_.text.contains(s"s-$ticket")), 4)
  }

  Then("the simulator run reports the count and the time to settle") { () =>
    passed()
    val debit = kit.eventually()(run.elements().asScala.find(_.get("kind").asText() == "debit").filter(_.get("attempts").asInt() == 4))
    assert(debit.get("lastAnsweredAt").asLong() > debit.get("firstSentAt").asLong())
  }

  // --- rotated key ---

  When("the simulator signs with its next key after publishing it in its key document") { () => play(): Unit }

  Then("the simulator run passes only if the brand admits provider calls under the next key within the brand's configured bound") { () =>
    val v = verdict.get("steps").elements().asScala.toVector
    assert(v.head.get("passed").asBoolean(), v.head.toString)
  }

  Then("the brand refuses provider calls under a key the key document no longer lists") { () =>
    passed()
    val document = mapper.readTree(kit.get("/.well-known/st8-keys").body)
    assert(!document.has("next"), document.toString)
  }

  // --- unknown session ticket ---

  When("a debit names a session ticket the brand never issued") { () => play(): Unit }

  Then("the simulator run passes only if the brand answers {string} and no balance moves") { (_: String) =>
    passed()
    assertEquals(kit.brand.balanceOf(player), BigDecimal("100.00"))
  }

  // --- win larger than any bet ---

  When("a credit of {string} follows a bet of {string}") { (_: String, _: String) => play(): Unit }

  Then("the simulator run passes only if the brand answers exactly as the original does") { () =>
    passed()
    assertEquals(kit.brand.balanceOf(player), BigDecimal("1000099.90"))
  }

  Then("the simulator run records the balance before and after") { () =>
    val balances = kit.eventually()(Option(run.elements().asScala.toVector.flatMap(r => Option(r.get("balance")).filter(!_.isNull).map(_.asText()))).filter(_.size >= 3))
    assertEquals(balances.take(3), Vector("100.00", "99.90", "1000099.90"))
  }

  // --- orphaned bet ---

  When("a debit is answered {string} and no credit, cancel or loss follows within the scenario's window") { (_: String) => play(): Unit }

  Then("the simulator sends a credit of {string}") { (amount: String) =>
    val credit = mapper.readTree(kit.brand.requestsTo("credit").filter(_.text.contains(s"s-$ticket")).last.text)
    assertEquals(credit.get("amount").asText(), amount)
  }

  Then("the simulator run passes only if the brand settles the round as a loss") { () =>
    passed()
    assertEquals(kit.brand.balanceOf(player), BigDecimal("99.00"))
  }

  // --- one session's scenario leaves another's alone ---

  Given("a simulator scenario selected for one session") { () =>
    assertEquals(kit.putScenario(fast("slow-operator")).status, 204)
    ticket = s"L-$scenarioId-a-${System.nanoTime()}"
    assertEquals(kit.select(ticket, "slow-operator").status, 204)
    kit.launch(ticket, s"p-$scenarioId-a"): Unit
  }

  When("another session on the same brand plays") { () =>
    player = s"p-$scenarioId-b"
    val other = s"L-$scenarioId-b-${System.nanoTime()}"
    kit.open(other, player): Unit
    name = other
  }

  Then("the other session runs the default simulator scenario") { () =>
    assertEquals(kit.session(name).scenario, "default")
    assertEquals(kit.session(ticket).scenario, "slow-operator")
    val bet = kit.post("/test/bet", s"""{"launchToken":"$name","amount":"2.00"}""")
    assertEquals(bet.body, """{"status":"ok","balance":"98.00","currency":"EUR"}""")
  }
