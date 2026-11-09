package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.audit.ids.RegistryKeyBytes
import io.eitheror.casino.audit.stored.Source
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, ModuleChange, PlatformAudit, PlatformAuditFact, PlatformModules}

import java.time.{Duration, Instant}

class IngestUnitSuite extends munit.FunSuite with LogCapturing:
  private val now  = Instant.parse("2026-11-09T10:00:00Z")
  private val keys = IngestKeys(RegistryKeyBytes(Array.fill[Byte](32)(3)), RegistryKeyBytes(Array.fill[Byte](32)(4)), Duration.ofMinutes(5))

  private def record(id: String = "wallet/p-1/EUR/k-1", brand: String = "goldvibe", actorKind: String = "operator",
      actorId: String = "op-1", category: String = "money", outcome: String = "done", changes: Vector[AuditChange] = Vector.empty,
      occurredAt: Instant = now.minusSeconds(1)) =
    AuditRecord(id, brand, "wallet", "wallet.adjust", actorKind, actorId, None, "player", "p-1", outcome, None, changes,
      "corr/p-1", "cause/k-1", category, occurredAt)

  private def bytes(r: AuditRecord) = PlatformAudit.serializer.toBytes(r: PlatformAuditFact)
  private def ingest(calls: KitCalls, r: AuditRecord, source: Source = Source.Brand("goldvibe")) =
    Ingest.record(source, bytes(r), now, calls, keys)

  test("an operator's action is kept once, its people pseudonymised and bound; a redelivery changes nothing") {
    val calls = KitCalls()
    assertEquals(ingest(calls, record()), Outcome.Kept)
    assertEquals(ingest(calls, record()), Outcome.Same)
    val stored = calls.stored
    assertEquals(stored.size, 1)
    assertEquals(calls.identity(stored.head.actor).realId, "op-1")
    assertEquals(calls.identity(stored.head.subject).realId, "p-1")
    assertEquals(calls.counts("brand:goldvibe").stored, 1L)
  }

  test("the same person has one pseudonym across records at one brand, and another at another brand") {
    val calls = KitCalls()
    ingest(calls, record(id = "a")): Unit
    ingest(calls, record(id = "b")): Unit
    ingest(calls, record(id = "c", brand = "spinvibe"), Source.Brand("spinvibe")): Unit
    val bySubject = calls.stored.groupBy(_.brand).view.mapValues(_.map(_.subject).toSet).toMap
    assertEquals(bySubject("goldvibe").size, 1)
    assertNotEquals(bySubject("goldvibe"), bySubject("spinvibe"))
  }

  test("a money event no person caused is passed over and counted, not stored (FR-008a)") {
    val calls = KitCalls()
    assertEquals(ingest(calls, record(actorKind = "service", actorId = "wallet")), Outcome.PassedOver)
    assertEquals(calls.stored, Vector.empty)
    assertEquals(calls.counts("brand:goldvibe").passedOver, 1L)
    assertEquals(calls.pseudonyms.size, 0)
  }

  test("each quarantine reason: undecodable, another brand's record, a personal value, a collision") {
    val calls = KitCalls()
    assertEquals(Ingest.record(Source.Brand("goldvibe"), "{nope".getBytes, now, calls, keys), Outcome.Held("undecodable"))
    assertEquals(ingest(calls, record(brand = "spinvibe")), Outcome.Held("brand_mismatch"))
    assertEquals(ingest(calls, record(changes = Vector(AuditChange("email", Some("a@b"), None)))), Outcome.Held("personal_value"))
    assertEquals(ingest(calls, record(changes = Vector(AuditChange("email", None, None)))), Outcome.Kept)
    assertEquals(ingest(calls, record(outcome = "refused", changes = Vector(AuditChange("email", None, None)))), Outcome.Held("collision"))
    assertEquals(calls.quarantined.values.map(_.call(Quarantined.get).replyValue.why).toSet,
      Set("undecodable", "brand_mismatch", "personal_value", "collision"))
  }

  test("a pseudonym bound to someone else holds the message, reported, and the source goes on") {
    val calls     = KitCalls()
    val pseudonym = calls.issue(io.eitheror.casino.audit.ids.Ids.registryId(keys.registry, "goldvibe", "player", "p-1"))
    calls.bind(pseudonym, io.eitheror.casino.audit.registry.Bind("goldvibe", "player", "someone-else")): Unit
    assertEquals(ingest(calls, record(id = "conflicted")), Outcome.Held(Quarantined.PseudonymConflict))
    assertEquals(calls.stored.size, 0)
    assertEquals(calls.quarantined.size, 1)
    // The next message, about another player, is kept.
    val next = record(id = "next").copy(subjectId = "p-2")
    assertEquals(ingest(calls, next), Outcome.Kept)
  }

  test("payments' records name any brand, or payments") {
    val calls = KitCalls()
    assertEquals(ingest(calls, record(brand = "payments", id = "merchant/m-1"), Source.Payments), Outcome.Kept)
    assertEquals(ingest(calls, record(brand = "spinvibe", id = "merchant/m-2"), Source.Payments), Outcome.Kept)
    assertEquals(calls.stored.map(_.brand).toSet, Set("payments", "spinvibe"))
  }

  test("a record occurring further after its arrival than the bound allows is counted as skewed") {
    val calls = KitCalls()
    ingest(calls, record(id = "behind", occurredAt = now.minus(Duration.ofMinutes(30)))): Unit
    ingest(calls, record(occurredAt = now.plus(Duration.ofMinutes(6)))): Unit
    assertEquals(calls.counts("brand:goldvibe").skewed, 1L)
    // The lag is still measured from the record that was behind, not hidden by the one ahead.
    assertEquals(calls.counts("brand:goldvibe").lastOccurred, Some(now.minus(Duration.ofMinutes(30))))
  }

  test("a module change is an installation record about the brand") {
    val calls  = KitCalls()
    val change = PlatformModules.serializer.toBytes(ModuleChange("casino", "enabled", Some("0.3.0"), Some("stable"), "vendor", now))
    assertEquals(Ingest.module("goldvibe", change, now, calls, keys), Outcome.Kept)
    assertEquals(Ingest.module("goldvibe", change, now, calls, keys), Outcome.Same)
    assertEquals(calls.stored.map(s => (s.source, s.visibility, s.action)), Vector(("modules:goldvibe", "installation", "module.enabled")))
  }
