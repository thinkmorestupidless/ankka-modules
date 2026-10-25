package io.eitheror.casino.ingest.audit

import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.contract.facts.AuditRecord
import io.eitheror.casino.ingest.requests.*

import java.time.Instant

/** FR-019, research R9: a key or rate refusal is one audit record naming the sub-brand where known and never the key. */
class AuditFactsSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-22T10:00:00Z")
  private val kit = ConsumerTestKit.of(RequestAudit)

  private def refused(outcome: String, subBrand: Option[String]) =
    RecordState("rec-1", subBrand, Route.Create, Some(at), Some("t-1"), Some(5000), Some("EUR"), Some("GB"), outcome, ticket = Some("tok"))

  private def one(r: RecordState): AuditRecord =
    val produced = kit.onMessage(r, subject = r.recordId)
    assertEquals(produced.payloads.size, 1, produced.toString)
    assertEquals(produced.messages.head.metadata.get("ce-id"), Some(r.recordId))
    produced.payloads.head.asInstanceOf[AuditRecord]

  test("a missing key is one record: ingest, the route, an unknown actor, the request as subject, refused for key_missing") {
    val a = one(refused(Outcome.KeyMissing, None))
    assertEquals((a.service, a.action, a.actorKind, a.actorId, a.subjectKind, a.subjectId, a.outcome, a.reasonCode),
      ("ingest", "create", "unknown", "unknown", "request", "rec-1", "refused", Some("key_missing")))
  }

  test("an unknown key names no sub-brand; a disabled key and a rate refusal name theirs") {
    assertEquals(one(refused(Outcome.KeyUnknown, None)).actorKind, "unknown")
    val disabled = one(refused(Outcome.KeyDisabled, Some("sb-1")))
    assertEquals((disabled.actorKind, disabled.actorId), ("sub-brand", "sb-1"))
    val rate = one(refused(Outcome.Rate, Some("sb-1")))
    assertEquals((rate.actorKind, rate.actorId, rate.reasonCode), ("sub-brand", "sb-1", Some("rate")))
  }

  test("every other outcome is nothing") {
    Seq(Outcome.Served, Outcome.Repeated, Outcome.Refused, Outcome.Unanswered, Outcome.InvalidField, Outcome.RequestIdReused,
      Outcome.NotFound, Outcome.BodyTooLarge, Outcome.NotJson, Outcome.LostAnswer).foreach { o =>
      assertEquals(kit.onMessage(refused(o, Some("sb-1")), subject = "rec-1").payloads, Vector.empty, o)
    }
  }

  test("a later change of a record that is not a refusal of the key or the rate is nothing") {
    val lost = refused(Outcome.LostAnswer, Some("sb-1")).copy(lostAnswer = Some(LostAnswer(at, Some("tok"))), seen = true)
    assertEquals(kit.onMessage(lost, subject = "rec-1").payloads, Vector.empty)
  }

  test("the record's ticket and request id never appear, and no field could hold a key") {
    val text = kit.onMessage(refused(Outcome.KeyUnknown, None), subject = "rec-1").messages.head.text
    assert(!text.contains("tok") && !text.contains("t-1"), text)
    assert(!text.toLowerCase.contains("merchantkey"), text)
  }
