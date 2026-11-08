package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class AuditEntrySuite extends munit.FunSuite with LogCapturing:
  test("written once and never changed") {
    val k = KeyValueEntityTestKit.of(AuditEntry, AuditEntry.newId())
    val a = Audit("aff-1", Some("goldvibe"), "membership.approved", "m-1", Instant.parse("2026-11-01T10:00:00Z"), Some("pending"), Some("active"))
    assertEquals(k.call(AuditEntry.write)(a).replyValue.entry, Some(a))
    assert(!k.call(AuditEntry.write)(a.copy(action = "x")).changed)
  }
