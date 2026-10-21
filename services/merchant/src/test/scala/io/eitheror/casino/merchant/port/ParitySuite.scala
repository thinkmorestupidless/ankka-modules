package io.eitheror.casino.merchant.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.merchant.Json

import java.nio.file.Files

/**
 * One test per recording in tests/port/merchant (constitution I, SC-001, FR-035): each step replayed
 * against the whole service and its answer compared field by field with the original's. Until the
 * recording run (T019) has been made, the suite reports itself ignored, never passed.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings(Parity.root.resolve("tests/port/merchant"))

  if found.isEmpty then
    test("every recording of tests/port/merchant replays as the original answered (T019 has not recorded them)".ignore) {}
  else
    found.foreach { path =>
      val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
      val diverges  = Parity.diverging(recording)
      test(recording.scenario + diverges.map(d => s" (diverges: ${d.why})").mkString) {
        val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
        assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
        // A marked divergence is demonstrated, not only tolerated: the service answers differently there.
        diverges.foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)), s"${recording.scenario}: no longer differs from the original where ${d.why}"))
      }
    }

/**
 * The replay machinery against hand-made recordings in tests/fixtures/port/merchant, shaped as the merchant
 * driver writes them, so the parity suite is known to work before the first real recording: the seed stood
 * in for, the key resolved by its digest, the capture sent as the gateway, and a difference reported.
 */
class ParityFixtureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val fixtures = Parity.recordings(Parity.root.resolve("tests/fixtures/port/merchant"))

  test("the fixtures exist") { assert(fixtures.nonEmpty) }

  fixtures.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)

    test(s"${recording.scenario} replays with no difference but the declared divergences") {
      val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      Parity.diverging(recording).foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)), s"no longer differs where ${d.why}"))
    }

    test(s"${recording.scenario}: a changed expectation is reported as a difference") {
      val changed = Recording.parse(path, Json.mapper.readTree(Files.readString(path).replace("\"AWAITING_REDIRECT\"", "\"FAILED\"")))
        .fold(e => throw AssertionError(e), identity)
      val differences = Parity.replay(changed)
      assert(differences.exists(_.at.endsWith(".status")), differences.map(_.message).mkString("\n"))
    }
  }
