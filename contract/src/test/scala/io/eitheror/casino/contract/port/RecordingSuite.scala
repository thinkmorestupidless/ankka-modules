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

  test("JSONPath reads names and indices") {
    val node = mapper.readTree("""{"a":{"b":[{"c":1},{"c":2}]}}""")
    assertEquals(JsonPath.get(node, "$.a.b[1].c").map(_.asInt()), Some(2))
    assertEquals(JsonPath.get(node, "$.a.x"), None)
  }
