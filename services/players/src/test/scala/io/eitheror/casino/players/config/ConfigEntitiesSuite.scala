package io.eitheror.casino.players.config

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

/** The three configuration entities (T014). */
class ConfigEntitiesSuite extends munit.FunSuite:

  test("an empty BrandConfig has data-model.md's defaults") {
    val c = KeyValueEntityTestKit.of(BrandConfigEntity, "goldvibe").call(BrandConfigEntity.get).replyValue
    assertEquals((c.reportingZone, c.singleSession, c.multiAccountDetection, c.retentionDays, c.features),
      ("UTC", false, true, None, Map.empty[String, Boolean]))
  }

  test("BrandConfig's set takes an IANA zone and refuses another as invalid_config naming the field") {
    val kit = KeyValueEntityTestKit.of(BrandConfigEntity, "goldvibe")
    assertEquals(kit.call(BrandConfigEntity.set)(BrandConfig(name = "Gold", reportingZone = "Africa/Johannesburg")).replyValue.refusal, None)
    val bad = kit.call(BrandConfigEntity.set)(BrandConfig(name = "Other", reportingZone = "Mars/Olympus"))
    assertEquals((bad.replyValue.refusal, bad.replyValue.field), (Some("invalid_config"), Some("reportingZone")))
    assertEquals(kit.currentState.name, "Gold", "a refused write changes nothing")
    assertEquals(kit.call(BrandConfigEntity.set)(BrandConfig(retentionDays = Some(0))).replyValue.field, Some("retentionDays"))
  }

  test("CurrencyConfig refuses an unknown ISO code and a default it does not offer") {
    val kit = KeyValueEntityTestKit.of(CurrencyConfigEntity, "goldvibe")
    assertEquals(kit.call(CurrencyConfigEntity.set)(CurrencyConfig(Vector("eur", "GBP"), Some("eur"))).replyValue.config,
      CurrencyConfig(Vector("EUR", "GBP"), Some("EUR")))
    assertEquals(kit.call(CurrencyConfigEntity.set)(CurrencyConfig(Vector("EUR", "XQQ"))).replyValue.field, Some("offered"))
    assertEquals(kit.call(CurrencyConfigEntity.set)(CurrencyConfig(Vector("EUR"), Some("USD"))).replyValue.field, Some("defaultCurrency"))
  }

  test("an empty AllowList allows every country; a set one only its own; an unknown code is refused") {
    assert(AllowList().allows("ZA"))
    val kit = KeyValueEntityTestKit.of(AllowListEntity, "goldvibe")
    val set = kit.call(AllowListEntity.set)(AllowList(Set("gb", "IE"))).replyValue.config
    assert(set.allows("gb") && !set.allows("FR"))
    assertEquals(kit.call(AllowListEntity.set)(AllowList(Set("ZZ"))).replyValue.field, Some("countries"))
  }
