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
    def recordNgr(a: EntityId, c: RecordNgr): AccountState  = accountOf(a).call(CommissionAccount.recordNgr)(c).replyValue
    def recordCpa(a: EntityId, c: RecordCpa): Unit          = accountOf(a).call(CommissionAccount.recordCpa)(c): Unit
    def recordPaymentFee(a: EntityId, c: RecordPaymentFee): Unit = accountOf(a).call(CommissionAccount.recordPaymentFee)(c): Unit
    val players = mutable.Map.empty[String, io.eitheror.casino.programme.players.PlayerState]
    val flags   = mutable.Map.empty[String, io.eitheror.casino.programme.activity.RaiseFlag]
    def player(brand: String, p: String) = players.getOrElse(s"$brand/$p", io.eitheror.casino.programme.players.PlayerState())
    def raiseFlag(id: EntityId, c: io.eitheror.casino.programme.activity.RaiseFlag): Unit = flags.getOrElseUpdate(id.toString, c): Unit
    var postbackUrl: Option[String] = None
    val postbacks = mutable.Map.empty[String, io.eitheror.casino.programme.postbacks.SendPostback]
    def account(a: String) = io.eitheror.casino.programme.accounts.AffiliateState(id = a, postbackUrl = postbackUrl)
    def postback(id: EntityId, c: io.eitheror.casino.programme.postbacks.SendPostback): Unit = postbacks.getOrElseUpdate(id.toString, c): Unit
    def facts(id: String) = accounts.get(id).fold(Vector.empty[io.eitheror.casino.programme.commission.LedgerFact])(_.call(CommissionAccount.get).replyValue.unsettled)
    def sums(id: String)  = accounts(id).call(CommissionAccount.get).replyValue.sums
    val marks = mutable.Map.empty[String, KeyValueEntityTestKit[LedgerMark, LedgerMarkState]]
    private def markOf(subject: String) = marks.getOrElseUpdate(subject, KeyValueEntityTestKit.of(LedgerMark, subject))
    def marked(subject: String): Long          = markOf(subject).call(LedgerMark.get).replyValue.seq
    def mark(subject: String, seq: Long): Unit = markOf(subject).call(LedgerMark.mark)(seq): Unit

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

  test("an event at or below the player's mark writes nothing, however far past the account's window of ids; a later one does") {
    val c   = calls()
    val src = (n: Long) => Some(SourceEvent("goldvibe/ps-1", n))
    writer(c).write(applied(PlayerFact.GgrDaily, "g-1", 1_000, "EUR", Outcome.Ngr("ggr", 1_000)), src(1))
    assertEquals(c.marked("goldvibe/ps-1"), 1L)
    // The same event redelivered under an id the window no longer holds (as one dropped from it would be).
    writer(c).write(applied(PlayerFact.GgrDaily, "g-1-again", 1_000, "EUR", Outcome.Ngr("ggr", 1_000)), src(1))
    writer(c).write(applied(PlayerFact.GgrDaily, "g-0", 1_000, "EUR", Outcome.Ngr("ggr", 1_000)), src(0))
    assertEquals(c.sums("aff-1/goldvibe/EUR").amount(FactType.Ggr), 1_000L)
    writer(c).write(applied(PlayerFact.GgrDaily, "g-2", 500, "EUR", Outcome.Ngr("ggr", 500)), src(2))
    assertEquals((c.sums("aff-1/goldvibe/EUR").amount(FactType.Ggr), c.marked("goldvibe/ps-1")), (1_500L, 2L))
  }

  test("LedgerMark only moves forward") {
    val k = KeyValueEntityTestKit.of(LedgerMark, "goldvibe/ps-9")
    k.call(LedgerMark.mark)(5L): Unit
    assert(!k.call(LedgerMark.mark)(3L).changed)
    assertEquals(k.call(LedgerMark.get).replyValue.seq, 5L)
  }

  test("a qualifying first deposit writes CPA in the plan's currency; its deposit fact the payment fee, once") {
    val c = calls()
    writer(c).write(applied(PlayerFact.FirstDeposit, "fd-1", 2_000, "USD", Outcome.FirstDeposit(true)))
    writer(c).write(applied(PlayerFact.Deposit, "d-1", 2_000, "USD", Outcome.Deposit))
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
