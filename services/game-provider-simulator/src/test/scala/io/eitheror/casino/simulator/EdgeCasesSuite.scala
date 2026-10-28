package io.eitheror.casino.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.simulator.SimulatorTestKit.staff
import io.eitheror.casino.simulator.brands.{BrandTable, Sender}
import io.eitheror.casino.simulator.core.Scenario

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The spec's edge cases, the operator routes' refusals (FR-027), determinism (SC-003) and an unreachable brand. */
class EdgeCasesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = SimulatorTestKit.kit
  private def admin  = Some(staff("brand_goldvibe"))

  private def fresh(prefix: String) = (s"L-$prefix-${System.nanoTime()}", s"p-$prefix")

  private def fast(name: String): String =
    val root = mapper.readTree(Scenario.shipped.find(_.name == name).get.json).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    val schedule = root.get("schedule").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    schedule.put("wait", "1500ms")
    if schedule.get("intervals").size() > 0 then
      val intervals = mapper.createArrayNode()
      intervals.addObject().put("every", "300ms")
      schedule.set("intervals", intervals): Unit
      schedule.put("cap", "3s")
    mapper.writeValueAsString(root)

  override def beforeAll(): Unit = kit.start()

  test("a launch ticket used twice: the second check is refused and the session-expired page is served, with HTTP 200") {
    val (ticket, player) = fresh("twice")
    kit.open(ticket, player): Unit
    val again = kit.get(s"/game?token=$ticket")
    assertEquals(again.status, 200)
    assert(again.body.contains("Session expired. Please relaunch the game."), again.body)
    assert(again.contentType.startsWith("text/html"))
  }

  test("a brand that holds no key of the simulator refuses every callback; the run says so in its first callback") {
    val (ticket, player) = fresh("nokey")
    kit.launch(ticket, player): Unit
    val kid = kit.keys.currentKeyId
    kit.brand.distrust(kid)
    try
      val page = kit.get(s"/game?token=$ticket")
      assert(page.body.contains("Session expired"), page.body)
      val first = kit.eventually() {
        // the view folds the attempt in after the callback's row appears: wait for the answer
        Option(mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket", admin).body).get("callbacks"))
          .filter(_.size() > 0).map(_.get(0)).filter(r => r.hasNonNull("status"))
      }
      assertEquals((first.get("kind").asText(), first.get("status").asText()), ("check", "auth_failed"))
    finally kit.brand.trust(kid)
  }

  test("a malformed body is answered HTTP 400 and never retried") {
    assertEquals(kit.putScenario(fast("malformed-body")).status, 204)
    val (ticket, player) = fresh("malformed")
    assertEquals(kit.select(ticket, "malformed-body").status, 204)
    kit.open(ticket, player): Unit
    val verdict = mapper.readTree(kit.verdict(ticket).body)
    assert(verdict.get("passed").asBoolean(), verdict.toString)
    val debit = kit.eventually()(Option(mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket", admin).body).get("callbacks"))
      .flatMap(_.elements().asScala.find(_.get("kind").asText() == "debit")).filter(_.get("outcome").asText() == "malformed_body"))
    assertEquals(debit.get("attempts").asInt(), 1)
  }

  test("an operator route without an identity is refused before it reaches the simulator, and nothing is recorded") {
    val (ticket, player) = fresh("anon")
    val refused = kit.put(s"/v1/simulator/brands/goldvibe/sessions/$ticket/scenario", """{"name":"slow-operator"}""")
    assertEquals(refused.status, 401)
    kit.launch(ticket, player): Unit
    assertEquals(kit.session(ticket).scenario, "default")
    assertEquals(kit.get("/v1/simulator/scenarios").status, 401)
  }

  test("an operator of another brand is refused; a platform administrator is not") {
    val other = kit.get("/v1/simulator/brands/goldvibe/runs/none", Some(staff("brand_spinvibe")))
    assertEquals(other.status, 403)
    assertEquals(kit.get("/v1/simulator/scenarios", Some(staff("brand_goldvibe"))).status, 403)
    assertEquals(kit.get("/v1/simulator/scenarios", Some(staff("brand_tools"))).status, 200)
    assertEquals(kit.get("/v1/simulator/scenarios", Some(staff("platform_admin"))).status, 200)
  }

  test("a brand the simulator does not serve is unknown") {
    val answer = kit.get("/v1/simulator/brands/nobrand/runs/x", Some(staff("platform_admin")))
    assertEquals(answer.status, 404)
    assert(answer.body.contains("unknown_brand"))
  }

  test("an instruction before the game page has loaded, for an unknown session, or with a bad body, is refused as the stand-in refuses it") {
    val (ticket, player) = fresh("early")
    kit.launch(ticket, player): Unit
    val early = kit.post("/test/bet", s"""{"launchToken":"$ticket","amount":"1.00"}""")
    assertEquals((early.status, early.body), (409, "wallet token not set — game page not loaded yet\n"))
    assertEquals(kit.post("/test/bet", """{"launchToken":"nobody","amount":"1.00"}""").status, 404)
    val bad = kit.post("/test/bet", """{"launchToken":"x","amount":1.00}""")
    assertEquals((bad.status, bad.body), (400, "bad request\n"))
  }

  test("the scenario library refuses a scenario that breaks the schema, naming where") {
    val refused = kit.put("/v1/simulator/scenarios/broken", """{"name":"broken","version":1,"source":"s","shape":"stub","steps":[]}""", Some(staff("platform_admin")))
    assertEquals(refused.status, 400)
    assert(refused.body.contains("schedule"), refused.body)
    assertEquals(kit.put("/v1/simulator/scenarios/default", Scenario.shipped.find(_.name == "default").get.json, Some(staff("platform_admin"))).status, 409)
  }

  test("a run with a given seed sends the same callbacks in the same order (SC-003)") {
    assertEquals(kit.putScenario(fast("win-larger-than-any-bet")).status, 204)
    def runWith(prefix: String): Vector[String] =
      val (ticket, player) = fresh(prefix)
      assertEquals(kit.put(s"/v1/simulator/brands/goldvibe/sessions/$ticket/scenario", """{"name":"win-larger-than-any-bet","seed":42}""", admin).status, 204)
      kit.open(ticket, player): Unit
      kit.verdict(ticket): Unit
      val rows = kit.eventually()(Option(mapper.readTree(kit.get(s"/v1/simulator/brands/goldvibe/runs/$ticket", admin).body).get("callbacks")).filter(_.size() == 4))
      // what was sent, without the session ticket and player, which are this session's
      rows.elements().asScala.toVector.map(r => r.get("body").asText().replace(s"s-$ticket", "<ticket>").replace(player, "<player>").replace(ticket, "<launch>"))
    assertEquals(runWith("seed-a"), runWith("seed-b"))
  }

  test("the stand-in's health is ready once the key is held, and the key document lists the current key") {
    val health = kit.get("/health")
    assertEquals((health.status, health.body), (200, """{"status":"ready"}"""))
    val document = mapper.readTree(kit.get("/.well-known/st8-keys").body)
    assertEquals(document.get("current").get("kid").asText(), kit.keys.currentKeyId)
    assertEquals(document.get("cacheSeconds").asInt(), 60)
  }

  test("an operator's rotation publishes the next key, then promotes it after the brand's bound") {
    val before  = kit.keys.currentKeyId
    val started = kit.post("/v1/simulator/brands/goldvibe/keys/rotate", """{"boundSeconds":1}""", admin)
    assertEquals(started.status, 202, started.body)
    val promoted = kit.eventually()(Option(kit.keys.currentKeyId).filter(_ != before))
    assert(!mapper.readTree(kit.get("/.well-known/st8-keys").body).has("next"))
    // the brand admits the new key: a round still plays
    val (ticket, player) = fresh("rotated")
    kit.open(ticket, player): Unit
    assertEquals(mapper.readTree(kit.post("/test/bet", s"""{"launchToken":"$ticket","amount":"1.00"}""").body).get("status").asText(), "ok")
    assertNotEquals(promoted, before)
  }

  test("the key is pushed to an original adapter as the stand-in pushes it: retried until HTTP 200 (research R8)") {
    val adapter = com.thinkmorestupidless.ankka.testkit.ScriptedService.start()
    try
      adapter.answer(_ => com.thinkmorestupidless.ankka.sdk.ServiceResponse(200, "application/json", """{"status":"ok"}""".getBytes, Vector.empty))
      adapter.failNext(503)
      adapter.failNext(503)
      val at = java.net.URI.create(s"${adapter.address}/st8/test/set-public-key")
      assert(Simulation.registerKey(at, kit.keys.currentPublicText, attempts = 5, pause = 50.millis))
      val sent = adapter.requests.filter(_.path == "/st8/test/set-public-key")
      assert(sent.size >= 2, s"${sent.size} attempts")
      assertEquals(sent.last.text, s"""{"publicKey":"${kit.keys.currentPublicText}"}""")
    finally adapter.stop()
  }

  test("a brand that cannot be reached: each attempt is recorded as unreachable") {
    val closed  = java.net.ServerSocket(0)
    val port    = closed.getLocalPort
    closed.close()
    val table   = BrandTable.parse(s"""[{"slug":"gone","baseAddress":"http://127.0.0.1:$port","testBrand":true}]""", "http://x", None, None, Map.empty).toOption.get
    val attempt = Sender(table, kit.keys).send("gone", "/st8/balance", "{}".getBytes, "current", 1.second, 1)
    assert(attempt.httpStatus.isEmpty)
    assert(attempt.error.exists(_.startsWith("brand unreachable")), attempt.toString)
  }
