package io.eitheror.casino.simulator.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.simulator.SimulatorTestKit
import io.eitheror.casino.simulator.SimulatorTestKit.{Answer, staff}
import io.eitheror.casino.simulator.core.*
import io.eitheror.casino.simulator.st8.Signer

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/simulator/runs.feature (US4): the run, its verdict, its export as a recording, its retention. */
class RunsFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/simulator/runs.feature"):

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit

  private var ticket = ""
  private var player = ""
  private var answer: Answer = null
  private var swept  = 0

  private def admin = Some(staff("brand_goldvibe"))
  private def json(text: String) = mapper.readTree(text)

  private def playRound(): Unit =
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    kit.open(ticket, player): Unit
    kit.post("/test/bet", s"""{"launchToken":"$ticket","amount":"1.00","roundId":"r-$scenarioId"}"""): Unit
    kit.post("/test/win", s"""{"launchToken":"$ticket","amount":"2.00","roundId":"r-$scenarioId"}"""): Unit

  private def runRows = kit.eventually() {
    val rows = json(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket", admin).body).get("callbacks")
    Option(rows).filter(r => r.size() == 3 && r.elements().asScala.forall(_.get("attempts").asInt() == 1))
  }

  Given("a brand configured with the simulator as its game provider") { () =>
    kit.start()
    kit.brand.clear()
  }

  Given("a session that has played a round") { () => playRound() }

  When("the tester opens its simulator run") { () => runRows: Unit }

  Then("each provider call is listed with its content as sent, the signing key's id, the time sent, the answer's word and balance, the time taken, the attempt number and the simulator scenario step") { () =>
    val rows = runRows.elements().asScala.toVector
    assertEquals(rows.map(_.get("kind").asText()), Vector("check", "debit", "credit"))
    val debit   = rows(1)
    val attempt = debit.get("attemptsDetail").get(0)
    assert(debit.get("body").asText().contains("\"amount\":\"1.00\""))
    assertEquals(attempt.get("keyId").asText(), kit.keys.currentKeyId)
    assert(attempt.get("sentAt").asLong() > 0 && attempt.get("tookMillis").asLong() >= 0)
    assertEquals((attempt.get("status").asText(), attempt.get("balance").asText(), attempt.get("attempt").asInt()), ("ok", "99.00", 1))
    assert(!debit.has("step") || debit.get("step").isNull, "the default scenario has no step")
  }

  Given("a simulator run") { () => playRound(); runRows: Unit }

  When("the tester exports it") { () => answer = kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket/export", admin) }

  Then("the export is a recording in the shape the recordings are kept in") { () =>
    assertEquals(answer.status, 200)
    val recording = Recording.parse(Paths.get("export.json"), json(answer.body)).fold(e => fail(e), identity)
    assertEquals(recording.steps.map(_.request.path), Vector("/st8/check", "/st8/debit", "/st8/credit"))
    assert(recording.vary.keySet.exists(_.endsWith("x-st8-sign")))
    assertEquals(recording.steps.head.capture, Map("wallet_token" -> "$.token"))
  }

  Then("the brand's parity tests replay it") { () =>
    val recording = Recording.parse(Paths.get("export.json"), json(answer.body)).toOption.get
    kit.brand.issue(ticket, player) // a brand whose ticket is fresh, as a replaying kit's is
    val differences = Replay.run(recording, request => {
      val body    = request.body.map(b => mapper.writeValueAsBytes(b)).getOrElse(Array.emptyByteArray)
      val (_, key) = kit.keys.signingKey("current").get
      val sent = kit.http.send(
        HttpRequest.newBuilder(URI.create(kit.brand.address + request.path))
          .header("Content-Type", "application/json").header(Signer.Header, Signer.sign(body, key))
          .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
        HttpResponse.BodyHandlers.ofString())
      Recording.Response(sent.statusCode(), Option(sent.body()).filter(_.nonEmpty).map(mapper.readTree))
    })
    assertEquals(differences.map(_.message), Vector.empty)
  }

  Given("a simulator run of a simulator scenario with a verdict") { () =>
    val failing = """{"name":"expects-the-impossible","version":1,"source":"this suite","shape":"harness",
                    |"schedule":{"wait":"1s","intervals":[],"cap":"0s"},
                    |"steps":[{"kind":"send","callback":"balance"},{"kind":"expect","of":0,"status":["ok"]},
                    |         {"kind":"send","callback":"balance"},{"kind":"expect","of":2,"status":["not_enough_money"]}]}""".stripMargin
    assertEquals(kit.putScenario(failing).status, 204)
    player = s"p-$scenarioId"
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    assertEquals(kit.select(ticket, "expects-the-impossible").status, 204)
    kit.open(ticket, player): Unit
  }

  When("the tester reads the simulator run") { () => answer = kit.verdict(ticket) }

  Then("each step shows expected against observed") { () =>
    val steps = json(answer.body).get("steps").elements().asScala.toVector
    assertEquals(steps.map(_.get("step").asInt()), Vector(1, 3))
    assertEquals(steps.map(_.get("expected").asText()), Vector("status ok", "status not_enough_money"))
    assertEquals(steps.map(_.get("observed").asText()), Vector("status ok", "status ok"))
  }

  Then("the simulator run shows passed or failed with the first failing step") { () =>
    val v = json(answer.body)
    assertEquals((v.get("passed").asBoolean(), v.get("firstFailingStep").asInt()), (false, 3))
  }

  Given("simulator runs older than the installation's retention for the simulator") { () =>
    playRound()
    runRows: Unit
    kit.offset = 31.days.toMillis
  }

  When("the sweep runs") { () =>
    try swept = Sweep.run(kit.testKit.componentClient, kit.testKit.service.viewClient, Wiring.now() - 30.days.toMillis)
    finally kit.offset = 0L
  }

  Then("they are removed") { () =>
    assert(swept >= 1)
    kit.eventually() {
      val rows = json(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket", admin).body).get("callbacks")
      Option.when(rows.size() == 0)(())
    }
    assertEquals(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket/verdict", admin).status, 404)
  }

  Then("the count is in the simulator's status") { () =>
    val status = json(kit.get("/v1/simulator/status", Some(staff("platform_admin"))).body)
    assert(status.get("sweptRuns").asLong() >= swept, status.toString)
    assert(status.has("lastSweepAt"))
  }
