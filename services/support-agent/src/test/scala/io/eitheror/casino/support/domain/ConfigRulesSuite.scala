package io.eitheror.casino.support.domain

import io.eitheror.casino.support.Json
import io.eitheror.casino.support.config.ConfigBody

/** data-model.md's `ConfigBody` rules (FR-023, FR-029). */
class ConfigRulesSuite extends munit.FunSuite:
  private def read(text: String, realMoney: Boolean = false, current: ConfigBody = ConfigBody()) =
    ConfigRules.read(Json.parse(text).get, current, realMoney)

  private def refused(text: String, realMoney: Boolean = false) = read(text, realMoney).left.getOrElse(fail("accepted"))

  test("a field left out keeps its value; a field given replaces it"):
    val b = read("""{"turnsPerMinute":20,"personalFields":["firstName"]}""", current = ConfigBody(model = "m-1")).fold(r => fail(r.message), identity)
    assertEquals((b.turnsPerMinute, b.personalFields, b.model, b.maxInputChars), (20, Set("firstName"), "m-1", 2000))

  test("an unknown field, and a personal field players does not serve, are unknown_field naming it"):
    assertEquals((refused("""{"colour":"red"}""").code, refused("""{"colour":"red"}""").field), ("unknown_field", Some("colour")))
    val r = refused("""{"personalFields":["firstName","password"]}""")
    assertEquals((r.status, r.code, r.field), (400, "unknown_field", Some("password")))

  test("each range, refused invalid_config naming the field"):
    Vector("""{"maxInputChars":0}""" -> "maxInputChars", """{"maxInputChars":20001}""" -> "maxInputChars",
      """{"turnsPerMinute":121}""" -> "turnsPerMinute", """{"dailyTokens":0}""" -> "dailyTokens",
      """{"approvalMinutes":1441}""" -> "approvalMinutes", """{"retentionDays":3651}""" -> "retentionDays",
      """{"prompt":""}""" -> "prompt", """{"enabled":"yes"}""" -> "enabled", """{"turnsPerMinute":"ten"}""" -> "turnsPerMinute",
      """{"handOffMessage":"Ask the desk."}""" -> "handOffMessage").foreach { (body, field) =>
      val r = refused(body)
      assertEquals((r.status, r.code, r.field), (400, "invalid_config", Some(field)), body)
    }

  test("a hand-off message may carry the hours themselves instead of {hours}"):
    assert(read("""{"handOffMessage":"Ask live chat, every day, 08:00–24:00 UK time."}""").isRight)

  test("a real-money brand cannot turn the agent on without the safeguarding message and helpline (FR-023)"):
    val r = refused("""{"enabled":true}""", realMoney = true)
    assertEquals((r.status, r.code), (422, "safeguarding_not_configured"))
    assertEquals(refused("""{"enabled":true,"safeguardingMessage":"Please talk to someone."}""", realMoney = true).code, "safeguarding_not_configured")
    assert(read("""{"enabled":true,"safeguardingMessage":"Please talk to someone.","helpline":"0808 8020 133"}""", realMoney = true).isRight)
    assert(read("""{"enabled":true}""").isRight, "a brand with no real money needs neither")

  test("changes name the fields, and carry values only for those the audit may record"):
    val (fields, values) = ConfigRules.changes(ConfigBody(), ConfigBody(enabled = true, prompt = "Be brief.", personalFields = Set("country")))
    assertEquals(fields, Vector("enabled", "prompt", "personalFields"))
    assertEquals(values, Map("enabled" -> "true", "personalFields" -> "country"))
    assertEquals(ConfigRules.changes(ConfigBody(), ConfigBody()), (Vector.empty, Map.empty))
