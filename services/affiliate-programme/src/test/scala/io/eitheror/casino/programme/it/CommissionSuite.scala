package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.brands.{Fees, GgrSource, Plan}
import io.eitheror.casino.programme.commission.FactType.*

import java.time.Instant

/**
 * A brand's facts become commission (US2), over the whole service: what the original's fold makes of
 * them (`S/CommissionEngine.scala`), whatever order they arrive in and however often.
 */
class CommissionSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def at(text: String) = Instant.parse(text)

  test("registration, first deposit, three GGR days, a bonus cost and a chargeback, out of order with a duplicate, fold as the original's") {
    val s = Scene(kit, planType = Plan.Hybrid, cpa = 5000, revShareBp = 3000, minimumDeposit = 2000)
    val p = s.player(at("2026-10-01T09:00:00Z"))
    val day3 = s.facts.ggrDaily(p, "2026-10-04", 9_000, 2_000, at("2026-10-04T23:00:00Z"))
    val cb   = s.facts.chargeback(p, 1_500, at("2026-10-05T10:00:00Z"))
    Vector(
      cb,
      s.facts.firstDeposit(p, 2_000, at("2026-10-01T10:00:00Z")),
      s.facts.deposit(p, 2_000, at("2026-10-01T10:00:00Z")),
      day3,
      s.facts.ggrDaily(p, "2026-10-02", 4_000, 1_000, at("2026-10-02T23:00:00Z")),
      s.facts.bonusCost(p, 500, at("2026-10-03T12:00:00Z")),
      s.facts.ggrDaily(p, "2026-10-03", 1_000, 3_000, at("2026-10-03T23:00:00Z"))
    ).foreach(s.send(_))
    assertEquals(s.send(day3), "ggr") // the duplicate: the inbox keeps the first outcome, and the sums below count it once
    // GGR 3,000 - 2,000 + 7,000 = 8,000; NGR = 8,000 - 500 - 1,500 = 6,000; RevShare 30% = 1,800; CPA 5,000 once.
    val rows = s.rowsWhen("the fold")(rs => s.sum(rs, RevShare) == 1_800 && s.sum(rs, Cpa) == 5_000)
    assertEquals((s.sum(rows, Ggr), s.sum(rows, BonusCost), s.sum(rows, Chargeback)), (8_000L, 500L, 1_500L))
    assertEquals(rows.count(_.kind == Cpa), 1)
    assertEquals((s.state(p).deposits("EUR").count, s.state(p).deposits("EUR").sum), (1, 2_000L))
  }

  test("CPA is paid once, in the plan's currency, for a first deposit in another; later deposits count only") {
    val s = Scene(kit, planType = Plan.Cpa, cpa = 5000, currency = "EUR", revShareBp = 0, minimumDeposit = 2000)
    val p = s.player()
    assertEquals(s.send(s.facts.firstDeposit(p, 2_000, kit.now, currency = "USD")), "first-deposit")
    assertEquals(s.send(s.facts.deposit(p, 2_000, kit.now, currency = "USD")), "deposit")
    assertEquals(s.send(s.facts.deposit(p, 9_000, at("2026-11-20T10:00:00Z"), currency = "USD")), "deposit")
    val rows = s.rowsWhen("the CPA")(rs => s.sum(rs, Cpa) == 5_000)
    assertEquals(rows.filter(_.kind == Cpa).map(_.currency), Vector("EUR"))
    assertEquals((s.state(p).deposits("USD").count, s.state(p).deposits("USD").sum), (2, 11_000L))
  }

  test("a first deposit below the minimum pays nothing; a later one at the minimum pays CPA") {
    val s = Scene(kit, planType = Plan.Cpa, cpa = 5000, revShareBp = 0, minimumDeposit = 2000)
    val p = s.player()
    s.send(s.facts.firstDeposit(p, 1_000, kit.now)): Unit
    s.send(s.facts.deposit(p, 1_000, kit.now)): Unit
    assert(s.state(p).guard.isEmpty)
    assertEquals(s.send(s.facts.deposit(p, 2_500, kit.now)), "first-deposit")
    assertEquals(s.sum(s.rowsWhen("the CPA")(rs => s.sum(rs, Cpa) == 5_000), Cpa), 5_000L)
  }

  test("the brand's fees are taken per GGR fact and per deposit, before RevShare") {
    Scene.withFees(kit, "silvervibe", Fees(admin = 100, payment = 200, provider = 300)) {
      val s = Scene(kit, brand = "silvervibe")
      val p = s.player()
      s.send(s.facts.deposit(p, 5_000, kit.now)): Unit
      s.rowsWhen("the payment fee")(rs => s.sum(rs, PaymentFee) == 100)
      s.send(s.facts.ggrDaily(p, "2026-10-25", 10_000, 0, kit.now)): Unit
      // NGR = 10,000 - 100 admin - 300 provider - 100 payment = 9,500; RevShare 30% = 2,850.
      val rows = s.rowsWhen("RevShare")(rs => s.sum(rs, RevShare) == 2_850)
      assertEquals((s.sum(rows, AdminFee), s.sum(rows, ProviderFee)), (100L, 300L))
    }
  }

  test("the GGR source switch takes effect from its day; the other kind is recorded, not folded") {
    Scene.setGgrSource(kit, "silvervibe", GgrSource.Rounds, "2026-03-10")
    try
      val s = Scene(kit, brand = "silvervibe")
      val p = s.player(at("2026-03-01T09:00:00Z"))
      assertEquals(s.send(s.facts.ggrDaily(p, "2026-03-09", 1_000, 0, at("2026-03-09T20:00:00Z"))), "ggr")
      assertEquals(s.send(s.facts.ggrRound(p, 70, 0, at("2026-03-09T20:00:00Z"))), "ignored:other_source")
      assertEquals(s.send(s.facts.ggrRound(p, 400, 0, at("2026-03-10T20:00:00Z"))), "ggr")
      assertEquals(s.send(s.facts.ggrDaily(p, "2026-03-10", 2_000, 0, at("2026-03-10T23:00:00Z"))), "ignored:other_source")
      assertEquals(s.sum(s.rowsWhen("the folded GGR")(rs => s.sum(rs, Ggr) == 1_400), Ggr), 1_400L)
    finally Scene.setGgrSource(kit, "silvervibe", GgrSource.Daily, "2026-03-11")
  }

  test("a GGR day from before the binding is recorded on the player and folds nothing") {
    val s = Scene(kit)
    val p = s.player()
    assertEquals(s.send(s.facts.ggrDaily(p, "2026-10-20", 3_000, 0, kit.now, beforeBinding = Some(true))), "before-binding")
    assertEquals(s.state(p).beforeBinding.map(_.ggr), Vector(3_000L))
    s.send(s.facts.ggrDaily(p, "2026-10-25", 100, 0, kit.now)): Unit
    assertEquals(s.sum(s.rowsWhen("the later day")(rs => s.sum(rs, Ggr) == 100), Ggr), 100L)
  }

  test("without carryover a negative period floors at zero and the next starts fresh") {
    val s = Scene(kit, carryover = false)
    val p = s.player(at("2026-08-01T09:00:00Z"))
    s.send(s.facts.ggrDaily(p, "2026-09-15", 0, 1_000, at("2026-09-15T12:00:00Z"))): Unit
    s.send(s.facts.ggrDaily(p, "2026-10-15", 2_000, 0, at("2026-10-15T12:00:00Z"))): Unit
    val rows = s.rowsWhen("October's RevShare")(rs => s.sum(rs, RevShare, period = Some("2026-10")) == 600)
    assertEquals(s.sum(rows, RevShare, period = Some("2026-09")), 0L)
  }

  test("with carryover a deficit persists until positive NGR covers it") {
    val s = Scene(kit, carryover = true)
    val p = s.player(at("2026-08-01T09:00:00Z"))
    s.send(s.facts.ggrDaily(p, "2026-09-15", 0, 1_000, at("2026-09-15T12:00:00Z"))): Unit
    s.send(s.facts.ggrDaily(p, "2026-10-15", 2_000, 0, at("2026-10-15T12:00:00Z"))): Unit
    // Lifetime NGR 1,000: RevShare 300, all written in October.
    assertEquals(s.sum(s.rowsWhen("the covered RevShare")(rs => s.sum(rs, Ggr) == 1_000 && s.sum(rs, RevShare) == 300), RevShare), 300L)
  }

  test("a suspended membership's facts are recorded and write no payable fact (FR-016)") {
    val s = Scene(kit, planType = Plan.Hybrid, cpa = 5000, minimumDeposit = 0)
    val p = s.player()
    s.suspend()
    assertEquals(s.send(s.facts.firstDeposit(p, 2_000, kit.now)), "first-deposit")
    s.send(s.facts.ggrDaily(p, "2026-10-25", 1_000, 0, kit.now)): Unit
    val rows = s.rowsWhen("the GGR")(rs => s.sum(rs, Ggr) == 1_000)
    assertEquals(rows.filter(_.kind.payable), Vector.empty)
    assert(s.state(p).guard.isDefined, "the guard is set, so the CPA is not paid later either")
  }
