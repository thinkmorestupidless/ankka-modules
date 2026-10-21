package io.eitheror.casino.pspgateway.port

import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.pspgateway.Json

import java.nio.file.Files

/**
 * One test per recording in tests/port/psp-gateway (constitution I, SC-001): each step replayed against
 * the whole service, psp-stub its providers, and its answer, provider calls and outcomes told compared
 * with the original's. A difference that is a declared divergence is named in the test's name, and must
 * still be there.
 */
class ParitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings(Parity.root.resolve("tests/port/psp-gateway"))

  if found.isEmpty then
    test("every recording of tests/port/psp-gateway replays as the original answered (T026 has not recorded them)".ignore) {}
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
 * The replay machinery against hand-made recordings in tests/fixtures/port/psp-gateway, shaped as the
 * gateway driver writes them, so the parity suite is known to catch a difference: the outcome told is
 * compared, though the replayer leaves an observe's answer alone.
 */
class ParityFixtureSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val fixtures = Parity.recordings(Parity.root.resolve("tests/fixtures/port/psp-gateway"))

  test("the fixtures exist") { assert(fixtures.nonEmpty) }

  fixtures.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)

    test(s"${recording.scenario} replays with no difference") {
      val differences = Parity.replay(recording)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
    }

    test(s"${recording.scenario}: an outcome told otherwise is reported as a difference") {
      val changed = Recording.parse(path, Json.mapper.readTree(Files.readString(path).replace("\"answered\": 200", "\"answered\": 400")))
        .fold(e => throw AssertionError(e), identity)
      val differences = Parity.replay(changed)
      assert(differences.exists(_.at.endsWith(".delivered")), differences.map(_.message).mkString("\n"))
    }

    test(s"${recording.scenario}: a provider call not made is reported as a difference") {
      val changed = Recording.parse(path, Json.mapper.readTree(Files.readString(path).replace("\"/api/v1/payments\"", "\"/api/v1/elsewhere\"")))
        .fold(e => throw AssertionError(e), identity)
      val differences = Parity.replay(changed)
      assert(differences.exists(_.at.contains(".calls")), differences.map(_.message).mkString("\n"))
    }
  }
