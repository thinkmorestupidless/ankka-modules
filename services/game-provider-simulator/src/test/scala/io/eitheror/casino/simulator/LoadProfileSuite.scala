package io.eitheror.casino.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.simulator.SimulatorTestKit.staff

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** A load profile's mechanics with a small profile (US5): sessions checked, rounds at the rate, the report, the refusals. */
class LoadProfileSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit
  private def admin  = Some(staff("brand_goldvibe"))

  private def tickets(prefix: String, n: Int): Vector[String] =
    (1 to n).toVector.map { i =>
      val t = s"$prefix-$i-${System.nanoTime()}"
      kit.launch(t, s"p-$prefix-$i"): Unit
      t
    }

  private def start(sessions: Vector[String], body: String = ""): SimulatorTestKit.Answer =
    kit.post("/v1/simulator/brands/goldvibe/load-profiles",
      s"""{"sessions":[${sessions.map(s => s"\"$s\"").mkString(",")}],"roundsPerMinute":60,"duration":"6s","seed":11$body}""", admin)

  test("five sessions are checked, play rounds at the rate for the duration, and the report counts them") {
    val sessions = tickets("load", 5)
    val started  = start(sessions)
    assertEquals(started.status, 201, started.body)
    val id = mapper.readTree(started.body).get("profileId").asText()
    val done = kit.eventually(60.seconds) {
      val s = mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/load-profiles/$id", admin).body)
      Option.when(s.get("state").asText() == "done")(s)
    }
    sessions.foreach(t => assert(kit.session(t).checked, s"$t was not checked"))
    sessions.foreach(t => assertEquals(kit.session(t).openRounds, Vector.empty))
    val report = done.get("report")
    // 6 seconds at a round a second each: about 6 rounds a session; at least 4 each, at most 7
    val rounds = report.get("roundsSent").asInt()
    assert(rounds >= 20 && rounds <= 35, report.toString)
    assertEquals(report.get("answersByWord").fieldNames().asScala.toSet, Set("ok"))
    assert(report.get("p99Millis").asLong() >= report.get("p50Millis").asLong())
    assertEquals(report.get("notAccepted").size(), 0)
  }

  test("a profile is refused against a brand that is not a test brand, and nothing is touched") {
    val refused = kit.post("/v1/simulator/brands/realvibe/load-profiles", """{"sessions":["x"],"roundsPerMinute":1,"duration":"1m"}""", Some(staff("brand_realvibe")))
    assertEquals(refused.status, 403)
    assert(refused.body.contains("not_a_test_brand"), refused.body)
  }

  test("a second profile is refused while the first runs") {
    // a later key wins in a JSON object: this profile runs for 20 seconds
    val first = start(tickets("busy", 1), ""","duration":"20s"""")
    assertEquals(first.status, 201, first.body)
    val second = start(tickets("busy2", 1))
    assertEquals(second.status, 409, second.body)
    kit.post(s"/v1/simulator/brands/goldvibe/load-profiles/${mapper.readTree(first.body).get("profileId").asText()}/stop", "{}", admin): Unit
  }

  test("a profile plays only the default scenario's sends") {
    val refused = start(tickets("scripted", 1), ""","scenario":"slow-operator"""")
    assertEquals(refused.status, 400)
    assert(refused.body.contains("scenario_not_instruction_driven"))
  }
