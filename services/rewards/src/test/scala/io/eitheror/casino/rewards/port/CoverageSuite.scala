package io.eitheror.casino.rewards.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.port.WireTranslation.{Context, Ids, Translated}

import java.nio.file.Files

/**
 * tests/port/rewards' recordings, plan.md's *Divergences* and contracts/wire.md's routes: each recording loads and was
 * made on two runs of the original; every divergence `Parity` names is a row of the table; a row no recording reaches
 * is reported ignored with where it is demonstrated instead; and a route of the table no recording reaches is reported
 * ignored as unhit.
 */
class CoverageSuite extends munit.FunSuite:
  private val paths           = Parity.recordings()
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  private val rows: Vector[(String, String)] =
    val plan    = Files.readString(Parity.root.resolve("specs/011-rewards/plan.md"))
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

  /** contracts/wire.md's routes, one probe each, in the original's terms (a webhook by its action). */
  private val routes: Vector[(String, String, Option[JsonNode])] = Vector(
    ("POST", "/v1/casino/bonus/b/enter-code", None), ("GET", "/v1/casino/bonus/b/state", None),
    ("POST", "/v1/casino/bonus/b/select", None), ("DELETE", "/v1/casino/bonus/b/cancel", None),
    ("POST", "/v1/casino/bonus/promo-code/b", Some(Json.obj("action" -> "preview"))), ("POST", "/v1/casino/bonus/offers/b", None),
    ("GET", "/v1/casino/bonus/offers/b/o", None), ("DELETE", "/v1/casino/bonus/offers/b/o", None),
    ("PUT", "/v1/casino/bonus/offers/b/reorder", None), ("GET", "/v1/casino/bonus/free-spins/spin-values/b", None),
    ("POST", "/v1/casino/bonus/grant/b/p", None), ("PATCH", "/v1/casino/b/bonuses/c/revoke", None),
    ("GET", "/v1/casino/backoffice/b/players/p/bonuses", None), ("GET", "/v1/casino/backoffice/b/bonuses", None),
    ("POST", "/v1/casino/customerio/webhook/b", Some(Json.obj("action" -> "grant_bonus"))),
    ("POST", "/v1/casino/customerio/webhook/b", Some(Json.obj("action" -> "target_offer"))),
    ("GET", "/twins/bonus-claims/c", None), ("GET", "/twins/free-spins-grants/g", None)
  )

  private def row(method: String, path: String, body: Option[JsonNode]): Option[String] =
    scala.util.Try(WireTranslation.request(method, path, body, Context("b", "p", Ids()))).toOption.collect {
      case Translated.Ours(r) => r.row.toString
    }

  routes.foreach { (method, path, body) =>
    val probe   = row(method, path, body)
    val reached = recordings.exists(_.steps.exists(s => s.request.method == method && row(method, s.request.path, s.request.body) == probe))
    val name    = s"$method $path${body.fold("")(b => s" ${Json.render(b)}")}"
    if reached then test(s"$name is replayed") { assert(probe.isDefined) }
    else test(s"unhit: $name (no recording reaches it)".ignore) {}
  }
