package io.eitheror.casino.publicapi.audit

import io.eitheror.casino.publicapi.keys.*
import io.eitheror.casino.publicapi.keys.KeyEvent.*

import java.time.Instant

/** T063: a key's changes as contracts/facts.md's `access_key.*` records; never the key or its digest. */
class KeyAuditSuite extends munit.FunSuite:
  private val at        = Instant.parse("2026-11-12T08:00:00Z")
  private val (id, raw) = RawKeys.mint()
  private val digest    = RawKeys.digest(raw)

  private def text(r: io.eitheror.casino.contract.facts.AuditRecord) = r.toString

  test("created names the operator, the key and its settings, as security") {
    val r = Audit.key("goldvibe", id, 1L, KeyCreated(id, "bi", digest, None, Set("phone", "email"), 600, "op-1", at))
    assertEquals((r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.recordClass), ("access_key.created", "operator", "op-1", "access_key", id, "security"))
    assertEquals(r.changes.find(_.field == "identifyingFields").flatMap(_.after), Some("email,phone"))
    assert(!text(r).contains(digest) && !text(r).contains(raw))
  }

  test("changed names each field changed, before and after") {
    val s = KeyState(id, "bi", Some(digest), enabled = true, deleted = false, None, Set.empty, 600, "op-1", Some(at))
    val c = Change(name = Some("bi-2"), requestsPerMinute = Some(60), by = "op-2", at = at)
    val r = Audit.key("goldvibe", id, 2L, KeyChanged(c.name, None, false, None, c.requestsPerMinute, "op-2", at, AccessKey.was(s, c)))
    assertEquals(r.changes.map(x => (x.field, x.before, x.after)).toSet: Set[(String, Option[String], Option[String])],
      Set[(String, Option[String], Option[String])](("name", Some("bi"), Some("bi-2")), ("requestsPerMinute", Some("600"), Some("60"))))
    assertEquals(r.actorId, "op-2")
  }

  test("disabled, enabled and deleted each a record of their own") {
    val records = Vector(KeyDisabled("op", at), KeyEnabled("op", at.plusSeconds(1)), KeyDeleted("op", at.plusSeconds(2))).zipWithIndex
      .map((e, i) => Audit.key("goldvibe", id, i + 2L, e))
    assertEquals(records.map(_.action), Vector("access_key.disabled", "access_key.enabled", "access_key.deleted"))
    assertEquals(records.map(_.recordId).distinct.size, 3)
  }

  test("two changes in the same millisecond are two records; a redelivered change is the same one") {
    val e = KeyDisabled("op", at)
    assertNotEquals(Audit.key("goldvibe", id, 4L, e).recordId, Audit.key("goldvibe", id, 6L, e).recordId)
    assertEquals(Audit.key("goldvibe", id, 4L, e).recordId, Audit.key("goldvibe", id, 4L, e).recordId)
  }
