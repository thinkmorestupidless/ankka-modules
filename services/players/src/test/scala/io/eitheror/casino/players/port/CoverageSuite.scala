package io.eitheror.casino.players.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

import java.nio.file.Files

/**
 * tests/port/players' recordings and plan.md's *Divergences*: each recording loads and was made on two
 * runs of the original; every row of the table is named in `Parity.divergences` and reached by at least
 * one recording, or reported ignored as unhit until its story names it; every named one is a row.
 */
class CoverageSuite extends munit.FunSuite with LogCapturing:
  private val paths           = Parity.recordings()
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  private val rows: Vector[String] =
    val plan    = Files.readString(Parity.root.resolve("specs/008-players/plan.md"))
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
    if named.isEmpty then test(s"unhit: $row (no Parity divergence names it yet)".ignore) {}
    else
      test(s"$row is reached by a recording") {
        assert(named.exists(d => recordings.exists(d.reached)), s"no recording reaches '$row'")
      }
  }
