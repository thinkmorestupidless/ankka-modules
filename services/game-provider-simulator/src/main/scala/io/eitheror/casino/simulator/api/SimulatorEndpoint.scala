package io.eitheror.casino.simulator.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import io.eitheror.casino.simulator.Simulator
import io.eitheror.casino.simulator.core.*

import scala.concurrent.duration.*
import scala.util.Try

/**
 * The simulator's operator routes (FR-027; contracts/routes.md), one endpoint at `/v1/simulator`
 * because ankka refuses two endpoints sharing a prefix: what acts on one brand is held to
 * `Routes.operator(<brand>)`, the library and the status to `Routes.operator("tools")`. Nothing
 * here is public.
 */
final class SimulatorEndpoint(sim: Simulator, access: Access, version: String) extends HttpEndpoint("/v1/simulator"):

  val acl: Acl = Acl.DenyAll

  private val mapper = ObjectMapper()

  private given JsonValueCodec[SimulatorEndpoint.RunPage] = Codecs.make[SimulatorEndpoint.RunPage]
  private given JsonValueCodec[SimulatorEndpoint.VerdictPage] = Codecs.make[SimulatorEndpoint.VerdictPage]
  private given JsonValueCodec[LoadState]          = Codecs.make[LoadState]
  private given JsonValueCodec[RotationState]      = Codecs.make[RotationState]
  private given JsonValueCodec[Vector[ScenarioRow]] = Codecs.make[Vector[ScenarioRow]]
  private given JsonValueCodec[SimulatorEndpoint.Status] = Codecs.make[SimulatorEndpoint.Status]

  private def ok[A: JsonValueCodec](value: A, status: Int = 200): Respond[Bytes] = Respond(Bytes("application/json", writeToArray(value)), status)
  private def problem(status: Int, error: String, detail: String = ""): Respond[Bytes] =
    val node = mapper.createObjectNode().put("error", error)
    if detail.nonEmpty then node.put("detail", detail): Unit
    Respond(Bytes("application/json", mapper.writeValueAsBytes(node)), status)
  private def fromError(e: CommandError): Respond[Bytes] =
    val status = e.code match
      case ErrorCode.NotFound  => 404
      case ErrorCode.Conflict  => 409
      case ErrorCode.Forbidden => 403
      case _                   => 400
    problem(status, e.message.takeWhile(_ != ':').trim, e.message)

  private def known(brand: String)(f: => Respond[Bytes]): Respond[Bytes] =
    if sim.table.known(brand) then
      try f
      catch case e: CommandError => fromError(e)
    else problem(404, "unknown_brand", s"this simulator does not serve brand $brand")

  private def json(text: String): Option[ObjectNode] = Try(mapper.readTree(text)).toOption.collect { case o: ObjectNode => o }
  private def text(o: ObjectNode, f: String): Option[String] = Option(o.get(f)).filter(_.isTextual).map(_.asText())

  private def scenarioExists(name: String): Boolean =
    try { sim.client.forKeyValueEntity(EntityId(name)).call(ScenarioEntity.get).invoke(); true }
    catch case _: CommandError => false

  // --- per brand ---

  withAcl(access.byBrand) {

    putBody("/brands/{brand}/sessions/{token}/scenario") { (brand: String, token: String, body: String) =>
      known(brand) {
        json(body).flatMap(text(_, "name")) match
          case None                                => problem(400, "name_required")
          case Some(name) if !scenarioExists(name) => problem(404, "unknown_scenario", s"no scenario named $name")
          case Some(name) =>
            val seed = json(body).flatMap(o => Option(o.get("seed"))).filter(_.canConvertToLong).map(_.asLong())
            sim.client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.selectScenario).invoke(SessionEntity.Selection(name, seed))
            Respond(Bytes("application/json", Array.emptyByteArray), 204)
      }
    }

    putBody("/brands/{brand}/scenario") { (brand: String, body: String) =>
      known(brand) {
        json(body).flatMap(text(_, "name")) match
          case None                                => problem(400, "name_required")
          case Some(name) if !scenarioExists(name) => problem(404, "unknown_scenario", s"no scenario named $name")
          case Some(name) =>
            sim.client.forKeyValueEntity(EntityId(brand)).call(BrandDefaults.set).invoke(name)
            Respond(Bytes("application/json", Array.emptyByteArray), 204)
      }
    }

    get("/brands/{brand}/runs/{run}") { (brand: String, run: String) =>
      known(brand) {
        val after = query.raw("after").getOrElse("-1:")
        val (afterAt, afterId) = after.split(":", 2) match
          case Array(at, id) => (at, id)
          case Array(at)     => (at, "")
        val rows = sim.views.forView(RunCallbacks).ask(RunCallbacks.byRun, 500, "run" -> run, "after_at" -> afterAt, "after_id" -> afterId)
          .filter(_.brand == brand)
        ok(SimulatorEndpoint.RunPage(run, rows, rows.lastOption.filter(_ => rows.size == 500).map(r => s"${r.firstSentAt}:${r.callbackId}")))
      }
    }

    get("/brands/{brand}/runs/{run}/verdict") { (brand: String, run: String) =>
      known(brand) {
        val session = sim.client.forEventSourcedEntity(EntityId(run)).call(SessionEntity.get).invoke()
        if session.brand != brand then problem(404, "run_not_found")
        else
          val play = Try(sim.client.forWorkflow(EntityId(run)).call(ScenarioPlay.state).invoke()).toOption
          val verdicts = session.verdicts.sortBy(_.step)
          val failing  = verdicts.find(!_.passed).map(_.step)
          val finished = play.exists(_.finished) || session.scenario == "default"
          ok(SimulatorEndpoint.VerdictPage(run, session.scenario, finished, verdicts.nonEmpty && failing.isEmpty, failing, verdicts))
      }
    }

    get("/brands/{brand}/runs/{run}/export") { (brand: String, run: String) =>
      known(brand) {
        val rows = sim.views.forView(RunCallbacks).ask(RunCallbacks.byRun, 1000, "run" -> run, "after_at" -> "-1", "after_id" -> "").filter(_.brand == brand)
        if rows.isEmpty then problem(404, "run_not_found")
        else
          val session  = Try(sim.client.forEventSourcedEntity(EntityId(run)).call(SessionEntity.get).invoke()).toOption
          val scenario = session.map(_.scenario).getOrElse("load")
          val source   = Try(sim.client.forKeyValueEntity(EntityId(scenario)).call(ScenarioEntity.get).invoke()).toOption
            .flatMap(s => Scenario.parse(s.json).toOption).map(_.source).getOrElse("")
          Respond(Bytes("application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Export.recording(brand, run, scenario, source, version, rows))), 200)
      }
    }

    postBody("/brands/{brand}/load-profiles") { (brand: String, body: String) =>
      known(brand) {
        if !sim.table.testBrand(brand) then problem(403, "not_a_test_brand", s"brand $brand is not marked as a test brand in this simulator's configuration")
        else
          json(body) match
            case None => problem(400, "bad_request")
            case Some(o) =>
              val sessions = Option(o.get("sessions")).filter(_.isArray).map(a => (0 until a.size).toVector.map(a.get(_).asText())).getOrElse(Vector.empty)
              val rate     = Option(o.get("roundsPerMinute")).filter(_.canConvertToInt).map(_.asInt()).getOrElse(0)
              val duration = text(o, "duration").map(Durations.parse)
              val scenario = text(o, "scenario").getOrElse("default")
              val seed     = Option(o.get("seed")).filter(_.canConvertToLong).map(_.asLong()).getOrElse(java.util.concurrent.ThreadLocalRandom.current().nextLong())
              (sessions, rate, duration) match
                case (s, _, _) if s.isEmpty            => problem(400, "sessions_required")
                case (_, r, _) if r <= 0               => problem(400, "rounds_per_minute_required")
                case (_, _, None | Some(Left(_)))      => problem(400, "duration_required", "a duration such as 5m")
                case _ if scenario != "default"        => problem(400, "scenario_not_instruction_driven", "a load profile plays the default scenario's sends")
                case (s, r, Some(Right(d))) =>
                  val active = sim.client.forKeyValueEntity(EntityId(brand)).call(ActiveProfile.get).invoke()
                  val running = active.nonEmpty && Try(sim.client.forWorkflow(EntityId(active)).lifecycle(LoadProfile).invoke()).toOption.exists(!_.isTerminal)
                  if running then problem(409, "load_profile_running", s"profile $active is running on brand $brand")
                  else
                    val id = s"$brand-${java.util.UUID.randomUUID().toString.take(8)}"
                    sim.client.forKeyValueEntity(EntityId(brand)).call(ActiveProfile.set).invoke(id)
                    ok(sim.client.forWorkflow(EntityId(id)).call(LoadProfile.start).invoke(LoadProfile.Start(brand, s, r, d.toMillis, seed, Wiring.now())), 201)
      }
    }

    post("/brands/{brand}/load-profiles/{id}/stop") { (brand: String, id: String) =>
      known(brand) {
        sim.client.forKeyValueEntity(EntityId(id)).call(LoadStop.request).invoke()
        ok(sim.client.forWorkflow(EntityId(id)).call(LoadProfile.get).invoke())
      }
    }

    get("/brands/{brand}/load-profiles/{id}") { (brand: String, id: String) =>
      known(brand) {
        val state = sim.client.forWorkflow(EntityId(id)).call(LoadProfile.get).invoke()
        if state.brand != brand then problem(404, "load_profile_not_found") else ok(state)
      }
    }

    postBody("/brands/{brand}/keys/rotate") { (brand: String, body: String) =>
      known(brand) {
        val bound = json(body).flatMap(o => Option(o.get("boundSeconds"))).filter(_.canConvertToLong).map(_.asLong().seconds)
          .getOrElse(Wiring.brands.keyRefreshBound(brand))
        ok(sim.client.forWorkflow(EntityId(s"rotation-${Wiring.now()}")).call(KeyRotation.start).invoke(bound.toMillis), 202)
      }
    }
  }

  // --- the installation's ---

  withAcl(access.tools) {

    get("/scenarios") { () =>
      ok(sim.views.forView(ScenarioLibrary).ordered(sql"true", jsonText("name")))
    }

    get("/scenarios/{name}") { (name: String) =>
      try
        val stored = sim.client.forKeyValueEntity(EntityId(name)).call(ScenarioEntity.get).invoke()
        Respond(Bytes("application/json", stored.json.getBytes("UTF-8")), 200)
      catch case e: CommandError => fromError(e)
    }

    putBody("/scenarios/{name}") { (name: String, body: String) =>
      Scenario.problems(body).headOption match
        case Some(first) => problem(400, "invalid_scenario", first)
        case None =>
          val scenario = Scenario.parse(body).toOption.get
          if scenario.name != name then problem(400, "invalid_scenario", s"$$.name: '${scenario.name}' is not '$name'")
          else
            try
              sim.client.forKeyValueEntity(EntityId(name)).call(ScenarioEntity.put).invoke(StoredScenario(name, scenario.version, scenario.json, shipped = false))
              Respond(Bytes("application/json", Array.emptyByteArray), 204)
            catch case e: CommandError => fromError(e)
    }

    get("/status") { () =>
      val status = sim.client.forKeyValueEntity(EntityId(SimulatorStatusEntity.Id)).call(SimulatorStatusEntity.get).invoke()
      ok(SimulatorEndpoint.Status(status.sweptRuns, status.lastSweepAt, sim.keys.currentKeyId, sim.keys.nextKeyId, sim.table.brands.map(b => SimulatorEndpoint.BrandStatus(b.slug, b.testBrand))))
    }
  }

object SimulatorEndpoint:
  final case class RunPage(runId: String, callbacks: Vector[RunCallbackRow], next: Option[String])
  final case class VerdictPage(runId: String, scenario: String, finished: Boolean, passed: Boolean, firstFailingStep: Option[Int], steps: Vector[Verdict])
  final case class BrandStatus(slug: String, testBrand: Boolean)
  final case class Status(sweptRuns: Long, lastSweepAt: Option[Long], currentKeyId: String, nextKeyId: Option[String], brands: Vector[BrandStatus])
