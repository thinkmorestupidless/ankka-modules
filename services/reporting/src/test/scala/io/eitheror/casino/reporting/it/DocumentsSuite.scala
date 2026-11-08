package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.sdk.ErasureContext
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.documents.{Document, DocumentState, Documents, Erasure}

import java.time.LocalDate
import scala.concurrent.duration.*

/**
 * Documents against the whole service (US5; FR-021, FR-022, SC-006): each kind kept for its own lifetime from the brand's
 * settings, then no longer served while its record stays; a job's document expired by the sweep when its pause was
 * lost; and an erasure deleting every kept document naming the player, its own and an export listing them, while the
 * records stay and name the player by id only.
 */
class DocumentsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  private var d: LocalDate = null

  override def beforeAll(): Unit = d = kit.days()

  private val Reference = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})".r

  private def referenceOf(r: Reply): String =
    r.header("Content-Disposition").flatMap(Reference.findFirstIn).getOrElse(throw AssertionError(s"${r.status} ${r.text.take(300)}"))

  private def record(ref: String): DocumentState = Disputed.document(kit, ref)

  /** A job's reference, once its document is ready and the view holds it. */
  private def job(r: Reply): String =
    assertEquals(r.status, 202, r.text)
    val ref = r.json.path("reference").asText
    kit.eventually(s"$ref ready", 120.seconds)(Option.when(record(ref).status == Document.Ready)(()))
    Disputed.inView(kit, ref)
    ref

  private def expired(ref: String, by: String): DocumentState =
    kit.eventually(s"$ref expired by $by", 30.seconds)(Some(record(ref)).filter(s => s.status == Document.Expired && s.endedBy.contains(by)))

  test("each kind is kept for its own lifetime, then not served, and its record stays (FR-021)") {
    val before = kit.reporting
    kit.setReporting(_.copy(lifetimeHours = Map(Document.ReportExport -> 2, Document.DisputeReport -> 5)))
    try
      val p = Disputed(kit, d).usual().publish()
      val csv = kit.get(s"/reports/ggr?from=${Kit.SeedDay}&to=${Kit.SeedDay}&currency=EUR&format=csv", kit.asFinance)
      assertEquals(csv.status, 200, csv.text)
      val e = referenceOf(csv)
      val sync = referenceOf(p.bySession(format = "csv"))
      val queued = job(p.byRange(d.minusDays(7), d, "csv"))
      for ref <- Vector(e, sync) do Disputed.inView(kit, ref)
      def hours(ref: String) = java.time.Duration.between(record(ref).readyAt.get, record(ref).expiresAt.get).toHours
      assertEquals((hours(e), hours(sync), hours(queued)), (2L, 5L, 5L))
      kit.moveClock(2.hours + 1.minute)
      assertEquals(kit.get(s"/documents/$e", kit.asFinance).status, 410)
      assertEquals(kit.get(s"/documents/$sync", kit.asOperator).status, 200)
      assertEquals(kit.get(s"/documents/$queued", kit.asOperator).status, 200)
      val gone = expired(e, Document.Lifetime)
      assertEquals(kit.testKit.storage.default.getBytes(gone.objectName.get), None)
      assertEquals((gone.kind, gone.askedBy, gone.from, gone.to), (Document.ReportExport, "finance-1", Some(Kit.SeedDay.toString),
        Some(Kit.SeedDay.toString)))
      kit.moveClock(3.hours)
      for ref <- Vector(sync, queued) do
        assertEquals(kit.get(s"/documents/$ref", kit.asOperator).error, "document_expired")
        val r = expired(ref, Document.Lifetime)
        assertEquals(kit.testKit.storage.default.getBytes(r.objectName.get), None)
        assertEquals(r.subject, Some(p.player))
    finally kit.setReporting(_ => before)
  }

  test("a job's document whose pause was lost is expired by the sweep") {
    val p   = Disputed(kit, d.plusDays(1)).usual().publish()
    val ref = job(p.byRange(d.minusDays(6), d.plusDays(1), "csv"))
    val pause = s"ankka-pause-document-job-$ref"
    // Lost: the wake deleted (or, with the clock moved past the system's, already spent ending the job; see DocumentJob.expireStep).
    kit.timers.timerScheduler.delete(pause)
    assert(!kit.timers.timerScheduler.exists(pause))
    kit.moveClock(25.hours)
    val r = expired(ref, Document.Lifetime)
    assertEquals(kit.testKit.storage.default.getBytes(r.objectName.get), None)
    assertEquals(kit.testKit.storage.default.getBytes(Document.sidecar(r.objectName.get)), None)
  }

  private def handler(again: Boolean = false)(subject: String) = Erasure.handler.run(new ErasureContext:
    def client     = kit.components
    def secrets    = throw UnsupportedOperationException("not read")
    def services   = throw UnsupportedOperationException("not read")
    def storage    = kit.testKit.storage
    def erasureSeq = 1L
    def rerun      = again
  )(DataSubject(subject))

  test("an erasure deletes every kept document naming the player and keeps the records (FR-021, SC-006)") {
    val a = Disputed(kit, d.plusDays(3)).usual().publish()
    val b = Disputed(kit, d.plusDays(3)).usual().publish()
    val own   = referenceOf(a.bySession(format = "pdf"))
    val other = referenceOf(b.bySession(format = "csv"))
    val registered = d.plusDays(2)
    val listing = kit.get(s"/reports/players?from=$registered&to=$registered&currency=EUR&format=csv", kit.asFinance)
    assertEquals(listing.status, 200, listing.text)
    assert(listing.text.contains(a.player) && listing.text.contains(b.player), listing.text)
    val names = referenceOf(listing)
    val ggr   = referenceOf(kit.get(s"/reports/ggr?from=${Kit.SeedDay}&to=${Kit.SeedDay}&currency=EUR&format=csv", kit.asFinance))
    for ref <- Vector(own, other, names, ggr) do Disputed.inView(kit, ref)
    val sidecar = Document.sidecar(record(names).objectName.get)
    assertEquals(Disputed.text(kit, sidecar).map(_.split('\n').toSet), Some(Set(a.player, b.player)))
    // The installation's erasure: the keys destroyed, then the service's part.
    kit.testKit.erase(DataSubject(s"player/${a.player}")): Unit
    assertEquals(handler()(s"player/${a.player}").objectsErased, 3L, "the dispute report under the prefix, and two documents ended")
    for ref <- Vector(own, names) do
      val r = record(ref)
      assertEquals((r.status, r.endedBy), (Document.Expired, Some(Document.Erasure)), ref)
      assertEquals(kit.testKit.storage.default.getBytes(r.objectName.get), None)
      assertEquals(kit.get(s"/documents/$ref", kit.asFinance).error, "document_expired")
    assertEquals(Disputed.text(kit, sidecar), None)
    assertEquals(kit.testKit.storage.default.list(s"subjects/player%2F${a.player}/").size, 0)
    // The record stays, naming the player by id: who asked, what, for when.
    val kept = record(own)
    assertEquals((kept.kind, kept.askedBy, kept.subject, kept.parameters.get("sessionId")),
      (Document.DisputeReport, "op-1", Some(a.player), Some(a.logins.head.id)))
    // The other player's document, and an export naming no player, are still served.
    assertEquals(kit.get(s"/documents/$other", kit.asOperator).status, 200)
    assertEquals(kit.get(s"/documents/$ggr", kit.asFinance).status, 200)
    // Run again it ends nothing more; a subject that is not a player's is not this service's.
    kit.eventually("the view caught up")(Option.when(!Documents.keptNow(kit.views).exists(_.reference == own))(()))
    assertEquals(handler(again = true)(s"player/${a.player}").objectsErased, 0L)
    assertEquals(handler()("operator/op-1").objectsErased, 0L)
  }

  test("the erasure route, for players alone, does the same and answers what it ended") {
    val p   = Disputed(kit, d.plusDays(5)).usual().publish()
    val ref = referenceOf(p.bySession(format = "html"))
    Disputed.inView(kit, ref)
    val path = s"/erasure/players/${p.player}"
    assertNotEquals(kit.request("POST", kit.path(path), headers = kit.asOperator).status, 200)
    val r = kit.request("POST", kit.path(path), headers = kit.asService("players"))
    assertEquals(r.status, 200, r.text)
    assertEquals((r.json.path("service").asText, r.json.path("subject").asText, r.json.path("documents").asInt),
      ("reporting", s"player/${p.player}", 1))
    assertEquals(record(ref).endedBy, Some(Document.Erasure))
    kit.eventually("the view caught up")(Option.when(!Documents.keptNow(kit.views).exists(_.reference == ref))(()))
    assertEquals(kit.request("POST", kit.path(path), headers = kit.asService("players")).json.path("documents").asInt, 0)
  }

