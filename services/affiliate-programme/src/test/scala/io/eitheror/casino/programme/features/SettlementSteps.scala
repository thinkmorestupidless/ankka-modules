package io.eitheror.casino.programme.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.activity.Activity
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.csv.Export
import io.eitheror.casino.programme.ledger.{AccountState, CommissionAccount, RecordCpa}
import io.eitheror.casino.programme.it.Reply
import io.eitheror.casino.programme.settlement.{Payout, PayoutState, Payouts}

import java.time.{Instant, YearMonth, ZoneOffset}
import java.util.concurrent.atomic.AtomicInteger

/**
 * features/programme/settlement.feature (US4). Brand "A" is goldvibe. Each scenario closes two months
 * of its own in 2024, so its closes sweep only what it wrote: the other suites in this JVM date their
 * facts in October 2026.
 */
class SettlementSteps extends ProgrammeFeatures("settlement.feature"):

  private def slug(name: String) = if name == "A" then "goldvibe" else name

  private var month: YearMonth     = null
  private var aff: String          = null
  private var payoutId: EntityId   = EntityId("none")
  private var lateId: String       = null
  private var csv: String          = null
  private var before: PayoutState  = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    month = SettlementSteps.Start.plusMonths(2L * SettlementSteps.next.getAndIncrement())
    aff = null; payoutId = EntityId("none"); lateId = null; csv = null; before = null

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private def period(m: YearMonth = month) = m.toString
  private def day(m: YearMonth, d: Int): Instant = m.atDay(d).atTime(10, 0).toInstant(ZoneOffset.UTC)
  private def end(m: YearMonth = month): Instant = m.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1)

  private def newAffiliate(): String = kit.affiliate(id("payee"), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 5_000))

  private def accountOf(who: String, currency: String = "EUR") =
    kit.components.forEventSourcedEntity(CommissionAccount.entityId(who, "goldvibe", currency))
  private def state(who: String = aff, currency: String = "EUR"): AccountState = accountOf(who, currency).call(CommissionAccount.get).invoke()

  /** A CPA dated `at`, waited for in the view a close reads. */
  private def earn(who: String, amount: Long, at: Instant, currency: String = "EUR"): String =
    val fact  = kit.fresh("cpa")
    accountOf(who, currency).call(CommissionAccount.recordCpa).invoke(RecordCpa(fact, amount, at, "UTC", kit.now)): Unit
    kit.owed(who, "goldvibe", currency)
    fact

  private def close(m: YearMonth = month): Reply =
    val r = kit.request("POST", s"/v1/affiliates/goldvibe/payouts/close?period=${period(m)}", headers = kit.asManager())
    assertEquals(r.status, 200, r.text)
    r

  private def payoutOf(who: String = aff, m: YearMonth = month, currency: String = "EUR"): EntityId =
    Payout.entityId(who, "goldvibe", currency, period(m))
  private def payout(id: EntityId = payoutId): PayoutState = kit.components.forEventSourcedEntity(id).call(Payout.get).invoke()
  private def wire(id: EntityId): String =
    val p = payout(id)
    Payouts.wireId(p.affiliate, p.currency, p.period)

  private def move(id: EntityId, body: String): Reply =
    kit.request("PATCH", s"/v1/affiliates/goldvibe/payouts/${wire(id)}/status", Some(body), kit.asManager())

  private def approve(id: EntityId): Unit = assertEquals(move(id, """{"status":"APPROVED"}""").status, 200)

  /** A payout of 5000 written by a close of this scenario's month. */
  private def pending(): EntityId =
    aff = newAffiliate()
    earn(aff, 5_000, day(month, 10))
    close(): Unit
    payoutId = payoutOf()
    payoutId

  private def stampedWith(id: EntityId, s: AccountState = state()) = s.unsettled.filter(_.settledBy.contains(id.toString))

  // ── a close ─────────────────────────────────────────────────────────────────────────────────────

  Given("unclaimed payable facts for an affiliate at brand {string} in {string}") { (brand: String, currency: String) =>
    assertEquals(slug(brand), "goldvibe")
    aff = newAffiliate()
    earn(aff, 5_000, day(month, 10), currency)
    earn(aff, 3_000, day(month, 28), currency)
    lateId = earn(aff, 2_000, day(month.plusMonths(1), 3), currency)
  }
  When("a manager closes the period for brand {string}") { (brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    last = close()
  }
  Then("every unclaimed payable fact that occurred on or before the period's end is stamped with the payout for that affiliate at brand {string} in {string} for the period") {
    (brand: String, currency: String) =>
      payoutId = payoutOf(aff, month, currency)
      val s = state(aff, currency)
      assertEquals(s.unsettled.filter(!_.occurredAt.isAfter(end())).map(_.settledBy).distinct, Vector(Some(payoutId.toString)))
      assertEquals(s.unsettled.find(_.id == lateId).flatMap(_.settledBy), None)
  }
  Then("the payout's total is the fold of everything stamped with it") { () =>
    assertEquals(payout().total, stampedWith(payoutId).map(_.amount).sum)
    assertEquals(payout().total, 8_000L)
  }
  Then("the payout is pending") { () => assertEquals(payout().status, Some(Payout.Pending)) }

  Given("an affiliate whose claimed total is below their minimum payout threshold") { () =>
    aff = newAffiliate()
    val r = kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/$aff", Some("""{"minimumPayout":{"EUR":10000}}"""), kit.asManager())
    assertEquals(r.status, 200, r.text)
    earn(aff, 5_000, day(month, 10))
  }
  Then("nothing is stamped") { () => assertEquals(state().unsettled.flatMap(_.settledBy), Vector.empty) }
  Then("no payout is written") { () => assertEquals(payout(payoutOf()).status, None) }
  Then("the facts carry forward to the next close") { () =>
    earn(aff, 6_000, day(month.plusMonths(1), 10))
    close(month.plusMonths(1)): Unit
    assertEquals(payout(payoutOf(aff, month.plusMonths(1))).total, 11_000L)
  }

  // ── the override figure ─────────────────────────────────────────────────────────────────────────

  Given("a pending payout whose claimed override is {string}") { (amount: String) =>
    aff = newAffiliate()
    // A sub on 10% earns ten times the override; the override is dated in the sub's period.
    val sub = kit.affiliate(id("sub"), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 5_000), parent = Some(aff),
      overrideBp = 1_000)
    earn(sub, amount.toLong * 10, day(month, 10))
    kit.eventually("the override")(Option(state()).filter(_.unsettled.exists(_.amount == amount.toLong))): Unit
    kit.owed(aff, "goldvibe", "EUR")
    close(): Unit
    payoutId = payoutOf()
    assertEquals((payout().status, payout().figures.overrideAmount), (Some(Payout.Pending), amount.toLong))
  }
  When("a manager approves it with an override figure of {string}") { (figure: String) =>
    last = move(payoutId, s"""{"status":"APPROVED","overrideCommission":$figure}""")
    assertEquals(last.status, 200, last.text)
  }
  Then("an adjustment fact of {string} is written and claimed by the payout") { (amount: String) =>
    val adjustments = stampedWith(payoutId).filter(_.id.startsWith("override-figure/"))
    assertEquals(adjustments.map(_.amount), Vector(amount.toLong))
  }
  Then("the payout is approved with a total that is the fold of everything it claims") { () =>
    val p = payout()
    assertEquals(p.status, Some(Payout.Approved))
    assertEquals(p.total, stampedWith(payoutId).map(_.amount).sum)
  }

  // ── late facts ──────────────────────────────────────────────────────────────────────────────────

  Given("an approved payout") { () => approve(pending()) }
  When("a late fact is swept into it by a later close") { () =>
    earn(aff, 1_000, day(month, 20))
    close(): Unit
  }
  Then("the payout reverts to pending") { () =>
    assertEquals((payout().status, payout().total), (Some(Payout.Pending), 6_000L))
  }
  Then("the previous approval is recorded as superseded") { () =>
    assertEquals(payout().supersededApprovals.map(s => (s.by, s.total)), Vector(("mgr-1", 5_000L)))
  }

  Given("a paid payout") { () =>
    approve(pending())
    assertEquals(move(payoutId, """{"status":"PAID"}""").status, 200)
    before = payout()
  }
  When("a late fact arrives that would have belonged to it") { () =>
    lateId = earn(aff, 1_000, day(month, 20))
    close(): Unit
  }
  Then("the payout is unchanged") { () => assertEquals(payout(), before) }
  Then("the fact is claimed by the next close, dated when it occurred") { () =>
    close(month.plusMonths(1)): Unit
    val fact = state().unsettled.find(_.id == lateId)
    assertEquals(fact.flatMap(_.settledBy), Some(payoutOf(aff, month.plusMonths(1)).toString))
    assertEquals(fact.map(_.period), Some(period()))
  }

  // ── currencies and the export ───────────────────────────────────────────────────────────────────

  Given("an affiliate with facts in {string} and in {string} at brand {string}") { (a: String, b: String, brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    aff = newAffiliate()
    earn(aff, 5_000, day(month, 10), a)
    earn(aff, 7_000, day(month, 11), b)
    ids("currencies") = s"$a,$b"
  }
  Then("two payouts are written, one per currency") { () =>
    val currencies = ids("currencies").split(',').toVector
    assertEquals(currencies.map(c => payout(payoutOf(aff, month, c)).total), Vector(5_000L, 7_000L))
  }
  Then("the export lists both") { () =>
    ids("currencies").split(',').foreach(c => approve(payoutOf(aff, month, c)))
    val rows = exported().filter(_.head == aff)
    assertEquals(rows.map(_(5)).sorted, ids("currencies").split(',').toVector.sorted)
  }

  /** The export's rows, once the view lists every approved payout this scenario expects. */
  private def exported(expect: Int = 2): Vector[Vector[String]] =
    kit.eventually("the approved payouts") {
      val r = kit.request("GET", s"/v1/affiliates/goldvibe/payouts/export?period=${period()}", headers = kit.asManager())
      assertEquals(r.status, 200, r.text)
      csv = r.text
      val rows = r.text.split("\r\n").toVector.map(_.split(",", -1).toVector)
      Option(rows).filter(_.count(_.head == aff) >= expect)
    }.tail

  Given("approved payouts for a period") { () =>
    approve(pending())
    val r = kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/$aff", Some("""{"paymentMethod":"bank-transfer","displayName":"Payee Ltd"}"""),
      kit.asManager())
    assertEquals(r.status, 200, r.text)
  }
  When("finance exports them") { () => exported(1): Unit }
  Then("the file lists affiliate, display name, email, payment details, brand, currency, period, CPA, RevShare, override, adjustments, total, who approved and when") {
    () =>
      val lines = csv.split("\r\n").toVector
      assertEquals(lines.head, Export.Header.mkString(","))
      val row = lines.map(_.split(",", -1).toVector).find(_.head == aff).get
      val p   = payout()
      assertEquals(row.take(13), Vector(aff, "Payee Ltd", s"$aff@example.com", "bank-transfer", "goldvibe", "EUR", period(), "5000", "0", "0", "0",
        "5000", "mgr-1"))
      assertEquals(row(13), p.approvedAt.get.toString)
  }
  Then("the export is audited as a read of payment details") { () =>
    kit.eventually("the audit") {
      kit.views.forView(Activity).ask(Activity.byAction, "action" -> "payment_details_read")
        .find(a => a.action == "payment_details_read" && a.details.get("period").contains(period()) && a.details.get("affiliates").exists(_.contains(aff)))
    }: Unit
  }

  // ── the status machine ──────────────────────────────────────────────────────────────────────────

  Given("a {string} payout") { (status: String) =>
    pending(): Unit
    status match
      case Payout.Paid =>
        approve(payoutId)
        assertEquals(move(payoutId, """{"status":"PAID"}""").status, 200)
      case Payout.Rejected => assertEquals(move(payoutId, """{"status":"REJECTED","reason":"fraud"}""").status, 200)
      case Payout.Approved => approve(payoutId)
      case _               => ()
    assertEquals(payout().status, Some(status))
  }
  When("it is moved to {string}") { (target: String) => last = move(payoutId, s"""{"status":"${target.toUpperCase}"}""") }
  Then("it is refused, naming {string}") { (status: String) =>
    assertEquals((last.status, last.json.get("error").asText), (409, "status_transition"), last.text)
    assert(last.json.get("message").asText.contains(status), last.text)
  }

object SettlementSteps:
  private val Start = YearMonth.of(2024, 1)
  private val next  = AtomicInteger(0)
