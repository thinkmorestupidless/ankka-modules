package io.eitheror.casino.attribution.settings

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.{Instant, LocalDate}

class SettingsEntitySuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-25T10:00:00Z")
  private def kit = KeyValueEntityTestKit.of(SettingsEntity, SettingsEntity.Id)

  test("the defaults are 30 and 30 days, nothing started") {
    val s = kit.call(SettingsEntity.get).replyValue
    assertEquals((s.attributionWindowDays, s.bonusCodeWindowDays, s.firstDay), (30, 30, None))
  }

  test("setWindows takes 1 to 365 days and refuses 0 and 366 naming the field") {
    val k = kit
    assertEquals(k.call(SettingsEntity.setWindows)(SetWindows(7, 14, "staff-1", at)).replyValue.bonusCodeWindowDays, 14)
    assert(k.call(SettingsEntity.setWindows)(SetWindows(0, 14, "staff-1", at)).errorMessage.startsWith("attributionWindowDays"))
    assert(k.call(SettingsEntity.setWindows)(SetWindows(30, 366, "staff-1", at)).errorMessage.startsWith("bonusCodeWindowDays"))
    assertEquals(k.call(SettingsEntity.get).replyValue.attributionWindowDays, 7)
    assert(k.call(SettingsEntity.setWindows)(SetWindows(365, 1, "s", at)).replyValue.attributionWindowDays == 365)
  }

  test("start sets the first day once; a repeat is nothing") {
    val k = kit
    assertEquals(k.call(SettingsEntity.start)("2026-11-01").replyValue.firstDay, Some("2026-11-01"))
    val again = k.call(SettingsEntity.start)("2026-11-02")
    assert(!again.changed)
    assertEquals(again.replyValue.firstDay, Some("2026-11-01"))
  }

  test("legacySeen counts per day and moves the last legacy day forward only") {
    val k = kit
    k.call(SettingsEntity.legacySeen)("2026-11-02"): Unit
    k.call(SettingsEntity.legacySeen)("2026-11-02"): Unit
    val s = k.call(SettingsEntity.legacySeen)("2026-11-01").replyValue
    assertEquals(s.legacyDaily, Map("2026-11-02" -> 2L, "2026-11-01" -> 1L))
    assertEquals(s.lastLegacyDay, Some("2026-11-02"))
    assertEquals(s.legacyLast30Days(LocalDate.parse("2026-11-30")), 3L)
    assertEquals(s.legacyLast30Days(LocalDate.parse("2026-12-01")), 2L)
  }

  test("pruneLegacy drops counts older than 90 days") {
    val k = kit
    k.call(SettingsEntity.legacySeen)("2026-01-01"): Unit
    k.call(SettingsEntity.legacySeen)("2026-10-01"): Unit
    assertEquals(k.call(SettingsEntity.pruneLegacy)("2026-10-25").replyValue.legacyDaily.keySet, Set("2026-10-01"))
  }

  test("endTransition once") {
    val k = kit
    assertEquals(k.call(SettingsEntity.endTransition)(EndTransition("2027-01-30", "90_days_without_legacy")).replyValue.transitionEnd,
      Some(TransitionEnd("2027-01-30", "90_days_without_legacy")))
    assert(!k.call(SettingsEntity.endTransition)(EndTransition("2027-11-01", "12_months")).changed)
  }

  test("signing keys: added, the newest live one signs, the last live key cannot be retired") {
    val k = kit
    k.call(SettingsEntity.addSigningKey)(AddSigningKey("b-1", "x1", at)): Unit
    assertEquals(k.call(SettingsEntity.retireSigningKey)(RetireSigningKey("b-1", at)).errorMessage, "the last live signing key cannot be retired")
    val two = k.call(SettingsEntity.addSigningKey)(AddSigningKey("b-2", "x2", at.plusSeconds(1))).replyValue
    assertEquals(two.signingKey.map(_.kid), Some("b-2"))
    assert(k.call(SettingsEntity.addSigningKey)(AddSigningKey("b-2", "x3", at)).isError)
    val retired = k.call(SettingsEntity.retireSigningKey)(RetireSigningKey("b-2", at.plusSeconds(2))).replyValue
    assertEquals(retired.liveKeys.map(_.kid), Vector("b-1"))
    assertEquals(retired.signingKey.map(_.kid), Some("b-1"))
    assert(k.call(SettingsEntity.retireSigningKey)(RetireSigningKey("b-9", at)).isError)
  }
