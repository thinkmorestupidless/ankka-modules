package io.eitheror.casino.kyc.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

/**
 * One test per recording in tests/port/kyc (constitution I), named after its file: each step replayed
 * against the whole service and its answer compared field by field with the original's, a marked
 * divergence where plan.md's *Divergences* says this service answers otherwise.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings()

  if found.isEmpty then test("tests/port/kyc holds the recordings T047 makes".ignore) {}

  found.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val diverges  = Parity.diverging(recording)
    test(path.getFileName.toString.stripSuffix(".json") + diverges.map(d => s" (diverges: ${d.why})").mkString) {
      val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)),
        s"${recording.scenario}: no longer differs from the original where ${d.why}"))
    }
  }
