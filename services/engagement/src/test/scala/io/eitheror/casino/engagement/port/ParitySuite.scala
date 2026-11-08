package io.eitheror.casino.engagement.port

import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.engagement.it.SharedKit

/**
 * One test per recording in tests/port/engagement (constitution I; SC-001), named after its file: the steps on
 * engagement's own routes compared field by field with the original's answers, the streams by their frames' events
 * and data keys, and the CRM calls as [[Parity]] compares them. A marked divergence is excepted and required to still
 * differ, and is named in the test.
 */
class ParitySuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")
  private lazy val kit = SharedKit.kit

  override def beforeAll(): Unit = kit.reset()

  private val found = Parity.recordings()

  if found.isEmpty then test("tests/port/engagement holds the recordings T051 makes".ignore) {}

  found.foreach { path =>
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val diverges  = Parity.diverging(recording)
    val name      = path.getFileName.toString.stripSuffix(".json") + diverges.map(d => s" (diverges: ${d.why})").distinct.mkString
    test(name) {
      val (marked, differences) = Parity.replay(kit, recording).partition(Parity.divergence(recording)(_).isDefined)
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
      diverges.foreach(d => assert(marked.exists(Parity.divergence(recording)(_).contains(d)),
        s"${recording.scenario}: no longer differs from the original where ${d.why}"))
    }
  }
