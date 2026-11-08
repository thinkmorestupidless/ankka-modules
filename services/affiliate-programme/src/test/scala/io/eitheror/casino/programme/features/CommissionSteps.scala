package io.eitheror.casino.programme.features

import io.eitheror.casino.contract.facts.AttributionFact
import io.eitheror.casino.programme.brands.{Fees, GgrSource, Plan}
import io.eitheror.casino.programme.commission.Commission
import io.eitheror.casino.programme.commission.FactType.*
import io.eitheror.casino.programme.it.Scene

import java.time.Instant
import scala.collection.mutable

/**
 * features/programme/commission.feature (US2). Brand "A" is goldvibe, except where a scenario changes
 * the brand's settings: those use silvervibe, on days of their own in March, and put the settings back.
 */
class CommissionSteps extends ProgrammeFeatures("commission.feature"):

  // ── a scenario's state ──────────────────────────────────────────────────────────────────────────

  private var s: Scene                    = null
  private var p: String                   = null
  private var pending: AttributionFact    = null
  private var sent: Vector[AttributionFact] = Vector.empty
  private var seen: Vector[Long]          = Vector.empty
  private var outcome: String             = null
  private var duplicates: Long            = 0
  private val restore                     = mutable.Buffer.empty[() => Unit]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    s = null; p = null; pending = null; sent = Vector.empty; seen = Vector.empty; outcome = null; duplicates = 0
    round = null; totals = Vector.empty; both = () => fail("no Given arranged two facts")

  override def afterEach(context: AfterEach): Unit =
    try restore.reverse.foreach(_()) finally restore.clear()
    super.afterEach(context)

  private def at(text: String) = Instant.parse(text)
  private def brandOf(name: String) = if name == "A" then "goldvibe" else name

  private def attributed(scene: => Scene, from: Instant = kit.now): Unit =
    s = scene
    p = s.player(from)

  private def send(f: AttributionFact): String =
    sent :+= f
    outcome = s.send(f)
    outcome

  /** The brand's fees for this scenario, put back after it. */
  private def fees(slug: String, f: Fees): Unit =
    val before = Scene.brand(kit, slug).fees
    kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(slug))
      .call(io.eitheror.casino.programme.brands.BrandProgramme.update)
      .invoke(io.eitheror.casino.programme.brands.UpdateBrand(fees = Some(f))): Unit
    restore += (() => kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(slug))
      .call(io.eitheror.casino.programme.brands.BrandProgramme.update)
      .invoke(io.eitheror.casino.programme.brands.UpdateBrand(fees = Some(before))): Unit)

  /** Nothing more is written: the ledger read twice, a projection's poll apart, the same. */
  private def settled() =
    val first = s.rows
    Thread.sleep(3_000)
    val again = s.rows
    assertEquals(again, first)
    again

  // ── a registration ──────────────────────────────────────────────────────────────────────────────

  Given("a {string} fact naming the programme, an affiliate, a link and a pseudonym") { (kind: String) =>
    assertEquals(kind, "registration")
    s = Scene(kit)
    p = kit.fresh("ps")
    pending = s.facts.copy(campaign = Some("summer"), subIds = Vector("s1", "s2"), clickId = Some("c-1"))
      .registration(p, kit.now, country = Some("MT"))
  }
  When("it is consumed") { () => send(pending): Unit }
  Then("the player is recorded as attributed to that affiliate at that brand with the link, the campaign, the sub-ids, the click and the country") { () =>
    assertEquals(outcome, "bound")
    val st = s.state(p)
    assertEquals(st.binding.map(b => (b.affiliate, b.link, b.campaign, b.subIds, b.clickId)),
      Some((s.affiliate, s.link, Some("summer"), Vector("s1", "s2"), Some("c-1"))))
    assertEquals((st.brand, st.country), ("goldvibe", Some("MT")))
  }

  Given("a player already attributed at brand {string}") { (brand: String) => attributed(Scene(kit, brandOf(brand))) }
  When("a second {string} fact for the same player at brand {string} is consumed") { (kind: String, brand: String) =>
    assertEquals((kind, brandOf(brand)), ("registration", s.brand))
    send(s.facts.registration(p, kit.now)): Unit
  }
  Then("it is ignored as a duplicate") { () => assertEquals(outcome, "duplicate") }

  // ── deposits and CPA ────────────────────────────────────────────────────────────────────────────

  Given("an attributed player on a CPA or hybrid plan with a CPA of {string} in {string} and a minimum deposit of {string}") {
    (cpa: String, currency: String, minimum: String) =>
      attributed(Scene(kit, planType = Plan.Hybrid, cpa = cpa.toLong, currency = currency, minimumDeposit = minimum.toLong))
  }
  Given("an attributed player on a CPA or hybrid plan with a minimum deposit of {string}") { (minimum: String) =>
    attributed(Scene(kit, planType = Plan.Cpa, cpa = 5_000, revShareBp = 0, minimumDeposit = minimum.toLong))
  }

  /** A brand emits a first deposit as two facts, `first-deposit` then `deposit` (spec 012); both are sent. */
  private def firstDeposit(amount: Long, currency: String): Unit =
    val when = kit.now.minusSeconds(3_600)
    send(s.facts.firstDeposit(p, amount, when, currency)): Unit
    val out = outcome
    send(s.facts.deposit(p, amount, when, currency)): Unit
    outcome = out

  When("a {string} fact of {string} in {string} arrives") { (kind: String, amount: String, currency: String) =>
    assertEquals(kind, "first-deposit")
    firstDeposit(amount.toLong, currency)
  }
  When("a {string} fact of {string} arrives") { (kind: String, amount: String) =>
    assertEquals(kind, "first-deposit")
    firstDeposit(amount.toLong, "EUR")
  }
  Then("one CPA fact of {string} is written in {string}, dated when the deposit occurred") { (amount: String, currency: String) =>
    val rows = s.rowsWhen("the CPA")(_.exists(_.kind == Cpa))
    val cpa  = rows.filter(_.kind == Cpa)
    assertEquals(cpa.map(r => (r.amount, r.currency, r.occurredAt)), Vector((amount.toLong, currency, sent.head.occurredAt)))
  }
  Then("the deposit is counted in the player's totals in {string}") { (currency: String) =>
    val d = s.state(p).deposits(currency)
    assertEquals((d.count, d.sum), (1, sent.collect { case f: io.eitheror.casino.contract.facts.Deposit => f.amount.value }.sum))
  }
  Then("no CPA fact is written") { () => assertEquals(settled().filter(_.kind == Cpa), Vector.empty) }
  Then("the player's first-deposit guard is not set, so a later deposit that meets the minimum deposit pays CPA") { () =>
    assert(s.state(p).guard.isEmpty)
    assertEquals(send(s.facts.deposit(p, 2_000, kit.now)), "first-deposit")
    assertEquals(s.sum(s.rowsWhen("the CPA")(_.exists(_.kind == Cpa)), Cpa), 5_000L)
  }

  Given("an attributed player whose first deposit paid CPA") { () =>
    attributed(Scene(kit, planType = Plan.Cpa, cpa = 5_000, revShareBp = 0, minimumDeposit = 1_000))
    firstDeposit(2_000, "EUR")
    s.rowsWhen("the first CPA")(_.exists(_.kind == Cpa)): Unit
  }
  When("a {string} fact arrives in a later period") { (kind: String) =>
    assertEquals(kind, "deposit")
    send(s.facts.deposit(p, 3_000, kit.now.plusSeconds(40L * 86_400))): Unit
  }
  Then("no second CPA fact is written") { () => assertEquals(settled().count(_.kind == Cpa), 1) }
  Then("the deposit is counted in the player's totals") { () =>
    assertEquals((s.state(p).deposits("EUR").count, s.state(p).deposits("EUR").sum), (2, 5_000L))
  }

  // ── NGR and RevShare ────────────────────────────────────────────────────────────────────────────

  Given("an attributed player on a RevShare or hybrid plan") { () =>
    fees("silvervibe", Fees(admin = 100, payment = 200, provider = 300))
    attributed(Scene(kit, brand = "silvervibe", revShareBp = 3_000))
    send(s.facts.deposit(p, 5_000, kit.now)): Unit
    s.rowsWhen("the payment fee")(_.exists(_.kind == PaymentFee)): Unit
  }
  When("{string}, {string}, {string} and {string} facts arrive") { (a: String, b: String, c: String, d: String) =>
    assertEquals(Vector(a, b, c, d), Vector("ggr-daily", "ggr-adjustment", "bonus-cost", "chargeback"))
    val day = "2026-10-25"
    Vector(s.facts.ggrDaily(p, day, 10_000, 0, kit.now), s.facts.ggrAdjustment(p, day, -4_000, kit.now),
      s.facts.bonusCost(p, 1_000, kit.now), s.facts.chargeback(p, 500, kit.now)).foreach(send(_))
  }
  Then("NGR is folded as GGR minus bonus cost, chargebacks and the brand's admin, payment and provider fee percentages") { () =>
    val rows = s.rowsWhen("the chargeback's RevShare")(_.count(_.kind == RevShare) == 4)
    // GGR 6,000; admin 1% = 60, provider 3% = 180, payment 2% of 5,000 = 100; NGR = 6,000 - 1,000 - 500 - 340 = 4,160.
    assertEquals((s.sum(rows, Ggr), s.sum(rows, AdminFee), s.sum(rows, ProviderFee), s.sum(rows, PaymentFee)), (6_000L, 60L, 180L, 100L))
    seen = rows.filter(_.kind == RevShare).sortBy(_.recordedAt).map(_.amount)
  }
  Then("a RevShare fact is written for exactly the delta between the commission on the cumulative NGR and what has already been emitted, positive or negative") { () =>
    // After each input: NGR 9,500 → 2,850; 5,660 → 1,698; 4,660 → 1,398; 4,160 → 1,248.
    assertEquals(seen.sorted, Vector(2_850L, -1_152L, -300L, -150L).sorted)
    assertEquals(seen.sum, Commission.onNgr(4_160, 3_000))
  }

  private val day            = "2026-03-20"

  Given("brand {string} whose GGR source is {string}") { (brand: String, src: String) =>
    assertEquals(brand, "A")
    fees("silvervibe", Fees(admin = 100))
    Scene.setGgrSource(kit, "silvervibe", src, day)
    restore += (() => Scene.setGgrSource(kit, "silvervibe", GgrSource.Daily, "2026-03-21"))
  }
  Given("an attributed player at brand {string} with {string} and {string} facts for the same day") { (_: String, a: String, b: String) =>
    assertEquals(Set(a, b), Set("ggr-daily", "ggr-round"))
    attributed(Scene(kit, brand = "silvervibe"), at(s"${day}T09:00:00Z"))
    pending = s.facts.ggrDaily(p, day, 2_000, 0, at(s"${day}T23:00:00Z"))
    round = s.facts.ggrRound(p, 300, 0, at(s"${day}T12:00:00Z"))
    both = () => { send(round): Unit; send(pending): Unit }
  }
  private var round: AttributionFact = null
  /** What "both are consumed" sends: the scenario's Given arranges it. */
  private var both: () => Unit = () => fail("no Given arranged two facts")
  When("both are consumed") { () => both() }
  Then("only the {string} facts fold into NGR and write fee facts") { (kind: String) =>
    val folded = if kind == "ggr-daily" then pending else round
    val rows   = s.rowsWhen("the folded GGR")(_.exists(_.kind == AdminFee))
    assertEquals(rows.filter(_.kind == Ggr).map(_.factId), Vector(s"ggr/${folded.factId}"))
    assertEquals(rows.filter(_.kind == AdminFee).map(_.factId), Vector(s"admin-fee/${folded.factId}"))
  }
  Then("the {string} facts are recorded for reconciliation") { (kind: String) =>
    val other = if kind == "ggr-daily" then pending else round
    assertEquals(s.outcome(other.factId), "ignored:other_source")
  }

  Given("a player bound by a late bonus code") { () => attributed(Scene(kit)) }
  When("a {string} fact marked as before the binding arrives") { (kind: String) =>
    assertEquals(kind, "ggr-daily")
    send(s.facts.ggrDaily(p, "2026-10-20", 3_000, 0, kit.now, beforeBinding = Some(true))): Unit
  }
  Then("it is recorded on the player") { () =>
    assertEquals(outcome, "before-binding")
    assertEquals(s.state(p).beforeBinding.map(_.ggr), Vector(3_000L))
  }
  Then("it folds into no NGR and writes no fee or commission fact") { () => assertEquals(settled(), Vector.empty) }

  Given("an attributed player on a plan without negative carryover") { () =>
    attributed(Scene(kit, carryover = false), at("2026-08-01T09:00:00Z"))
  }
  Given("an attributed player on a plan with negative carryover") { () =>
    attributed(Scene(kit, carryover = true), at("2026-08-01T09:00:00Z"))
  }
  When("a period's NGR is negative") { () =>
    send(s.facts.ggrDaily(p, "2026-09-15", 0, 1_000, at("2026-09-15T12:00:00Z"))): Unit
  }
  Then("that period's RevShare floors at zero") { () =>
    s.rowsWhen("September's GGR")(_.exists(_.kind == Ggr)): Unit
    assertEquals(s.sum(settled(), RevShare, period = Some("2026-09")), 0L)
  }
  Then("the next period starts fresh") { () =>
    send(s.facts.ggrDaily(p, "2026-10-15", 2_000, 0, at("2026-10-15T12:00:00Z"))): Unit
    s.rowsWhen("October's RevShare: 30% of 2,000")(rs => s.sum(rs, RevShare, period = Some("2026-10")) == 600): Unit
  }
  Then("the deficit persists until positive NGR covers it") { () =>
    send(s.facts.ggrDaily(p, "2026-10-15", 500, 0, at("2026-10-15T12:00:00Z"))): Unit
    s.rowsWhen("October's GGR")(rs => s.sum(rs, Ggr) == -500): Unit
    assertEquals(s.sum(settled(), RevShare), 0L)
    send(s.facts.ggrDaily(p, "2026-10-16", 1_500, 0, at("2026-10-16T12:00:00Z"))): Unit
    s.rowsWhen("RevShare on the lifetime 1,000: 300")(rs => s.sum(rs, RevShare) == 300): Unit
  }

  // ── duplicates and unattributed facts ───────────────────────────────────────────────────────────

  Given("a fact whose id has been consumed before") { () =>
    attributed(Scene(kit))
    pending = s.facts.ggrDaily(p, "2026-10-25", 1_000, 0, kit.now)
    send(pending): Unit
    s.rowsWhen("the GGR")(_.exists(_.kind == Ggr)): Unit
    duplicates = Scene.brand(kit, s.brand).duplicates
  }
  When("it arrives again on any carrier") { () => kit.publish(pending) }
  Then("nothing is written") { () => assertEquals(settled().count(_.kind == Ggr), 1) }
  Then("the duplicate is counted") { () =>
    kit.eventually("the duplicate counted")(Option(Scene.brand(kit, s.brand).duplicates).filter(_ > duplicates)): Unit
  }

  Given("a fact for a player the programme has no attribution for at that brand") { () =>
    s = Scene(kit)
    p = kit.fresh("ps")
    pending = s.facts.deposit(p, 5_000, kit.now)
  }
  Then("it is recorded as unattributed for the brand's manager to see") { () =>
    assertEquals(outcome, "unattributed:no_attribution")
  }
  Then("no commission is written") { () => assertEquals(settled(), Vector.empty) }

  // ── withdrawals ─────────────────────────────────────────────────────────────────────────────────

  private var totals: Vector[Long] = Vector.empty

  Given("a {string} fact and later a {string} fact with the same reference") { (a: String, b: String) =>
    assertEquals((a, b), ("withdrawal-completed", "withdrawal-reversed"))
    attributed(Scene(kit))
    val done = s.facts.withdrawalCompleted(p, 1_000, kit.now)
    val back = s.facts.withdrawalReversed(p, 1_500, kit.now).copy(reference = done.reference)
    both = () =>
      totals = Vector(done, back).map { f => send(f): Unit; s.state(p).withdrawals.getOrElse("EUR", 0L) }
  }
  Then("the player's withdrawal total rises then falls by that amount and never below zero") { () =>
    // The reversal names 1,500 against a completed 1,000: the total falls to zero, not below.
    assertEquals(totals, Vector(1_000L, 0L))
  }

  Given("an attributed player") { () => attributed(Scene(kit)) }
  Given("an attributed player with no completed withdrawal") { () => attributed(Scene(kit)) }
  When("a {string} fact is consumed") { (kind: String) =>
    val f = kind match
      case "withdrawal-requested" => s.facts.withdrawalRequested(p, 700, kit.now)
      case "withdrawal-failed"    => s.facts.withdrawalFailed(p, 700, kit.now)
      case "withdrawal-reversed"  => s.facts.withdrawalReversed(p, 700, kit.now)
      case other                  => fail(s"no fact named $other here")
    send(f): Unit
  }
  Then("no total changes") { () =>
    assert(outcome.startsWith("ignored:"), outcome)
    assertEquals((s.state(p).withdrawals, s.state(p).deposits), (Map.empty[String, Long], Map.empty))
  }
  Then("it is ignored") { () => assertEquals(outcome, "ignored:no_completed_withdrawal") }
