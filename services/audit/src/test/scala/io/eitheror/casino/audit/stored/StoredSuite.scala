package io.eitheror.casino.audit.stored

import io.eitheror.casino.audit.ids.RegistryKeyBytes
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, ModuleChange}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

import java.time.Instant
import java.util.UUID

class StoredSuite extends ScalaCheckSuite:

  private val key = RegistryKeyBytes(Array.tabulate[Byte](32)(i => (i * 3).toByte))
  private val at  = Instant.parse("2026-11-09T10:00:00Z")
  private val pseudonyms = scala.collection.mutable.Map.empty[(String, String), String]
  private def pseudonym(kind: String, id: String) = pseudonyms.getOrElseUpdate(kind -> id, s"ps-${UUID.randomUUID()}")

  private def record(
      actorKind: String = "operator", actorId: String = "op-1", subjectKind: String = "player", subjectId: String = "p-1",
      approverId: Option[String] = None, changes: Vector[AuditChange] = Vector.empty, service: String = "wallet",
      action: String = "wallet.adjust", category: String = "money", brand: String = "goldvibe") =
    AuditRecord(s"wallet/$subjectId/EUR/k-1", brand, service, action, actorKind, actorId, approverId, subjectKind, subjectId,
      "done", None, changes, s"corr/$subjectId", s"cause/$actorId", category, at)

  test("people by pseudonyms; record, correlation and causation ids by digests; brand visibility from a brand's topic") {
    val s = Stored.from(record(approverId = Some("op-2")), Source.Brand("goldvibe"), pseudonym, key, at)
    assertEquals(s.actor, pseudonym("operator", "op-1"))
    assertEquals(s.subject, pseudonym("player", "p-1"))
    assertEquals(s.approver, Some(pseudonym("operator", "op-2")))
    Vector(s.recordDigest, s.correlation, s.causation).foreach(d => assert(d.matches("d-[0-9a-f]{32}"), d))
    assert(s.key.startsWith("brand:goldvibe/d-"), s.key)
    assertEquals((s.source, s.brand, s.visibility, s.category), ("brand:goldvibe", "goldvibe", "brand", "money"))
  }

  test("the same correlation at one brand joins across its sources; the record's own id is unique within its project") {
    val brand    = Stored.from(record(), Source.Brand("goldvibe"), pseudonym, key, at)
    val payments = Stored.from(record(), Source.Payments, pseudonym, key, at)
    assertEquals(brand.correlation, payments.correlation)
    assertNotEquals(brand.recordDigest, payments.recordDigest)
    assertEquals(payments.visibility, "brand")
    assert(payments.key.startsWith("payments/d-"))
  }

  test("an id that is not a person's is kept as written, unless it carries a person's real id") {
    val market = Stored.from(record(subjectKind = "market", subjectId = "m-9"), Source.Brand("goldvibe"), pseudonym, key, at)
    assertEquals(market.subject, "m-9")
    val built = Stored.from(record(actorKind = "service", actorId = "wallet/p-1"), Source.Brand("goldvibe"), pseudonym, key, at)
    assert(built.actor.startsWith("d-"), built.actor)
  }

  test("amount and currency from the record's changes, in minor units") {
    val s = Stored.from(record(changes = Vector(AuditChange("amount", None, Some("2500")), AuditChange("currency", None, Some("EUR")),
      AuditChange("email", None, None))), Source.Brand("goldvibe"), pseudonym, key, at)
    assertEquals((s.amount, s.currency), (Some(2500L), Some("EUR")))
    assertEquals(s.changes.last, StoredChange("email", None, None))
  }

  test("an erasure request's state on a brand's topic is an installation record") {
    val s = Stored.from(record(service = "players", action = "erasure.applied", category = "system"), Source.Brand("goldvibe"), pseudonym, key, at)
    assertEquals(s.visibility, "installation")
  }

  test("a module change is an installation record about the brand, by the platform") {
    val s = Stored.fromModule(ModuleChange("casino", "upgraded", Some("0.3.0"), Some("stable"), "vendor:agent", at), "goldvibe", key, at)
    assertEquals((s.source, s.brand, s.visibility, s.service, s.action, s.actorKind, s.subjectKind, s.subject, s.category),
      ("modules:goldvibe", "goldvibe", "installation", "casino", "module.upgraded", "platform", "brand", "goldvibe", "system"))
    assertEquals(s.changes, Vector(StoredChange("version", None, Some("0.3.0")), StoredChange("channel", None, Some("stable"))))
    assertEquals(Stored.fromModule(ModuleChange("casino", "upgraded", Some("0.3.0"), Some("stable"), "vendor:agent", at), "goldvibe", key, at).key, s.key)
  }

  // Real ids carry a prefix no fixed field has, so a match anywhere in the output is a leak.
  private val realId = Gen.listOfN(10, Gen.alphaNumChar).map(cs => s"pid-${cs.mkString}")
  private val kinds  = Gen.oneOf("player", "operator", "affiliate", "agent", "service", "system", "market")

  property("SC-002: no real id of a person the record names appears anywhere in what is stored") {
    Prop.forAll(realId, realId, Gen.option(realId), kinds, kinds, Gen.oneOf(Source.Brand("goldvibe"), Source.Payments)) {
      (actor, subject, approver, actorKind, subjectKind, source) =>
        val r = AuditRecord(s"wallet/$subject/EUR/$actor", "goldvibe", "wallet", "wallet.adjust", actorKind, actor, approver,
          subjectKind, subject, "done", Some("manual"), Vector(AuditChange("target", Some(s"$subject/bonus"), Some(s"$subject/cash")),
            AuditChange("amount", None, Some("100"))), s"$subject/$actor", s"cmd/$actor", "money", at)
        val stored = Stored.from(r, source, pseudonym, key, at).toString
        val people = Stored.people(r).map(_._2)
        people.forall(id => !stored.contains(id))
    }
  }
