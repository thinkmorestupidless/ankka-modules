package io.eitheror.casino.payouts.port

import io.eitheror.casino.contract.port.Recording

import java.nio.file.Files

/**
 * tests/port/payouts' recordings, plan.md's *Divergences* and contracts/wire.md's routes: each recording
 * loads and was made on two runs of the original; every divergence `Parity` names is a row of the table; a
 * row no recording reaches is reported ignored with where it is demonstrated instead; and a route of the
 * table no recording reaches is reported ignored as unhit.
 */
class CoverageSuite extends munit.FunSuite:
  private val paths           = Parity.recordings()
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  private val rows: Vector[(String, String)] =
    val plan    = Files.readString(Parity.root.resolve("specs/007-payouts/plan.md"))
    val section = plan.substring(plan.indexOf("## Divergences")).linesIterator.drop(1).takeWhile(!_.startsWith("## ")).toVector
    section.filter(_.startsWith("| ")).drop(1).filterNot(_.startsWith("|---")).map(_.split('|')).map(c => (c(1).trim, c(3).trim))

  test("plan.md's Divergences table has rows") { assert(rows.nonEmpty) }

  test("every recording loads, and was made on two runs of the original") {
    val broken = paths.flatMap(p => Recording.load(p).left.toOption)
    assert(broken.isEmpty, broken.mkString("\n"))
    val once = recordings.filter(_.runs < 2).map(_.scenario)
    assert(once.isEmpty, s"recorded on one run: ${once.mkString("; ")}")
  }

  test("every divergence Parity names is a row of plan.md's Divergences") {
    val missing = Parity.divergences.map(_.row).distinct.filterNot(r => rows.exists(_._1.startsWith(r)))
    assert(missing.isEmpty, missing.mkString("; "))
  }

  rows.foreach { (row, shown) =>
    val named = Parity.divergences.filter(d => row.startsWith(d.row))
    if named.isEmpty then test(s"not in a recording: $row (shown by $shown)".ignore) {}
    else test(s"$row is reached by a recording") {
      assert(named.exists(d => recordings.exists(d.reached)), s"no recording reaches '$row'")
    }
  }

  /** contracts/wire.md's routes, one probe each, in the original's terms. */
  private val routes = Vector(
    "POST" -> "/v1/casino/brands/b/players/p/withdrawal-requests", "GET" -> "/v1/casino/brands/b/players/p/withdrawal-requests",
    "DELETE" -> "/v1/casino/brands/b/players/p/withdrawal-requests/r", "GET" -> "/v1/casino/brands/b/players/p/withdrawal-terminals",
    "GET" -> "/v1/casino/brands/b/players/p/payout-destinations", "POST" -> "/v1/casino/brands/b/players/p/payout-destinations",
    "PUT" -> "/v1/casino/brands/b/players/p/payout-destinations/d/set-default",
    "DELETE" -> "/v1/casino/brands/b/players/p/payout-destinations/d", "GET" -> "/v1/casino/brands/b/withdrawal-requests",
    "GET" -> "/v1/casino/brands/b/withdrawal-requests/r", "POST" -> "/v1/casino/brands/b/withdrawal-requests/r/approve",
    "POST" -> "/v1/casino/brands/b/withdrawal-requests/r/reject", "POST" -> "/v1/casino/brands/b/withdrawal-requests/r/confirm-payout",
    "POST" -> "/v1/casino/brands/b/withdrawal-requests/r/mark-payout-failed", "GET" -> "/v1/casino/brands/b/players/p/withdrawal-history",
    "GET" -> "/v1/casino/brands/b/withdrawal-config", "PUT" -> "/v1/casino/brands/b/withdrawal-config",
    "POST" -> "/internal/v1/casino/withdrawal-requests/r/outcome"
  )

  /** The route a request is: its row, and for a decision which one. */
  private def row(method: String, path: String): Option[String] =
    scala.util.Try(WireTranslation.request(method, path, None, WireTranslation.Context("b")).map(_.row)).toOption.flatten
      .map(r => if r == WireTranslation.Row.Decision then s"$r/${path.split('/').last}" else r.toString)

  routes.foreach { (method, path) =>
    val reached = recordings.exists(_.steps.exists(s => s.request.method == method && row(method, s.request.path) == row(method, path)))
    if reached then test(s"$method $path is replayed") { assert(row(method, path).isDefined) }
    else test(s"unhit: $method $path (no recording reaches it)".ignore) {}
  }
