package io.eitheror.casino.rewards.port

import io.eitheror.casino.contract.port.Recording

/**
 * One test per recording in tests/port/rewards (constitution I), named after its file: each step replayed against the
 * whole service and its answer compared field by field with the original's, a marked divergence excepted and required
 * to still differ. A step wire.md marks not carried is named in the test and answered as recorded; another service's
 * step is stood in for as [[Parity]] says.
 */
class ParitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val found = Parity.recordings()

  if found.isEmpty then test("tests/port/rewards holds the recordings T053 makes".ignore) {}

  found.foreach { path =>
    val recording  = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val diverges   = Parity.diverging(recording)
    val notCarried = Parity.notCarried(recording)
    val name = path.getFileName.toString.stripSuffix(".json") + diverges.map(d => s" (diverges: ${d.why})").mkString +
      notCarried.map(_._2).distinct.map(w => s" (not carried: $w)").mkString
    test(name) {
      val (marked, differences) = Parity.replay(recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)),
        s"${recording.scenario}: no longer differs from the original where ${d.why}"))
    }
  }
