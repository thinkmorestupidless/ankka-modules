package io.eitheror.casino.players.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.players.it.SharedKit

/**
 * One test per recording in tests/port/players (constitution I), named after its file: each step
 * replayed against the whole service and its answer compared field by field with the original's. With
 * no recording yet (T029 makes them), the suite says so as an ignored test rather than passing empty.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings()

  if found.isEmpty then test("tests/port/players holds the recordings T029 makes".ignore) {}

  /**
   * Spec 018 SC-001: what a replay audits is one record per action and none twice. Every accepted
   * registration leaves exactly one `register` record, and no record id is published twice.
   */
  private def assertOneRecordEach(recording: Recording, before: Int): Unit =
    val registered = recording.steps.count(s => s.request.path.endsWith("/register") && s.response.status / 100 == 2)
    def records = SharedKit.kit.facts(PlatformAudit).drop(before).collect { case r: AuditRecord => r }
    val deadline = System.nanoTime() + 10_000_000_000L
    while records.count(_.action == "register") < registered && System.nanoTime() < deadline do Thread.sleep(100)
    Thread.sleep(300)
    val ids = records.map(_.recordId)
    assertEquals(ids.diff(ids.distinct), Vector.empty, s"${recording.scenario}: a record published twice")
    assertEquals(records.count(_.action == "register"), registered, s"${recording.scenario}: one record per registration")

  found.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val diverges  = Parity.diverging(recording)
    test(path.getFileName.toString.stripSuffix(".json") + diverges.map(d => s" (diverges: ${d.why})").mkString) {
      val auditedBefore         = SharedKit.kit.facts(PlatformAudit).size
      val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      assertOneRecordEach(recording, auditedBefore)
      diverges.foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)),
        s"${recording.scenario}: no longer differs from the original where ${d.why}"))
    }
  }
