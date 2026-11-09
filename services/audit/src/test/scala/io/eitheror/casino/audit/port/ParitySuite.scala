package io.eitheror.casino.audit.port

import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.it.Kit
import io.eitheror.casino.audit.registry.RegistryKey

import java.time.Instant
import scala.concurrent.duration.*

/**
 * What the original's audit demonstrates (contracts/recording.md; tests/port/audit/README.md), held
 * against this one. The original's query is internal and its tests run a CLI over a stub, so rows 2–5
 * are translated from those tests' fixtures: each fixture's records are written in a brand and asked
 * for through `GET /v1/audit-records`. The marked divergences: the original answers newest first and
 * pages by `hasMore`, this oldest first with a cursor (FR-013); its actor is the twin, here who acted.
 */
class ParitySuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  private def loaded(corr: String, n: Int): Unit =
    val digest = Ids.digest(RegistryKey.required(kit.secrets), "goldvibe", corr)
    kit.eventually(30.seconds)(Option.when(kit.records.count(_.correlation == digest) >= n)(()))

  test("row 2, TestRunAuditQuery_empty: a query that matches nothing answers no rows and no cursor") {
    val r = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "correlation" -> kit.fresh("nothing"))
    assertEquals(r.status, 200, r.text)
    assertEquals(r.records, Vector.empty)
    assert(r.json.get("next").isNull)
  }

  test("row 3, TestRunAuditQuery_events: each matching record is answered with its actor, action and subject (diverges: actor is who acted, oldest first)") {
    val corr = kit.fresh("c")
    // The fixture's two events: a person shipped pipe-001, a service approved pipe-002.
    kit.publish("goldvibe", Kit.record(action = "pipeline.shipped", actorKind = "operator", actorId = "admin@eitheror.io",
      subjectKind = "pipeline", subjectId = "pipe-001", correlationId = corr, occurredAt = Instant.parse("2026-11-09T07:00:00Z")))
    kit.publish("goldvibe", Kit.record(action = "pipeline.approved", actorKind = "service", actorId = "epipe",
      subjectKind = "pipeline", subjectId = "pipe-002", correlationId = corr, category = "operator",
      occurredAt = Instant.parse("2026-11-09T07:01:00Z")))
    loaded(corr, 2)
    val r = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "correlation" -> corr)
    assertEquals(r.records.size, 2)
    val first = r.records.head
    assertEquals((first.get("action").asText, first.get("subject").asText), ("pipeline.shipped", "pipe-001"))
    // The person is answered by a pseudonym, never as written; the service as written.
    assertNotEquals(first.get("actor").asText, "admin@eitheror.io")
    assertEquals(r.records(1).get("actor").asText, "epipe")
  }

  test("row 4, TestRunAuditQuery_filterPipelineKind: a query by action and since answers only those") {
    val corr = kit.fresh("c")
    kit.publish("goldvibe", Kit.record(action = "pipeline.shipped", subjectKind = "pipeline", subjectId = "pipe-003",
      correlationId = corr, occurredAt = Instant.parse("2026-11-09T07:30:00Z")))
    kit.publish("goldvibe", Kit.record(action = "pipeline.approved", subjectKind = "pipeline", subjectId = "pipe-003",
      correlationId = corr, occurredAt = Instant.parse("2026-11-09T07:31:00Z")))
    kit.publish("goldvibe", Kit.record(action = "pipeline.shipped", subjectKind = "pipeline", subjectId = "pipe-003",
      correlationId = corr, occurredAt = Instant.parse("2026-11-09T06:00:00Z")))
    loaded(corr, 3)
    // The fixture's `since: 1h` at the kit's 08:00.
    val r = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "correlation" -> corr, "action" -> "pipeline.shipped",
      "from" -> "2026-11-09T07:00:00Z", "limit" -> "10")
    assertEquals(r.records.map(_.get("action").asText), Vector("pipeline.shipped"))
    assertEquals(r.records.head.get("occurredAt").asText, "2026-11-09T07:30:00Z")
  }

  test("row 5, TestAuditQuery_HTTP: the answer's records are under records (diverges: a cursor, not hasMore)") {
    val corr = kit.fresh("c")
    kit.publish("goldvibe", Kit.record(correlationId = corr))
    loaded(corr, 1)
    val r = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "correlation" -> corr, "limit" -> "10")
    assertEquals(r.records.size, 1)
    assert(r.json.has("next") && !r.json.has("hasMore"), r.text)
  }
