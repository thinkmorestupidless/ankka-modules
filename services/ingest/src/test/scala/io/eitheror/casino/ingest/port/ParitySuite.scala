package io.eitheror.casino.ingest.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.ingest.Json

import java.nio.file.Files

/**
 * One test per recording in tests/port/ingest (constitution I): each step replayed against the whole
 * service and its answer compared field by field with the original's. Fewer than the three recordings
 * copied from spec 004's run (T005) is a failure, not an empty pass.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings(Parity.root.resolve("tests/port/ingest"))

  test("tests/port/ingest holds at least the three recordings copied from spec 004's run") {
    assert(found.size >= 3, s"${found.size} recordings in tests/port/ingest")
  }

  found.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val diverges  = Parity.diverging(recording)
    test(recording.scenario + diverges.map(d => s" (diverges: ${d.why})").mkString) {
      val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)), s"${recording.scenario}: no longer differs from the original where ${d.why}"))
    }
  }

/**
 * The replay machinery against a hand-made recording in tests/fixtures/port/ingest, shaped as the copied
 * recordings are: the key reconstructed from the key step's `rawKey`, the create, submit and read through
 * the edge, and a difference reported when the service answers otherwise.
 */
class ParityFixtureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val fixtures = Parity.recordings(Parity.root.resolve("tests/fixtures/port/ingest"))

  test("the fixtures exist") { assert(fixtures.nonEmpty) }

  fixtures.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)

    test(s"${recording.scenario} replays with no difference") {
      assertEquals(Parity.replay(recording).map(_.message), Vector.empty)
    }

    test(s"${recording.scenario}: a create ingest refuses where the original served is reported as a difference") {
      val changed = Recording.parse(path, Json.mapper.readTree(Files.readString(path).replace("\"amountCents\": 1000", "\"amountCents\": 0")))
        .fold(e => throw AssertionError(e), identity)
      assert(Parity.replay(changed).exists(_.at.endsWith(".status")))
    }

    test(s"${recording.scenario}: a key the index does not hold is reported as a difference") {
      val changed = Recording.parse(path, Json.mapper.readTree(Files.readString(path).replace("\"rawKey\": \"eo_live_fixture", "\"rawKey\": \"eo_live_other")))
        .fold(e => throw AssertionError(e), identity)
      val keyless = changed.copy(steps = changed.steps.map(s =>
        if Parity.ours(s.request) then s.copy(request = s.request.copy(headers = s.request.headers + ("merchantKey" -> "eo_live_fixture_not_issued"))) else s))
      assert(Parity.replay(keyless).exists(_.at.endsWith(".status")))
    }
  }
