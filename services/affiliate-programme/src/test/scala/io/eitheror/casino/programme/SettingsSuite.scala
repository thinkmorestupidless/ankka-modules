package io.eitheror.casino.programme

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import io.eitheror.casino.programme.commission.{FactType, LedgerFact}

import java.time.Instant

class SettingsSuite extends munit.FunSuite:

  test("the brands are comma-separated slugs; spaces and repeats are dropped") {
    assertEquals(Settings.fromEnvironment(Map("PROGRAMME_BRANDS" -> "goldvibe, silvervibe,goldvibe")).map(_.brands),
      Right(Vector("goldvibe", "silvervibe")))
    assertEquals(Settings.fromEnvironment(Map.empty).map(_.brands), Right(Vector.empty))
  }

  test("a slug outside [a-z0-9-] is refused naming the variable") {
    assert(Settings.fromEnvironment(Map("PROGRAMME_BRANDS" -> "Gold_Vibe")).left.exists(_.startsWith("PROGRAMME_BRANDS")))
  }

  test("the sites are http or https addresses, kept without a trailing slash") {
    assertEquals(Settings.fromEnvironment(Map("AFFILIATE_PORTAL_BASE_URL" -> "https://affiliates.example/")).map(_.portalBaseUrl),
      Right("https://affiliates.example"))
    assert(Settings.fromEnvironment(Map("AFFILIATE_BACKOFFICE_BASE_URL" -> "ftp://x")).isLeft)
  }

  test("Customer.io's region is eu or us") {
    assert(Settings.fromEnvironment(Map("CUSTOMERIO_REGION_DEFAULT" -> "ap")).isLeft)
    assertEquals(Settings.fromEnvironment(Map("CUSTOMERIO_REGION_DEFAULT" -> "us")).map(_.customerIoRegion), Right("us"))
  }

  test("a ledger fact is stored with its type as the domain spells it, and read back") {
    import ProgrammeCodecs.given
    given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[LedgerFact] = ProgrammeCodecs.stored[LedgerFact]
    val f = LedgerFact("bonus-cost/f-1", FactType.BonusCost, -500, Instant.parse("2026-08-01T00:00:00Z"),
      Instant.parse("2026-08-01T00:00:01Z"), "2026-08")
    val json = writeToString(f)
    assert(json.contains("\"kind\":\"bonus-cost\""), json)
    assert(!json.contains("settledBy"), json)
    assertEquals(readFromString[LedgerFact](json), f)
  }
