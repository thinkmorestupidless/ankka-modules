package io.eitheror.casino.programme.port

import com.fasterxml.jackson.databind.JsonNode
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.imports.Bundle
import io.eitheror.casino.programme.it.{FakeServer, Kit}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * The bundle exported from the recorded original (T088, research R19): `tests/port/programme/import/`
 * holds `bundle.json` and `revenue.json`, both written by `tests/original/programme-export`. Imported
 * here, every affiliate's revenue for every period the original kept equals the original's, field by
 * field, and the import run again writes nothing. Until the export is recorded this suite fails, not
 * passes empty. A service of its own (build.sbt), on the bundle's brand.
 */
class ImportParitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val directory = Parity.programme.resolve("import")
  private def read(name: String) = Files.readString(directory.resolve(name))
  private lazy val body   = read("bundle.json")
  private lazy val bundle = readFromString[Bundle](body)
  private lazy val kit    = Kit(Vector(bundle.brand))

  override def afterAll(): Unit = { if Files.exists(directory.resolve("bundle.json")) then kit.stop(); super.afterAll() }

  private def post(): JsonNode =
    val r = kit.request("POST", s"/v1/programme/imports/${bundle.brand}", Some(body), kit.asPlatformAdmin())
    assert(r.status == 200 || r.status == 202, s"${r.status} ${r.text}")
    r.json

  test("tests/port/programme/import holds the original's export") {
    assert(Files.exists(directory.resolve("bundle.json")) && Files.exists(directory.resolve("revenue.json")),
      s"$directory: the export is not recorded yet (tests/original/programme-export, T088)")
  }

  test("the recorded bundle imports and folds to the original's revenue for every affiliate and period") {
    assume(Files.exists(directory.resolve("bundle.json")), "the export is not recorded yet")
    val done = kit.eventually("the import's end", within = scala.concurrent.duration.Duration(300, "s"))(
      Option(post()).filter(_.get("status").asText == "finished"))
    // Run again, the same bundle is the same import, finished, with the same counts: nothing is written twice.
    val again = post()
    assertEquals((again.get("importId").asText, again.get("status").asText, again.get("counts")), (done.get("importId").asText, "finished", done.get("counts")))
    val revenue = FakeServer.mapper.readTree(read("revenue.json"))
    val differences = for
      affiliate <- revenue.fieldNames().asScala.toVector
      period    <- revenue.get(affiliate).fieldNames().asScala.toVector
      expected  = revenue.get(affiliate).get(period)
      figure    = figureOf(affiliate, period, expected.get("totalCommission").asLong) // once per affiliate and period
      field     <- expected.fieldNames().asScala.toVector
      if figure.path(field).asLong != expected.get(field).asLong && !unstored(expected, figure, field)
    yield s"$affiliate $period $field: the original ${expected.get(field).asLong}, the programme ${figure.path(field).asLong}"
    assert(differences.isEmpty, differences.mkString("\n"))
  }

  /**
   * The original keeps no bonus-cost or chargeback fact: their ids (`bonus-<tx>`, `chargeback-<tx>`) are not
   * UUIDs and the substrate refuses them, so its revenue counted each deduction only in the request that
   * carried it. The export has no fact to carry, so the programme's figure has none, and its NGR is the
   * original's with those deductions given back; the commission is the same (research.md).
   */
  private def unstored(original: JsonNode, programme: JsonNode, field: String): Boolean =
    def o(f: String) = original.path(f).asLong
    def p(f: String) = programme.path(f).asLong
    Set("bonusCosts", "chargebacks", "ngr")(field) && p("bonusCosts") == 0 && p("chargebacks") == 0 &&
      p("ngr") == o("ngr") + o("bonusCosts") + o("chargebacks")

  /** The currency the bundle's facts are in: the recorded original's brand ran in one. */
  private lazy val currency: String =
    bundle.facts.map(_.currency).distinct match
      case Vector(one) => one
      case several     => fail(s"the bundle's facts are in ${several.mkString(", ")}; the parity reads one currency")

  /** The programme's figure in the bundle's currency, waited for until its total is the original's or the wait ends. */
  private def figureOf(affiliate: String, period: String, total: Long): JsonNode =
    def read = kit.request("GET", s"/v1/affiliates/${bundle.brand}/accounts/$affiliate/revenue?period=$period",
      headers = kit.asManager(Seq(bundle.brand))).json.get("figures").elements().asScala.toVector.filter(_.path("currency").asText == currency)
    val wanted = scala.util.Try(kit.eventually(s"$affiliate $period")(read.find(_.path("totalCommission").asLong == total)))
    wanted.getOrElse(read.headOption.getOrElse(FakeServer.mapper.createObjectNode()))
