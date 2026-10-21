package io.eitheror.casino.contract.port

import com.fasterxml.jackson.databind.ObjectMapper

import java.nio.file.Paths

class RecordingSuite extends munit.FunSuite:

  private val root    = Paths.get(sys.props.getOrElse("casino.root", "."))
  private val fixture = root.resolve("tests/fixtures/port/wallet/TestFixture_BetThenBalance.json")
  private val mapper  = ObjectMapper()
  private def json(s: String) = Some(mapper.readTree(s))

  private val recording = Recording.load(fixture).fold(e => throw AssertionError(e), identity)

  /** A rebuilt wallet that answers as the original did, with its own generated ids and clock. */
  private def faithful(balance: String = "89.50"): Recording.Request => Recording.Response =
    case r if r.method == "POST" => Recording.Response(200, json(s"""{"transaction_id":"new-1","balance":"$balance"}"""))
    case r if r.path.endsWith("/new-1") => Recording.Response(200, json("""{"transaction_id":"new-1","amount":1050,"created_at":"2026-12-01T10:00:00Z"}"""))
    case r => Recording.Response(404, json(s"""{"error":"no ${r.path}"}"""))

  test("a recording decodes with its steps, captures and the fields that vary") {
    assertEquals(recording.scenario, "TestFixture_BetThenBalance")
    assertEquals(recording.runs, 2)
    assertEquals(recording.steps.size, 2)
    assertEquals(recording.vary.keySet, Set("$.transaction_id", "$.created_at"))
  }

  test("a step keeps the service the recorder sorted it under, and has none when the recorder named none") {
    val sorted = Recording.parse(fixture, mapper.readTree(
      """{"scenario":"TestS","runs":2,"original":{"brand":"spinvibe","commit":"21da61f0"},"steps":[{"name":"n","request":{"method":"GET","path":"/a","service":"wallet"},"response":{"status":200}}]}"""))
    assertEquals(sorted.map(_.steps.head.request.service), Right(Some("wallet")))
    assertEquals(sorted.map(_.original.get("brand")), Right(Some("spinvibe")))
    assertEquals(recording.steps.head.request.service, None)
  }

  test("a rebuild answering the same way passes: varying fields need only be present, captures fill later paths") {
    assertEquals(Replay.run(recording, faithful()), Vector.empty)
  }

  test("a changed answer fails naming the recording and the field") {
    val differences = Replay.run(recording, faithful(balance = "89.51"))
    assertEquals(differences.size, 1)
    val d = differences.head
    assertEquals(d.at, "$.steps[0].response.body.balance")
    assert(d.message.contains("TestFixture_BetThenBalance.json"), d.message)
    assert(d.message.contains("\"89.50\""), d.message)
  }

  test("a varying field that is missing fails") {
    val missing: Recording.Request => Recording.Response =
      case r if r.method == "POST" => Recording.Response(200, json("""{"balance":"89.50"}"""))
      case _                       => Recording.Response(404, None)
    assert(Replay.run(recording, missing).exists(_.at.endsWith("transaction_id")))
  }

  test("a different status fails naming it") {
    val refusing: Recording.Request => Recording.Response = _ => Recording.Response(500, None)
    assert(Replay.run(recording, refusing).exists(_.at == "$.steps[0].response.status"))
  }

  test("an arranged step (setup:, observe:, time:) is sent and its captures taken, and its answer not compared") {
    val arranged = Recording.parse(fixture, mapper.readTree(
      """{"scenario":"TestA","runs":2,"steps":[
        |{"name":"setup: seed","request":{"method":"POST","path":"/seed"},"response":{"status":201,"body":{"id":"orig-1","extra":true}},"capture":{"id":"$.id"}},
        |{"name":"time: the deadline passes","request":{"method":"POST","path":"/sweep"},"response":{"status":200,"body":{"expired":1}}},
        |{"name":"read","request":{"method":"GET","path":"/things/{id}"},"response":{"status":200,"body":{"ok":true}}}]}""".stripMargin))
      .fold(e => throw AssertionError(e), identity)
    val sent = scala.collection.mutable.Buffer.empty[String]
    val standIn: Recording.Request => Recording.Response = r =>
      sent += r.path
      if r.path == "/seed" then Recording.Response(200, json("""{"id":"new-1"}"""))
      else if r.path == "/sweep" then Recording.Response(204, None)
      else Recording.Response(200, json("""{"ok":true}"""))
    assertEquals(Replay.run(arranged, standIn), Vector.empty)
    assertEquals(sent.toVector, Vector("/seed", "/sweep", "/things/new-1"))
    assert(Replay.run(arranged, r => if r.path.startsWith("/things") then Recording.Response(200, json("""{"ok":false}""")) else standIn(r))
      .exists(_.at == "$.steps[2].response.body.ok"))
  }

  test("a step's vary holds in that step only; the recording-wide vary of older recordings in every step") {
    def two(vary: String) = Recording.parse(fixture, mapper.readTree(
      s"""{"scenario":"TestV","runs":2,$vary"steps":[
        |{"name":"page","request":{"method":"GET","path":"/page"},"response":{"status":200,"body":"<html>run 1</html>"}STEPVARY},
        |{"name":"read","request":{"method":"GET","path":"/read"},"response":{"status":200,"body":{"status":"captured"}}}]}""".stripMargin
        .replace("STEPVARY", if vary.isEmpty then ""","vary":{"$":"differs"}""" else ""))).fold(e => throw AssertionError(e), identity)
    val other: Recording.Request => Recording.Response = r =>
      if r.path == "/page" then Recording.Response(200, json("\"<html>run 2</html>\"")) else Recording.Response(200, json("""{"status":"failed"}"""))
    assertEquals(Replay.run(two(""), other).map(_.at), Vector("$.steps[1].response.body.status"))
    assertEquals(Replay.run(two(""""vary":{"$":"differs"},"""), other), Vector.empty)
  }

  test("JSONPath reads names and indices") {
    val node = mapper.readTree("""{"a":{"b":[{"c":1},{"c":2}]}}""")
    assertEquals(JsonPath.get(node, "$.a.b[1].c").map(_.asInt()), Some(2))
    assertEquals(JsonPath.get(node, "$.a.x"), None)
  }
