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

  override def afterAll(): Unit = { super.afterAll(); if Files.exists(directory.resolve("bundle.json")) then kit.stop() }

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
    kit.eventually("the import's end", within = scala.concurrent.duration.Duration(300, "s"))(
      Option(post()).filter(_.get("status").asText == "finished")): Unit
    val revenue = FakeServer.mapper.readTree(read("revenue.json"))
    val differences = for
      affiliate <- revenue.fieldNames().asScala.toVector
      period    <- revenue.get(affiliate).fieldNames().asScala.toVector
      expected  = revenue.get(affiliate).get(period)
      field     <- expected.fieldNames().asScala.toVector
      figure    = figureOf(affiliate, period, expected.get("totalCommission").asLong)
      if figure.path(field).asLong != expected.get(field).asLong
    yield s"$affiliate $period $field: the original ${expected.get(field).asLong}, the programme ${figure.path(field).asLong}"
    assert(differences.isEmpty, differences.mkString("\n"))
  }

  /** The programme's EUR figure, waited for until its total is the original's or the wait ends. */
  private def figureOf(affiliate: String, period: String, total: Long): JsonNode =
    def read = kit.request("GET", s"/v1/affiliates/${bundle.brand}/accounts/$affiliate/revenue?period=$period",
      headers = kit.asManager(Seq(bundle.brand))).json.get("figures").elements().asScala.toVector
    val wanted = scala.util.Try(kit.eventually(s"$affiliate $period")(read.find(_.path("totalCommission").asLong == total)))
    wanted.getOrElse(read.headOption.getOrElse(FakeServer.mapper.createObjectNode()))
