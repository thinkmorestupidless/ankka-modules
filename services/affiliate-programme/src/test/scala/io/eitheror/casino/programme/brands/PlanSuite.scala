package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.programme.commission.Window

import java.time.Instant

class PlanSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private val id = Plan.idOf("goldvibe", "Gold CPA")
  private def kit = KeyValueEntityTestKit.of(Plan, s"goldvibe/$id")
  private def gold = CreatePlan("goldvibe", "Gold CPA", Plan.Hybrid, 5000, "EUR", 2500, 2000, negativeCarryover = false, "m-1", at)

  test("the id is the original's name UUID") {
    assertEquals(id, java.util.UUID.nameUUIDFromBytes("aff.plan:goldvibe:Gold CPA".getBytes("UTF-8")).toString)
  }

  test("create once; a second plan of the name is refused plan_already_exists") {
    val k = kit
    val p = k.call(Plan.createPlan)(gold).replyValue
    assertEquals((p.id, p.cpaAmount, p.cpaCurrency, p.revShareBp, p.window, p.paysCpa, p.paysRevShare), (id, 5000L, "EUR", 2500, Window.Period, true, true))
    assertEquals(k.call(Plan.createPlan)(gold).errorMessage, "plan_already_exists")
  }

  test("a type, a currency or a percent that is none is refused") {
    assert(kit.call(Plan.createPlan)(gold.copy(planType = "flat")).isError)
    assert(kit.call(Plan.createPlan)(gold.copy(cpaCurrency = "euro")).isError)
    assert(kit.call(Plan.createPlan)(gold.copy(revShareBp = 10_001)).isError)
  }

  test("update changes what it names; an unknown plan is plan_not_found") {
    val k = kit
    k.call(Plan.createPlan)(gold): Unit
    val p = k.call(Plan.update)(UpdatePlan(cpaCurrency = Some("USD"), negativeCarryover = Some(true), status = Some("inactive"))).replyValue
    assertEquals((p.cpaCurrency, p.window, p.status, p.cpaAmount), ("USD", Window.Lifetime, "inactive", 5000L))
    assert(k.call(Plan.update)(UpdatePlan(status = Some("gone"))).isError)
    assertEquals(KeyValueEntityTestKit.of(Plan, "goldvibe/x").call(Plan.update)(UpdatePlan()).errorMessage, "plan_not_found")
  }
