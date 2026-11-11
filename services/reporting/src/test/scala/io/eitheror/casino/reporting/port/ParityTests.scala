package io.eitheror.casino.reporting.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * One test per recording `recordings` names, named after its file (constitution I): its steps replayed on the shared
 * world and every difference from the original's shape either a declared divergence, which must still differ, or a
 * failure. A pending divergence is named in the test, for the person to decide.
 */
abstract class ParityTests(val recordings: Vector[String]) extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  recordings.foreach { name =>
    val diverges = Parity.diverging(name)
    test(name + diverges.map(d => s" (${d.label})").mkString) {
      val (marked, differences) = Replayed.replay(name).partition(Parity.divergence(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.foreach(d =>
        assert(marked.exists(Parity.divergence(_).contains(d)), s"$name no longer differs from the original where ${d.why}"))
    }
  }
