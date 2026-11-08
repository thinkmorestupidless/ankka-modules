package io.eitheror.casino.reporting.settings

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.reporting.{ReportingService, Settings}

/** T029: the settings' defaults, a put, and each range refused with `invalid_setting` naming the field. */
class ReportingSettingsSuite extends munit.FunSuite:
  private def kit = KeyValueEntityTestKit.of(ReportingSettings, ReportingSettings.Id)

  test("the defaults: the tab shown, test accounts in, no default currency, 500 rows, 7 days, 24 hours a kind") {
    val s = kit.call(ReportingSettings.get).replyValue
    assertEquals(s, ReportingSettingsState(reportsTab = true, excludeTestAccounts = false, defaultCurrency = None, syncRows = 500, syncDays = 7))
    assertEquals(ReportingSettings.Kinds.map(s.lifetimeOf), Set(24))
  }

  test("a put within every range is kept whole") {
    val k    = kit
    val next = ReportingSettingsState(false, true, Some("ZAR"), 10_000, 365, Map("dispute-report" -> 2_160, "report-export" -> 1))
    assertEquals(k.call(ReportingSettings.put)(next).replyValue, next)
    assertEquals(k.currentState.lifetimeOf("dispute-report"), 2_160)
    assertEquals(k.currentState.lifetimeOf("day-end-export"), 24)
  }

  test("each field out of its range is refused, naming it and its range; nothing is kept") {
    val k = kit
    Seq(
      ReportingSettingsState(syncRows = 0)                                     -> "invalid_setting: syncRows is 1 to 10000, not 0",
      ReportingSettingsState(syncRows = 10_001)                                -> "invalid_setting: syncRows is 1 to 10000, not 10001",
      ReportingSettingsState(syncDays = 366)                                   -> "invalid_setting: syncDays is 1 to 365, not 366",
      ReportingSettingsState(lifetimeHours = Map("report-export" -> 2_161))    -> "invalid_setting: lifetimeHours.report-export is 1 to 2160, not 2161",
      ReportingSettingsState(lifetimeHours = Map("compliance-export" -> 0))    -> "invalid_setting: lifetimeHours.compliance-export is 1 to 2160, not 0",
      ReportingSettingsState(defaultCurrency = Some("euro"))                   -> "invalid_setting: defaultCurrency is a three-letter code, not euro"
    ).foreach { (s, problem) =>
      val r = k.call(ReportingSettings.put)(s)
      assert(r.isError, s.toString)
      assertEquals(r.errorMessage, problem)
    }
    assertEquals(k.currentState, ReportingSettingsState())
  }

  test("a lifetime for a kind that is not a document's is refused") {
    assert(ReportingSettings.validate(ReportingSettingsState(lifetimeHours = Map("ledger" -> 24))).exists(_.startsWith("invalid_setting: lifetimeHours.ledger")))
  }

  test("the settings are registered") {
    assert(ReportingService.descriptors(Settings("goldvibe")).exists(_.componentId.toString == "reporting-settings"))
  }
