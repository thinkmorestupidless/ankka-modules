package io.eitheror.casino.simulator.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.simulator.SimulatorTestKit
import io.eitheror.casino.simulator.SimulatorTestKit.staff

import scala.concurrent.duration.*

/**
 * features/simulator/load.feature (US5). The first scenario's figures are SC-004's: 100 sessions for
 * five real minutes against a rebuilt brand, measured by hand (quickstart §7); this suite proves the
 * stop, and `LoadProfileSuite` the mechanics with a small profile.
 */
class LoadFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/simulator/load.feature"):

  override val munitTimeout = 3.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    "a load profile launches its sessions, plays rounds at the rate, and reports rounds sent, answers by word and the percentile answer times" ->
      "quickstart §7 by hand against a rebuilt brand (SC-004: five real minutes); the mechanics are LoadProfileSuite's"
  )

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit
  private def admin  = Some(staff("brand_goldvibe"))

  private var profile  = ""
  private var sessions = Vector.empty[String]

  Given("a brand configured with the simulator as its game provider") { () =>
    kit.start()
    kit.brand.clear()
  }

  Given("a running load profile") { () =>
    sessions = (1 to 3).toVector.map { i =>
      val ticket = s"L-$scenarioId-$i-${System.nanoTime()}"
      kit.launch(ticket, s"p-$scenarioId-$i"): Unit
      ticket
    }
    val started = kit.post("/v1/simulator/brands/goldvibe/load-profiles",
      s"""{"sessions":[${sessions.map(s => s"\"$s\"").mkString(",")}],"roundsPerMinute":600,"duration":"10m","seed":7}""", admin)
    assertEquals(started.status, 201, started.body)
    profile = mapper.readTree(started.body).get("profileId").asText()
    // playing, with a round open in every session
    kit.eventually() {
      val state = mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/load-profiles/$profile", admin).body)
      Option.when(state.get("state").asText() == "playing" && state.get("rounds").asInt() >= 6)(())
    }
  }

  When("the tester stops it") { () =>
    assertEquals(kit.post(s"/v1/simulator/brands/goldvibe/load-profiles/$profile/stop", "{}", admin).status, 200)
  }

  Then("open rounds are settled as losses before the profile ends") { () =>
    val state = kit.eventually() {
      val s = mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/load-profiles/$profile", admin).body)
      Option.when(s.get("state").asText() == "stopped")(s)
    }
    sessions.foreach(t => assertEquals(kit.session(t).openRounds, Vector.empty, t))
    assert(state.get("report").get("roundsSent").asInt() >= 6, state.toString)
  }
