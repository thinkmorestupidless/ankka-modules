package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.*
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.players.*

import java.time.Instant
import scala.collection.mutable

class ToLedgerSuite extends munit.FunSuite with LogCapturing:
  private val at   = Instant.parse("2026-10-15T10:00:00Z")
  private val plan = Plan.idOf("goldvibe", "Hybrid")

  private final class Calls extends LedgerCalls:
    val brandKit   = KeyValueEntityTestKit.of(BrandProgramme, "goldvibe")
    val planKit    = KeyValueEntityTestKit.of(Plan, s"goldvibe/$plan")
    val member     = EventSourcedTestKit.of(Membership, "aff-1/goldvibe")
    val accounts   = mutable.Map.empty[String, EventSourcedTestKit[CommissionAccount, AccountState, CommissionAccount.Event]]
    def accountOf(id: EntityId) = accounts.getOrElseUpdate(id.toString, EventSourcedTestKit.of(CommissionAccount, id.toString))
    def brand(slug: String): BrandState                     = brandKit.call(BrandProgramme.get).replyValue
    def membership(a: String, b: String): MembershipState   = member.call(Membership.get).replyValue
    def plan(brand: String, p: String): PlanState           = planKit.call(Plan.get).replyValue
    def recordNgr(a: EntityId, c: RecordNgr): Unit          = accountOf(a).call(CommissionAccount.recordNgr)(c): Unit
    def recordCpa(a: EntityId, c: RecordCpa): Unit          = accountOf(a).call(CommissionAccount.recordCpa)(c): Unit
    def recordPaymentFee(a: EntityId, c: RecordPaymentFee): Unit = accountOf(a).call(CommissionAccount.recordPaymentFee)(c): Unit
    def facts(id: String) = accounts.get(id).fold(Vector.empty[io.eitheror.casino.programme.commission.LedgerFact])(_.call(CommissionAccount.get).replyValue.unsettled)
    def sums(id: String)  = accounts(id).call(CommissionAccount.get).replyValue.sums

  private def calls(active: Boolean = true) =
    val c = Calls()
    c.brandKit.call(BrandProgramme.seed)(SeedBrand("goldvibe", at)): Unit
    c.brandKit.call(BrandProgramme.update)(UpdateBrand(fees = Some(Fees(admin = 1_000, payment = 200)))): Unit
    c.planKit.call(Plan.createPlan)(CreatePlan("goldvibe", "Hybrid", Plan.Hybrid, 5_000, "EUR", 2_500, 2_000, false, "m", at)): Unit
    c.member.call(Membership.applyFor)(Apply("aff-1", "goldvibe", Application(at, None, "form"))): Unit
    c.member.call(Membership.approve)(Approve(plan, "m", at)): Unit
    if !active then c.member.call(Membership.suspend)(Suspend("m", Membership.Operator, at)): Unit
    c
  private val binding = Some(Binding("aff-1", "lnk-1", None, Vector.empty, None, at))
  private def applied(kind: String, id: String, amount: Long, currency: String, o: Outcome) =
    PlayerAttribution.Applied(PlayerFact(kind, id, at, "goldvibe", "ps-1", "aff-1", "lnk-1", currency = Some(currency), amount = amount), binding, o)
  private def writer(c: Calls) = LedgerWriter(c, () => at.plusSeconds(60))

  test("a qualifying first deposit writes CPA in the plan's currency and the payment fee in the deposit's") {
    val c = calls()
    writer(c).write(applied(PlayerFact.FirstDeposit, "d-1", 2_000, "USD", Outcome.FirstDeposit(true)))
    assertEquals(c.facts("aff-1/goldvibe/EUR").map(f => (f.id, f.kind, f.amount)), Vector(("cpa/goldvibe/ps-1", FactType.Cpa, 5_000L)))
    assertEquals(c.sums("aff-1/goldvibe/USD").amount(FactType.PaymentFee), 40L)
  }

  test("a suspended membership gets no CPA and no payable RevShare, but its NGR inputs") {
    val c = calls(active = false)
    writer(c).write(applied(PlayerFact.FirstDeposit, "d-1", 2_000, "EUR", Outcome.FirstDeposit(true)))
    writer(c).write(applied(PlayerFact.GgrDaily, "g-1", 10_000, "EUR", Outcome.Ngr("ggr", 10_000)))
    assertEquals(c.facts("aff-1/goldvibe/EUR"), Vector.empty)
    assertEquals(c.sums("aff-1/goldvibe/EUR").amount(FactType.Ggr), 10_000L)
  }

  test("an NGR input folds at the membership's RevShare override when it has one, with the brand's fees") {
    val c = calls()
    c.member.call(Membership.setRevShareOverride)(SetRevShareOverride(Some(5_000), "m", at)): Unit
    writer(c).write(applied(PlayerFact.GgrDaily, "g-1", 10_000, "EUR", Outcome.Ngr("ggr", 10_000)))
    val s = c.sums("aff-1/goldvibe/EUR")
    assertEquals((s.amount(FactType.AdminFee), s.amount(FactType.RevShare)), (1_000L, 4_500L))
  }

  test("an unbound or ignored fact writes nothing") {
    val c = calls()
    writer(c).write(applied(PlayerFact.Deposit, "d", 1, "EUR", Outcome.Deposit).copy(binding = None))
    writer(c).write(applied(PlayerFact.GgrRound, "g", 1, "EUR", Outcome.Ignored("other_source")))
    assert(c.accounts.isEmpty)
  }
