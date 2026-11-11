package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, PlatformAudit}
import io.eitheror.casino.reporting.documents.Document

import scala.concurrent.duration.*

/**
 * FR-026 (contracts/facts.md, *The audit record*): every document, each report's export as much as a dispute report, a
 * synchronous one and a job's, makes `report.generated` once when it is ready and `report.served` each time it is
 * served, on `platform.audit`, naming the operator, the reference, the kind and the period, and no person.
 */
class AuditSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  private val Reference = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})".r

  private def referenceOf(r: Reply): String =
    assertEquals(r.status, 200, r.text.take(300))
    r.header("Content-Disposition").flatMap(Reference.findFirstIn).getOrElse(throw AssertionError(r.headers.toString))

  private def records(reference: String): Vector[AuditRecord] =
    kit.broker.publishedTo(PlatformAudit.name).toVector
      .flatMap(m => scala.util.Try(PlatformAudit.serializer.fromBytes(m.message.payload)).toOption)
      .collect { case r: AuditRecord if r.subjectId == reference => r }

  /** The records of a document once `n` of them are published, in order. */
  private def awaited(reference: String, n: Int): Vector[AuditRecord] =
    kit.eventually(s"$n records of $reference")(Some(records(reference)).filter(_.size >= n))

  private def held(r: AuditRecord, action: String, by: String, kind: String, period: String): Unit =
    assertEquals((r.service, r.action, r.actorKind, r.actorId, r.subjectKind, r.outcome, r.recordClass, r.brand),
      ("reporting", action, "operator", by, "document", "ok", "operational", kit.brand), r.toString)
    assertEquals(r.changes, Vector(AuditChange("kind", None, Some(kind)), AuditChange("period", None, Some(period))))
    assertEquals(r.reasonCode, None)

  test("every report's export makes report.generated and report.served (FR-026)") {
    val d = kit.days()
    val p = Disputed(kit, d).usual().publish()
    val seed = Kit.SeedDay
    val registered = d.minusDays(1)
    val exports = Vector(
      s"/reports/ggr?from=$seed&to=$seed&currency=EUR&format=csv"                       -> s"$seed/$seed",
      s"/reports/players?from=$registered&to=$registered&currency=EUR&format=csv"       -> s"$registered/$registered",
      s"/reports/transactions/export?from=$d&to=$d&format=csv"                          -> s"$d/$d",
      s"/reports/bonus?from=$seed&to=$seed&currency=EUR&format=csv"                     -> s"$seed/$seed",
      s"/reports/psp?from=$seed&to=$seed&currency=EUR&format=csv"                       -> s"$seed/$seed")
    for (path, period) <- exports do
      val ref = referenceOf(kit.get(path, kit.asFinance))
      val Vector(generated, served) = awaited(ref, 2): @unchecked
      held(generated, "report.generated", "finance-1", Document.ReportExport, period)
      held(served, "report.served", "finance-1", Document.ReportExport, period)
      assertEquals(generated.recordId, s"audit/document/$ref/2", "the record's second event, its readying")
      assertNotEquals(generated.recordId, served.recordId)
    // No person on any of them.
    val all = kit.broker.publishedTo(PlatformAudit.name).toVector.map(m => String(m.message.payload, "UTF-8"))
    assert(!all.exists(_.contains(p.email)), "an audit record names no person")
  }

  test("a dispute report, synchronous or a job's, is audited when ready and at each serving") {
    val d = kit.days()
    val p = Disputed(kit, d).usual().publish()
    val l = p.logins.head
    val sync = referenceOf(p.bySession(format = "csv"))
    val Vector(g1, s1) = awaited(sync, 2): @unchecked
    val day = l.started.atZone(kit.zone).toLocalDate
    held(g1, "report.generated", "op-1", Document.DisputeReport, s"$day/$day")
    held(s1, "report.served", "op-1", Document.DisputeReport, s"$day/$day")
    val queued = p.byRange(d.minusDays(7), d, "csv")
    assertEquals(queued.status, 202, queued.text)
    val ref = queued.json.path("reference").asText
    val generated = awaited(ref, 1).head
    held(generated, "report.generated", "op-1", Document.DisputeReport, s"${d.minusDays(7)}/$d")
    assertEquals(records(ref).size, 1, "not served until it is asked for")
    // Served twice, by two operators: a record for each.
    assertEquals(Disputed.served(kit, ref, kit.asOperator).status, 200)
    assertEquals(kit.get(s"/documents/$ref", kit.asStaff(Nil, "op-2")).status, 200)
    val after = awaited(ref, 3)
    assertEquals(after.drop(1).map(r => (r.action, r.actorId)), Vector("report.served" -> "op-1", "report.served" -> "op-2"))
  }
