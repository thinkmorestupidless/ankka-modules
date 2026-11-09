package io.eitheror.casino.audit.exports

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import io.eitheror.casino.audit.AuditCodecs.given
import io.eitheror.casino.audit.stored.{Stored, StoredChange}

import java.time.Instant

/** An export's lines (research R10): CSV in FR-001's order, changes flattened; JSON lines as the store holds a record. */
class ExportLinesSuite extends munit.FunSuite:
  private val s = Stored(key = "brand:goldvibe/d-1", source = "brand:goldvibe", brand = "goldvibe", visibility = "brand",
    service = "wallet", action = "adjust", outcome = "accepted", reasonCode = Some("goodwill"), category = "money",
    occurredAt = Instant.parse("2026-11-09T07:00:00Z"), actorKind = "operator", actor = "a1b2", subjectKind = "player", subject = "c3d4",
    approver = None, changes = Vector(StoredChange("amount", None, Some("-500")), StoredChange("email", None, None),
      StoredChange("note", Some("one, \"two\""), Some("three"))),
    amount = Some(-500L), currency = Some("EUR"), correlation = "d-c", causation = "d-k", recordDigest = "d-1",
    arrivedAt = Instant.parse("2026-11-09T07:00:01Z"))

  test("the CSV header names the columns in FR-001's order") {
    assertEquals(Csv.header.split(',').take(8).toVector,
      Vector("occurredAt", "brand", "actorKind", "actor", "approver", "subjectKind", "subject", "service"))
    assertEquals(Csv.line(s).count(_ == ',') >= Csv.Columns.size - 1, true)
  }

  test("a line flattens the changes, a personal field written with its name alone, and quotes what needs it") {
    val line = Csv.line(s)
    assert(line.startsWith("2026-11-09T07:00:00Z,goldvibe,operator,a1b2,,player,c3d4,wallet,adjust,accepted,goodwill,"), line)
    assert(line.contains("\"amount:>-500;email:;note:one, \"\"two\"\">three\""), line)
    assert(line.endsWith(",-500,EUR,d-k,d-c,money,brand,brand:goldvibe,brand:goldvibe/d-1"), line)
  }

  test("a JSON line is the record as stored, and reads back as it") {
    val line = JsonLines.line(s)
    assert(!line.contains("\n"))
    assertEquals(readFromString[Stored](line), s)
  }
