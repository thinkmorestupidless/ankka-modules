package io.eitheror.casino.programme.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

/**
 * One test per recording in tests/port/programme, and per ledger read in tests/port/attribution
 * (contracts/recording.md), replayed through `Translate` on the shared kit. Fewer than the thirteen
 * programme recordings is a failure, not an empty pass: until T034 records them this suite fails.
 */
class ParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private def load(path: java.nio.file.Path) = Recording.load(path).fold(e => throw AssertionError(e), identity)

  private val replayed = java.util.concurrent.atomic.AtomicInteger()

  private val programme   = Parity.recordings(Parity.programme).map(load)
  private val ledgerReads = Parity.recordings(Parity.attribution).map(load).filter(Parity.ledgerReads)

  test("tests/port/programme holds the thirteen recordings of contracts/recording.md") {
    assertEquals(programme.size, 13, "tests/port/programme holds the thirteen recordings (T034)")
  }

  (programme ++ ledgerReads).foreach { recording =>
    test(s"${recording.path.getParent.getFileName}: ${recording.scenario}") {
      val differences = Parity.replay(recording)
      replayed.incrementAndGet(): Unit
      val (marked, unmarked) = differences.partition(Parity.divergence(recording)(_).isDefined)
      assert(unmarked.isEmpty, unmarked.map(_.message).mkString("\n"))
      marked.foreach(d => println(s"diverges by change ${Parity.divergence(recording)(d).get.change}: ${d.message}"))
    }
  }

  test("every marked divergence was needed by a replay of this run") {
    assume(replayed.get == programme.size + ledgerReads.size, "only when every replay ran")
    assertEquals(Parity.unused.map(d => s"${d.change}: ${d.why}"), Vector.empty)
  }
