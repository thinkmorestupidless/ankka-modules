package io.eitheror.casino.reporting.port

import io.eitheror.casino.contract.port.Recording

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Offline, with no kit: every recording of tests/port/reporting but the seed and the suite's result is replayed by
 * exactly one parity test, named after it; each loads and was made on two runs of the original; every divergence is
 * reached by a recording it names; and tests/port/unclaimed.txt lists none of them.
 */
class CoverageSuite extends munit.FunSuite:
  private val claimed = ReportsParity.recordings ++ DashboardParity.recordings ++ DisputeParity.recordings

  test("tests/port/reporting holds the 31 recordings of contracts/recording.md") {
    assertEquals(Parity.names.size, 31)
  }

  test("every recording is replayed by one parity test, and every test's recording exists") {
    assertEquals(Parity.names.filterNot(claimed.contains), Vector.empty, "recordings no parity test replays")
    assertEquals(claimed.filterNot(Parity.names.contains), Vector.empty, "tests whose recording does not exist")
    assertEquals(claimed.diff(claimed.distinct), Vector.empty, "recordings replayed twice")
  }

  test("every recording loads, and was made on two runs of the original") {
    val loaded = Parity.names.map(n => n -> Recording.load(Parity.directory.resolve(s"$n.json")))
    assertEquals(loaded.flatMap(_._2.left.toOption), Vector.empty)
    assertEquals(loaded.collect { case (n, Right(r)) if r.runs < 2 => n }, Vector.empty)
  }

  test("every divergence names recordings that exist, and says where it is decided") {
    val stray = Divergences.all.flatMap(_.recordings).filterNot(Parity.names.contains)
    assertEquals(stray, Vector.empty)
    assertEquals(Divergences.all.filter(_.decided.isEmpty).map(_.why), Vector.empty)
  }

  test("tests/port/unclaimed.txt lists no reporting recording") {
    val lines = Files.readAllLines(Parity.root.resolve("tests/port/unclaimed.txt")).asScala.map(_.trim)
    assertEquals(lines.filter(_.startsWith("tests/port/reporting/")).toVector, Vector.empty)
  }
