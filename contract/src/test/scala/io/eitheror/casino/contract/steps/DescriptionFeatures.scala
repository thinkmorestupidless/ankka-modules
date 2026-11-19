package io.eitheror.casino.contract.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.ComponentRegistry
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer, Respond}
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.http.Json
import io.eitheror.casino.contract.port.DescriptionCheck
import io.eitheror.casino.contract.port.DescriptionCheck.OriginalRoute

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/**
 * features/brands/service-descriptions.feature (spec 031). The outline and the two route-description
 * scenarios read the kept descriptions (`services/<service>/openapi.player.json`), which each
 * service's CI job compares with what its routes say; the check's scenarios run `DescriptionCheck`
 * over the fixtures of `DescriptionCheckSuite`; the incomplete route is a fixture endpoint asked for
 * its document as `ankka openapi --strict` asks; the changed route is `scripts/describe-diff.py`
 * over its fixtures.
 */
class DescriptionFeatures extends GherkinSuite("features/brands/service-descriptions.feature"):
  import DescriptionFeatures.*

  private val fixtureSuite = "responsible-gambling's it.HeldFixtureSuite: it needs a served route, so a running kit"

  override protected def ranElsewhere: Map[String, String] = Map(
    "describing a route changes nothing the route answers"                             -> fixtureSuite,
    "a test that causes an answer the service description does not allow fails" -> fixtureSuite
  )

  private val root   = Paths.get(sys.props.getOrElse("casino.root", "."))
  private val mapper = ObjectMapper()

  private def kept(service: String): JsonNode = mapper.readTree(root.resolve(s"services/$service/openapi.player.json").toFile)

  private def operations(document: JsonNode): Vector[(String, String, JsonNode)] =
    document.path("paths").properties().asScala.toVector.flatMap { p =>
      p.getValue.properties().asScala.toVector.collect { case m if Methods(m.getKey) => (m.getKey.toUpperCase, p.getKey, m.getValue) }
    }

  private def operation(document: JsonNode, method: String, pathEnds: String): JsonNode =
    operations(document).collectFirst { case (m, p, node) if m == method && p.endsWith(pathEnds) => node }
      .getOrElse(fail(s"no $method …$pathEnds in the kept description"))

  private def tags(op: JsonNode): Set[String] = op.path("tags").elements().asScala.map(_.asText).toSet

  // ── every player route, and no other ─────────────────────────────────────

  private var service  = ""
  private var document = Option.empty[JsonNode]

  Given("the service {string}") { (name: String) => service = name }

  When("its service description is written") { () => document = Some(kept(service)) }

  Then("it holds every player route of the service") { () =>
    // A player route is one its endpoint tags `player`; the kept description holds one operation for each.
    val sources = Files.walk(root.resolve(s"services/$service/src/main/scala")).iterator.asScala.filter(_.toString.endsWith(".scala")).toVector
    val tagged  = sources.map(f => """\.tag\("player"""".r.findAllIn(Files.readString(f)).size).sum
    val ops     = operations(document.get)
    assert(ops.nonEmpty, s"$service keeps no operation")
    assertEquals(ops.size, tagged, s"$service: the kept operations and the routes its sources tag player")
    assert(ops.forall((_, _, op) => tags(op)("player")), ops.filterNot((_, _, op) => tags(op)("player")).map(o => s"${o._1} ${o._2}").toString)
  }

  Then("it holds no route of an operator, of another service or of a provider") { () =>
    val others = operations(document.get).map(_._2).filter(p => NotAPlayers.exists(p.contains))
    assertEquals(others, Vector.empty[String])
  }

  // ── a route description ──────────────────────────────────────────────────

  private var route = Option.empty[JsonNode]

  Given("a player route of the service {string}") { (name: String) =>
    service = name
    document = Some(kept(name))
    route = Some(operation(document.get, "GET", "/{playerId}/messages"))
  }

  Given("the push connection of the service {string}") { (name: String) =>
    document = Some(kept(name))
    route = Some(operation(document.get, "GET", "/v1/stream"))
  }

  When("its route description is read") { () => assert(route.isDefined) }

  Then("it states a summary, what the route takes and what it answers") { () =>
    val op = route.get
    assert(op.path("summary").asText.nonEmpty, "no summary")
    assert(op.path("parameters").elements().asScala.exists(p => p.path("in").asText == "path" && p.path("name").asText == "playerId"))
    assert(op.path("responses").path("200").path("content").path("application/json").has("schema"), "no answer shape")
    // a route that takes a body says so too
    val edit = operation(document.get, "PATCH", "/{playerId}")
    assert(edit.path("requestBody").path("content").path("application/json").has("schema"), "no body shape")
  }

  Then("it states every refusal the route makes and every query parameter it reads") { () =>
    val op       = route.get
    val refusals = op.path("responses").fieldNames().asScala.toSet.filter(s => s.headOption.exists(c => c == '4' || c == '5'))
    assert(refusals.nonEmpty, "no refusal named")
    assert(op.path("responses").has("default"), "no default refusal")
    val queried = op.path("parameters").elements().asScala.filter(_.path("in").asText == "query").map(_.path("name").asText).toSet
    assertEquals(queried, Set("status", "page", "pageSize"))
  }

  Then("it states whether the caller is a visitor, a signed-in player or a player presenting a cashier ticket") { () =>
    // contracts/generation.md: `security: []` is a visitor; no `security` a signed-in player; the tag `ticket` a ticket.
    val register = operation(document.get, "POST", "/register")
    assert(register.has("security") && register.get("security").isEmpty, "the registration is not open to a visitor")
    assert(!route.get.has("security"), "the messages are open to a visitor")
    val ticketed = operations(kept("ingest")).map(_._3)
    assert(ticketed.nonEmpty && ticketed.forall(op => tags(op)("ticket")), "ingest's routes are not the ticket's")
  }

  Then("it states that the answer is a stream") { () =>
    assert(route.get.path("responses").path("200").path("content").has("text/event-stream"), route.get.path("responses").toString)
  }

  // ── an incomplete route ──────────────────────────────────────────────────

  private var written = Option.empty[Either[Vector[String], String]]

  Given("a player route whose route description does not state what it answers") { () => written = None }

  When("the service description is written") { () =>
    written = HttpServer.at("127.0.0.1", 0)(_ => Incomplete())
      .document("openapi", Map("name" -> "fixture", "tags" -> "player", "strict" -> "true"), ComponentRegistry.empty)
  }

  Then("it is refused, naming the route and what its route description lacks") { () =>
    val problems = written.getOrElse(fail("no document was asked for")).left.getOrElse(fail("the document was written"))
    assert(problems.exists(p => p.contains("/fixture/incomplete") && p.contains("lacks")), problems.toString)
    assert(!problems.exists(_.contains("/fixture/complete")), problems.toString)
  }

  // ── a route that changes without its kept description ────────────────────

  private val diffFixtures = root.resolve("scripts/fixtures/describe-diff")
  private var keptBytes    = Array.emptyByteArray
  private var diff         = (0, "")

  Given("the kept service description of the service {string}") { (_: String) =>
    keptBytes = Files.readAllBytes(diffFixtures.resolve("kept.json"))
  }

  When("a player route of the service answers a field the kept service description does not hold") { () =>
    val out = StringBuilder()
    val rc  = Process(Seq("python3", root.resolve("scripts/describe-diff.py").toString, diffFixtures.resolve("kept.json").toString,
      diffFixtures.resolve("fresh.json").toString)).!(ProcessLogger(l => out.append(l).append('\n'): Unit))
    diff = (rc, out.toString)
  }

  Then("the build fails, naming the route") { () =>
    assert(diff._1 != 0, "describe-diff found no difference")
    assert(diff._2.linesIterator.contains("changed GET /v1/casino/players/{brand}/wallets/{currency}/balance"), diff._2)
  }

  Then("the kept service description is unchanged") { () =>
    assert(java.util.Arrays.equals(Files.readAllBytes(diffFixtures.resolve("kept.json")), keptBytes))
  }

  // ── the description check ────────────────────────────────────────────────

  private def resource(p: String) = Path.of(getClass.getResource(s"/description-check/$p").toURI)

  private val Balance      = "get /v1/casino/players/{brand}/wallets/{currency}/balance"
  private val Transactions = "GET /v1/casino/players/{brand}/wallets/{currency}/transactions"
  private val table        = Vector(OriginalRoute("GET", "/v1/casino/players/[^/]+/wallets/[^/]+/balance".r, Some("wallet"), Balance))

  /** The fixture service's parity test skips the step named `skipped`: a difference its spec names. */
  private def check(asParity: Boolean) = DescriptionCheck.run(resource("openapi.player.json"), Vector(resource("recordings/TestBalance.json")),
    table, (_, step) => Option.when(!asParity || step.name != "skipped")(step))

  private var result = Option.empty[DescriptionCheck.Result]

  Given("a recording that holds a call of a player route") { () => result = None }
  Given("a recording in which a player route makes a refusal its route description omits") { () => result = None }
  Given("a player route that no recording calls") { () => result = None }
  Given("a recording whose answer differs from the rebuilt service by a difference a spec names") { () => result = None }

  When("the description check runs") { () => result = Some(check(asParity = true)) }

  Then("the recorded answer is one the route description allows") { () =>
    assert(result.get.matched > 0)
    assert(!result.get.differences.exists(d => Set("allowed", "named refusal", "platform status")(d.step)), result.get.differences.toString)
  }

  Then("it fails, naming the recording, the route and the refusal") { () =>
    val found = result.get.differences.find(_.step == "unnamed status").getOrElse(fail(result.get.differences.toString))
    assertEquals((found.recording, found.operationId), ("TestBalance.json", Balance))
    assert(found.what.contains("409"), found.what)
  }

  Then("the route is listed as having no recording") { () => assertEquals(result.get.unrecorded, Vector(Transactions)) }

  Then("the description check fails when the routes listed differ from the kept list of them") { () =>
    // Each service's DescriptionSuite compares the two as here: equal to the kept list, and not to another.
    val lists = Files.createTempDirectory("unrecorded")
    val dir   = Files.createDirectories(lists.resolve("tests/port/unrecorded-player-routes"))
    Files.writeString(dir.resolve("fixture.txt"), s"# kept\n$Transactions\n")
    Files.writeString(dir.resolve("stale.txt"), "# a route since recorded was never taken off\nGET /v1/casino/players/{brand}/wallets/{currency}/balance\n")
    assertEquals(result.get.unrecorded, DescriptionCheck.unrecordedList(lists, "fixture"))
    intercept[munit.ComparisonFailException](assertEquals(result.get.unrecorded, DescriptionCheck.unrecordedList(lists, "stale")))
  }

  Then("the recorded answer is checked as the service's parity test reads it") { () =>
    // Read as recorded, the step is matched and is a difference; read as the parity test reads it, it is not matched.
    val asRecorded = check(asParity = false)
    assert(asRecorded.differences.exists(_.step == "skipped"), asRecorded.differences.toString)
    assertEquals(result.get.matched, asRecorded.matched - 1)
  }

  Then("the difference is not a failure") { () =>
    assert(!result.get.differences.exists(_.step == "skipped"), result.get.differences.toString)
  }

object DescriptionFeatures:
  private val Methods = Set("get", "put", "post", "delete", "patch")

  /** What marks a path as an operator's, another service's or a provider's (spec 001's route classes). */
  private val NotAPlayers = Vector("/backoffice", "/internal/", "/callbacks", "/webhook")

  final case class Answer(ok: Boolean)

  /** One route described whole, and one whose description states no answer. */
  final class Incomplete extends HttpEndpoint("/fixture"):
    val acl: Acl = Acl.AllowAll

    described(_.summary("A route described whole").answers[Answer](200).tag("player")) {
      get("/complete") { () => Respond(Json("""{"ok":true}""".getBytes("UTF-8")), 200) }
    }
    described(_.summary("A route that does not state what it answers").tag("player")) {
      get("/incomplete") { () => Respond(Json("""{"ok":true}""".getBytes("UTF-8")), 200) }
    }
