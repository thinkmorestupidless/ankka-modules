package io.eitheror.casino.payouts.port

import io.eitheror.casino.contract.port.Recording

/**
 * One test per recording in tests/port/payouts (constitution I), named after its file: each step replayed
 * against the whole service and its answer compared field by field with the original's, a marked divergence
 * excepted and required to still differ.
 */
class ParitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings()

  if found.isEmpty then test("tests/port/payouts holds the recordings T053 makes".ignore) {}

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
