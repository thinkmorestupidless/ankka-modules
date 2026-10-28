package io.eitheror.casino.attribution.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

/**
 * One test per recording in tests/port/attribution (constitution I), replayed through `Translate` on the
 * shared kit. Fewer than the twelve contracts/recording.md names (the suite's four and the driver's eight)
 * is a failure, not an empty pass: until T035 records them this suite fails.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings(Parity.directory)

  test("tests/port/attribution holds the twelve recordings of contracts/recording.md") {
    assert(found.size >= 12, s"${found.size} recordings in tests/port/attribution (T035 records them)")
  }

  found.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val diverges  = Parity.diverging(recording)
    test(recording.scenario + diverges.map(d => s" (diverges: ${d.why})").mkString) {
      val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.filter(_.observable).foreach(d =>
        assert(marked.exists(Parity.divergence(recording)(_).contains(d)), s"${recording.scenario}: no longer differs from the original where ${d.why}"))
    }
  }
