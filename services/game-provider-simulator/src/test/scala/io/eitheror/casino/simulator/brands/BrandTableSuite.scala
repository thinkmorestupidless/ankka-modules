package io.eitheror.casino.simulator.brands

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/** The brand table (FR-025; contracts/brand-config.md): a malformed entry refuses the start, naming the field. */
class BrandTableSuite extends munit.FunSuite with LogCapturing:

  private def parse(brands: String, env: Map[String, String] = Map.empty) = BrandTable.parse(brands, "http://localhost:9100/", None, None, env)

  test("an entry with its defaults") {
    val table = parse("""[{"slug":"goldvibe","baseAddress":"http://localhost:9000","testBrand":true}]""").fold(fail(_), identity)
    assertEquals(table.answerWait("goldvibe"), 5.seconds)
    assertEquals(table.keyRefreshBound("goldvibe"), 60.seconds)
    assertEquals(table.externalUrl, "http://localhost:9100")
    assertEquals(table.retentionDays, 30)
    assert(table.testBrand("goldvibe"))
  }

  test("a missing or malformed field is named") {
    assert(parse("""[{"slug":"goldvibe","testBrand":true}]""").left.exists(_.contains("SIMULATOR_BRANDS[0].baseAddress")))
    assert(parse("""[{"slug":"goldvibe","baseAddress":"ftp://x","testBrand":true}]""").left.exists(_.contains("baseAddress")))
    assert(parse("""[{"slug":"goldvibe","baseAddress":"http://x"}]""").left.exists(_.contains("testBrand")))
    assert(parse("""[{"slug":"Gold","baseAddress":"http://x","testBrand":true}]""").left.exists(_.contains("slug")))
    assert(parse("not json").left.exists(_.contains("not JSON")))
  }

  test("a brand named twice is refused") {
    val two = """[{"slug":"a","baseAddress":"http://x","testBrand":true},{"slug":"a","baseAddress":"http://y","testBrand":true}]"""
    assert(parse(two).left.exists(_.contains("twice")))
  }

  test("a CA named from the environment is resolved, and a missing one is named") {
    val entry = """[{"slug":"a","baseAddress":"https://x","testBrand":true,"caPem":"env:LOCAL_CA"}]"""
    assertEquals(parse(entry, Map("LOCAL_CA" -> "PEM")).map(_.brands.head.caPem), Right(Some("PEM")))
    assert(parse(entry).left.exists(_.contains("LOCAL_CA")))
  }

  test("a launch's site picks its brand; with one brand, that brand") {
    val two = parse("""[{"slug":"a","baseAddress":"http://x","testBrand":true,"site":"site-a"},{"slug":"b","baseAddress":"http://y","testBrand":true}]""").toOption.get
    assertEquals(two.bySite(Some("site-a")).map(_.slug), Some("a"))
    assertEquals(two.bySite(Some("b")).map(_.slug), Some("b"))
    assertEquals(two.bySite(None), None)
    val one = parse("""[{"slug":"a","baseAddress":"http://x","testBrand":true}]""").toOption.get
    assertEquals(one.bySite(Some("anything")).map(_.slug), Some("a"))
  }
