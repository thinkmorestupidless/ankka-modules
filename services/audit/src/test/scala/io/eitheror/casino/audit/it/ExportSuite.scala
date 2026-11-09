package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.exports.{Csv, Export}
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.registry.RegistryKey

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.duration.*

/** `/v1/audit-exports` (FR-014, SC-005): CSV and JSON lines, the signed URL, the same key twice, and its records. */
class ExportSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  private val from = Instant.parse("2026-10-01T00:00:00Z")
  private val to   = Instant.parse("2026-11-01T00:00:00Z")

  /** Three goldvibe records in October, one before it, and one of spinvibe; waits for the store to hold them. */
  private def loaded(): String =
    val corr = kit.fresh("c")
    val in   = (1 to 3).map(i => Kit.record(correlationId = corr, subjectId = "p-export", occurredAt = from.plusSeconds(3600L * i)))
    val out  = Vector(Kit.record(correlationId = corr, occurredAt = from.minusSeconds(60)),
      Kit.record(brand = "spinvibe", correlationId = corr, occurredAt = from.plusSeconds(60)))
    in.foreach(kit.publish("goldvibe", _))
    kit.publish("goldvibe", out.head)
    kit.publish("spinvibe", out(1))
    val digests = Set("goldvibe", "spinvibe").map(Ids.digest(RegistryKey.required(kit.secrets), _, corr))
    kit.eventually(30.seconds)(Option.when(kit.records.count(r => digests(r.correlation)) >= 5)(()))
    corr

  private def exportAs(key: String, as: Seq[(String, String)], brand: String = "goldvibe") =
    kit.request("POST", "/v1/audit-exports", Some(s"""{"key":"$key","brand":"$brand","from":"$from","to":"$to"}"""), as)

  private def finished(key: String, as: Seq[(String, String)]): Reply =
    kit.eventually(60.seconds) {
      val r = kit.request("GET", s"/v1/audit-exports/$key", headers = as)
      Option.when(r.status == 200 && r.json.get("status").asText != Export.Running)(r)
    }

  private def lines(key: String, ext: String): Vector[String] =
    kit.testKit.storage.bucket(Export.Bucket).getBytes(s"goldvibe/$key.$ext").map(b => String(b._2, UTF_8)).get.linesIterator.toVector

  test("an export is a CSV of the brand's records in the period, in pseudonyms, with a URL signed for 15 minutes") {
    loaded()
    val key = kit.fresh("e")
    val officer = kit.asBrandCompliance("goldvibe")
    val started = exportAs(key, officer)
    assertEquals(started.status, 202, started.text)
    val done = finished(key, officer)
    assertEquals((done.json.get("status").asText, done.json.get("format").asText), ("done", "csv"))
    assert(done.json.get("url").asText.nonEmpty)
    val rows = lines(key, "csv")
    assertEquals(rows.head, Csv.header)
    val body = rows.tail
    assert(body.size >= 3 && body.forall(_.contains(",goldvibe,")), body.mkString("\n"))
    assert(body.forall(l => Instant.parse(l.takeWhile(_ != ',')).isBefore(to) && !Instant.parse(l.takeWhile(_ != ',')).isBefore(from)))
    assert(!body.exists(_.contains("p-export")), "a person's real id in the export")
    assertEquals(done.json.get("count").asLong, body.size.toLong)
  }

  test("the same key again answers the same export; another brand's officer cannot see it") {
    loaded()
    val key = kit.fresh("e")
    val officer = kit.asBrandCompliance("goldvibe")
    assertEquals(exportAs(key, officer).status, 202)
    finished(key, officer)
    val records = kit.own(Export.Action).size
    val again = exportAs(key, officer)
    assertEquals((again.status, again.json.get("status").asText), (202, "done"))
    Thread.sleep(500)
    assertEquals(kit.own(Export.Action).size, records)
    assertEquals(kit.request("GET", s"/v1/audit-exports/$key", headers = kit.asBrandCompliance("spinvibe")).status, 404)
    assertEquals(exportAs(kit.fresh("e"), officer, brand = "spinvibe").status, 403)
  }

  test("the owner sets the brand's format to JSON lines, and the next export is one record per line") {
    loaded()
    assertEquals(kit.request("PUT", "/v1/audit-exports/format/goldvibe", Some("""{"format":"jsonl"}"""), kit.asOwner()).status, 200)
    assertEquals(kit.request("PUT", "/v1/audit-exports/format/goldvibe", Some("""{"format":"xml"}"""), kit.asOwner()).status, 400)
    assertEquals(kit.request("PUT", "/v1/audit-exports/format/goldvibe", Some("""{"format":"csv"}"""), kit.asInstallationCompliance()).status, 403)
    try
      val key = kit.fresh("e")
      assertEquals(exportAs(key, kit.asInstallationCompliance()).status, 202)
      assertEquals(finished(key, kit.asInstallationCompliance()).json.get("format").asText, "jsonl")
      val rows = lines(key, "jsonl")
      assert(rows.nonEmpty && rows.forall(l => Reply.mapper.readTree(l).get("brand").asText == "goldvibe"))
      kit.eventually()(kit.own("audit.export-format").find(_.changes.exists(c => c.after.contains("jsonl"))))
    finally kit.request("PUT", "/v1/audit-exports/format/goldvibe", Some("""{"format":"csv"}"""), kit.asOwner()): Unit
  }

  test("an export is recorded at the request and at its completion, naming who exported what range") {
    loaded()
    val key = kit.fresh("e")
    val before = kit.own(Export.Action).map(_.key).toSet
    exportAs(key, kit.asBrandCompliance("goldvibe", "officer-x"))
    finished(key, kit.asBrandCompliance("goldvibe"))
    val made = kit.eventually()(Option(kit.own(Export.Action).filterNot(r => before(r.key))).filter(_.size >= 2))
    val statuses = made.flatMap(_.changes.find(_.field == "status").flatMap(_.after)) ++
      made.flatMap(_.changes.find(_.field == "count").map(_ => "done"))
    assertEquals(statuses.toSet, Set("running", "done"))
    assert(made.forall(r => r.actor != "officer-x" && r.brand == "goldvibe"))
    assert(made.forall(_.changes.exists(c => c.field == "range" && c.after.contains(s"$from..$to"))))
  }

  test("a stage of an export recorded again, as a retried step or a second request under the key writes it, writes no second record") {
    loaded()
    val key = kit.fresh("e")
    exportAs(key, kit.asBrandCompliance("goldvibe"))
    finished(key, kit.asBrandCompliance("goldvibe"))
    val state = kit.components.forWorkflow(Export.entityId(key)).call(Export.get).invoke()
    val calls = io.eitheror.casino.audit.ingest.IngestCalls.of(kit.components)
    def again(stage: String) =
      io.eitheror.casino.audit.own.Own.record(calls, state.brand, Export.Action, state.actor, "export", state.brand, "done", None,
        Vector.empty, Instant.parse("2026-11-09T10:00:00Z"), id = Some(s"export/$key/$stage"))
    // A name written twice is one record; a name already written with other content is kept out, never a second.
    val first = again("again")
    assertEquals(again("again").key, first.key)
    assertEquals(calls.keep(first).outcome, io.eitheror.casino.audit.store.Record.Same)
    assertEquals(calls.keep(again(Export.Running)).outcome, io.eitheror.casino.audit.store.Record.Collision)
  }

  test("a body that is not an export's shape, or a key that is not a name, is refused") {
    val officer = kit.asBrandCompliance("goldvibe")
    assertEquals(kit.request("POST", "/v1/audit-exports", Some("""{"key":"a/b","brand":"goldvibe"}"""), officer).status, 400)
    assertEquals(kit.request("POST", "/v1/audit-exports", Some("""{"key":"k1","brand":"nowhere"}"""), officer).status, 400)
    assertEquals(kit.request("GET", "/v1/audit-exports/none-such", headers = officer).status, 404)
  }
