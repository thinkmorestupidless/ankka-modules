package io.eitheror.casino.publicapi.audit

import io.eitheror.casino.publicapi.keys.{Disclosed, KeyLog, Served}

import java.time.Instant

/** T063, a port of the original's `audit.test.ts`: contracts/facts.md's *Published* reads, record by record. */
class AuditSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-12T08:00:00Z")
  private val p1 = "11111111-1111-1111-1111-111111111111"
  private val p2 = "22222222-2222-2222-2222-222222222222"

  private def served(status: Int, players: Vector[Disclosed] = Vector.empty) =
    Served("r-1", "GET /v1/players/{playerId} playerId=" + p1, status, KeyLog.outcome(status), 12, players, at)

  test("a request is one public_api.read: actor and subject the key, the operation, status, duration and fields served per player") {
    val Vector(r) = Audit.read("goldvibe", "k1", served(200, Vector(Disclosed(p1, Vector.empty))))
    assertEquals((r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId), ("public_api.read", "access_key", "k1", "access_key", "k1"))
    assertEquals((r.outcome, r.recordClass, r.service, r.brand, r.occurredAt), ("done", "system", "public-api", "goldvibe", at))
    val changes = r.changes.map(c => c.field -> c.after).toMap
    assertEquals(changes("status"), Some("200"))
    assertEquals(changes("duration_ms"), Some("12"))
    assert(changes("operation").exists(_.startsWith("GET /v1/players/{playerId}")))
    assertEquals(changes(s"player:$p1"), Some(""))
  }

  test("each player whose identifying fields were served is the subject of public_api.identifying_fields_served") {
    val records = Audit.read("goldvibe", "k1", served(200, Vector(Disclosed(p1, Vector("email")), Disclosed(p2, Vector("email", "phone")))))
    val subjects = records.filter(_.action == "public_api.identifying_fields_served").map(r => r.subjectId -> r.changes.find(_.field == "fields").flatMap(_.after))
    assertEquals(subjects, Vector(p1 -> Some("email"), p2 -> Some("email,phone")))
    assert(records.forall(r => r.actorKind == "access_key" && r.actorId == "k1"))
    assertEquals(records.map(_.recordId).distinct.size, 3)
  }

  test("a player served no identifying field makes no disclosure record") {
    assertEquals(Audit.read("goldvibe", "k1", served(200, Vector(Disclosed(p1, Vector.empty)))).size, 1)
  }

  test("the outcome follows the status: refused for 4xx, failed for 5xx, so a key's error rate is its records' outcomes (FR-038)") {
    assertEquals(Vector(200, 401, 429, 503).map(s => Audit.read("goldvibe", "k1", served(s)).head.outcome), Vector("done", "refused", "refused", "failed"))
  }

  test("a redelivered event is the same record") {
    assertEquals(Audit.read("goldvibe", "k1", served(200)).map(_.recordId), Audit.read("goldvibe", "k1", served(200)).map(_.recordId))
  }
