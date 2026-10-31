package io.eitheror.casino.kyc.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

import java.nio.file.Files

/**
 * tests/port/kyc's recordings and plan.md's *Divergences*: each recording loads and was made on two runs
 * of the original; every divergence `Parity` names is a row of the table and reached by a recording; a row
 * no recording can reach (its *Demonstrated by* says "no recording" or names no recording) is reported
 * ignored as unhit.
 */
class CoverageSuite extends munit.FunSuite with LogCapturing:
  private val paths           = Parity.recordings()
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  private val rows: Vector[String] =
    val plan    = Files.readString(Parity.root.resolve("specs/009-kyc/plan.md"))
    val section = plan.substring(plan.indexOf("## Divergences")).linesIterator.drop(1).takeWhile(!_.startsWith("## ")).toVector
    section.filter(_.startsWith("| ")).drop(1).filterNot(_.startsWith("|---")).map(_.split('|')(1).trim)

  test("plan.md's Divergences table has rows") { assert(rows.nonEmpty) }

  test("every recording loads, and was made on two runs of the original") {
    val broken = paths.flatMap(p => Recording.load(p).left.toOption)
    assert(broken.isEmpty, broken.mkString("\n"))
    val once = recordings.filter(_.runs < 2).map(_.scenario)
    assert(once.isEmpty, s"recorded on one run: ${once.mkString("; ")}")
  }

  test("every divergence Parity names is a row of plan.md's Divergences") {
    val missing = Parity.divergences.map(_.row).distinct.filterNot(r => rows.exists(_.startsWith(r)))
    assert(missing.isEmpty, missing.mkString("; "))
  }

  rows.foreach { row =>
    val named = Parity.divergences.filter(d => row.startsWith(d.row))
    if named.isEmpty then test(s"unhit: $row (no recording reaches it; its suites hold it)".ignore) {}
    else
      test(s"$row is reached by a recording") {
        assert(named.exists(d => recordings.exists(d.reached)), s"no recording reaches '$row'")
      }
  }
