package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.{Instant, LocalDate}

class BrandProgrammeSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private def kit = KeyValueEntityTestKit.of(BrandProgramme, "goldvibe")

  test("seed makes a brand here once; a repeat changes nothing") {
    val k = kit
    val s = k.call(BrandProgramme.seed)(SeedBrand("goldvibe", at)).replyValue
    assertEquals((s.slug, s.origin, s.reportingZone, s.ggrSource.current, s.thresholds), ("goldvibe", "here", "UTC", "daily", Thresholds(3, 2)))
    assert(!k.call(BrandProgramme.seed)(SeedBrand("goldvibe", at.plusSeconds(1))).changed)
  }

  test("add makes a brand elsewhere or hosted once; here is not added, nor a zone that is none") {
    val k = kit
    assertEquals(k.call(BrandProgramme.add)(AddBrand("acme", "hosted", Some("Acme"), "Europe/Malta", "op-1", at)).replyValue.origin, "hosted")
    assertEquals(k.call(BrandProgramme.add)(AddBrand("acme", "hosted", None, "UTC", "op-1", at)).errorMessage, "brand_exists")
    assert(KeyValueEntityTestKit.of(BrandProgramme, "b").call(BrandProgramme.add)(AddBrand("b", "here", None, "UTC", "o", at)).isError)
    assert(KeyValueEntityTestKit.of(BrandProgramme, "c").call(BrandProgramme.add)(AddBrand("c", "elsewhere", None, "Mars/Base", "o", at)).isError)
  }

  test("update changes only what it names, and refuses a fee over 100%, a threshold of 0 and a day of 29") {
    val k = kit
    k.call(BrandProgramme.seed)(SeedBrand("goldvibe", at)): Unit
    val s = k.call(BrandProgramme.update)(UpdateBrand(fees = Some(Fees(2000, 150, 100)), defaultPlan = Some("plan-1"))).replyValue
    assertEquals((s.fees, s.defaultPlan, s.thresholds), (Fees(2000, 150, 100), Some("plan-1"), Thresholds()))
    assert(k.call(BrandProgramme.update)(UpdateBrand(fees = Some(Fees(10_001)))).errorMessage.startsWith("adminFeePercent"))
    assert(k.call(BrandProgramme.update)(UpdateBrand(thresholds = Some(Thresholds(0, 2)))).isError)
    assert(k.call(BrandProgramme.update)(UpdateBrand(payoutCycleDay = Some(29))).isError)
    assertEquals(k.call(BrandProgramme.get).replyValue.fees, Fees(2000, 150, 100))
  }

  test("an unknown brand is not updated") {
    assertEquals(kit.call(BrandProgramme.update)(UpdateBrand(name = Some("x"))).errorMessage, "brand_unknown")
  }

  test("the GGR source changes from a named day; the days before keep the source they had") {
    val k = kit
    k.call(BrandProgramme.seed)(SeedBrand("goldvibe", at)): Unit
    val s = k.call(BrandProgramme.setGgrSource)(SetGgrSource("rounds", "2026-11-02")).replyValue.ggrSource
    assertEquals(s.on(LocalDate.parse("2026-11-01")), "daily")
    assertEquals(s.on(LocalDate.parse("2026-11-02")), "rounds")
    val back = k.call(BrandProgramme.setGgrSource)(SetGgrSource("daily", "2026-11-10")).replyValue.ggrSource
    assertEquals(back.on(LocalDate.parse("2026-11-05")), "rounds")
    assertEquals(back.on(LocalDate.parse("2026-11-10")), "daily")
    assert(k.call(BrandProgramme.setGgrSource)(SetGgrSource("hourly", "2026-11-10")).isError)
  }

  test("retire once; duplicates and mismatches are counted") {
    val k = kit
    k.call(BrandProgramme.seed)(SeedBrand("goldvibe", at)): Unit
    assert(!k.call(BrandProgramme.retire)(at).replyValue.live)
    assert(!k.call(BrandProgramme.retire)(at.plusSeconds(5)).changed)
    k.call(BrandProgramme.countDuplicate): Unit
    k.call(BrandProgramme.countDuplicate): Unit
    val s = k.call(BrandProgramme.countMismatch).replyValue
    assertEquals((s.duplicates, s.mismatches), (2L, 1L))
  }
