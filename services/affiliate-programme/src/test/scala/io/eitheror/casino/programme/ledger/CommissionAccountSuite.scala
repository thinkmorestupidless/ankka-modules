package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.programme.commission.*

import java.time.Instant

class CommissionAccountSuite extends munit.FunSuite with LogCapturing:
  private val oct  = Instant.parse("2026-10-15T10:00:00Z")
  private val nov  = Instant.parse("2026-11-15T10:00:00Z")
  private val now  = Instant.parse("2026-11-20T10:00:00Z")
  private val octEnd = Instant.parse("2026-10-31T23:59:59.999Z")
  private type Kit = EventSourcedTestKit[CommissionAccount, AccountState, CommissionAccount.Event]
  private def kit: Kit = EventSourcedTestKit.of(CommissionAccount, "aff-1/goldvibe/EUR")
  private val period   = Rate(2500, Window.Period, payable = true)
  private val lifetime = Rate(2500, Window.Lifetime, payable = true)
  private def ngr(k: Kit, id: String, kind: String, amount: Long, at: Instant, rate: Rate = period, fees: FeeRates = FeeRates()) =
    k.call(CommissionAccount.recordNgr)(RecordNgr(id, kind, amount, at, rate, fees, "UTC", now))
  private def facts(k: Kit) = k.call(CommissionAccount.get).replyValue
  private def payable(k: Kit, t: FactType) = facts(k).unsettled.filter(_.kind == t).map(_.amount)

  test("an NGR input writes its RevShare delta, and is idempotent on its fact id") {
    val k = kit
    ngr(k, "g1", "ggr", 10_000, oct): Unit
    assertEquals(payable(k, FactType.RevShare), Vector(2_500L))
    assert(!ngr(k, "g1", "ggr", 10_000, oct).persisted)
    ngr(k, "b1", "bonus-cost", 2_000, oct): Unit
    assertEquals(payable(k, FactType.RevShare), Vector(2_500L, -500L))
  }

  test("an event already applied writes nothing, however far past the id window; a later event, or another kind from it, does") {
    val k   = kit
    val src = (n: Long) => Some(SourceEvent("goldvibe/ps-1", n))
    def ngrFrom(id: String, n: Long) = k.call(CommissionAccount.recordNgr)(RecordNgr(id, "ggr", 1_000, oct, period, FeeRates(), "UTC", now, src(n)))
    assert(ngrFrom("g1", 1).persisted)
    // The same event again under an id the window never held (as one dropped from it would be).
    assert(!ngrFrom("g1-again", 1).persisted)
    assert(!ngrFrom("g0", 0).persisted)
    assert(ngrFrom("g2", 2).persisted)
    assert(k.call(CommissionAccount.recordPaymentFee)(RecordPaymentFee("d2", 10_000, 100, oct, "UTC", now, Some(period), src(2))).persisted)
    // The window drops an id; what the source applied stays.
    val applied = Vector(
      CommissionAccount.FactWritten(LedgerFact("ggr/g1", FactType.Ggr, 1, oct, now, "2026-10")),
      CommissionAccount.SourceApplied("ngr", "goldvibe/ps-1", 1)
    ) ++ (1 to CommissionAccount.MaxWritten).map(i => CommissionAccount.FactWritten(LedgerFact(s"adj/$i", FactType.Adjustment, 1, oct, now, "2026-10")))
    val state = applied.foldLeft(AccountState())(CommissionAccount.next)
    assert(!state.has("ggr/g1"))
    assert(state.seenBefore("ngr", src(1)) && !state.seenBefore("ngr", src(2)) && !state.seenBefore("cpa", src(1)))
  }

  test("an input that is not NGR is refused") {
    assert(ngr(kit, "x", "cpa", 1, oct).isError)
  }

  test("without carryover a negative period floors at zero and the next starts fresh") {
    val k = kit
    ngr(k, "g1", "ggr", -8_000, oct): Unit
    ngr(k, "g2", "ggr", 4_000, nov): Unit
    assertEquals(payable(k, FactType.RevShare), Vector(1_000L))
  }

  test("with carryover the deficit persists until positive NGR covers it") {
    val k = kit
    ngr(k, "g1", "ggr", -8_000, oct, lifetime): Unit
    ngr(k, "g2", "ggr", 4_000, nov, lifetime): Unit
    assertEquals(payable(k, FactType.RevShare), Vector.empty)
    ngr(k, "g3", "ggr", 8_000, nov, lifetime): Unit
    assertEquals(payable(k, FactType.RevShare), Vector(1_000L))
  }

  test("each GGR fact writes its admin and provider fees, unfloored, and they reduce NGR") {
    val k = kit
    ngr(k, "g1", "ggr", 10_000, oct, fees = FeeRates(admin = 1_000, provider = 500)): Unit
    ngr(k, "g2", "ggr", -2_000, oct, fees = FeeRates(admin = 1_000, provider = 500)): Unit
    val s = facts(k).sums
    assertEquals((s.amount(FactType.AdminFee), s.amount(FactType.ProviderFee)), (800L, 400L))
    assertEquals(Ngr.of(s, Window.Period, "2026-10"), 6_800L)
    assertEquals(s.amount(FactType.RevShare), 1_700L)
  }

  test("the payment fee is the brand's percentage of a deposit, once") {
    val k = kit
    k.call(CommissionAccount.recordPaymentFee)(RecordPaymentFee("d1", 10_000, 150, oct, "UTC", now)): Unit
    assert(!k.call(CommissionAccount.recordPaymentFee)(RecordPaymentFee("d1", 10_000, 150, oct, "UTC", now)).persisted)
    assertEquals(facts(k).sums.amount(FactType.PaymentFee), 150L)
  }

  test("no payable fact while the membership is not active, and the catch-up on the next NGR fact once it is") {
    val k = kit
    ngr(k, "g1", "ggr", 10_000, oct, period.copy(payable = false)): Unit
    assertEquals(payable(k, FactType.RevShare), Vector.empty)
    ngr(k, "g2", "ggr", 2_000, oct): Unit
    assertEquals(payable(k, FactType.RevShare), Vector(3_000L))
  }

  test("a plan at no RevShare writes none, and claws back nothing already written") {
    val k = kit
    ngr(k, "g1", "ggr", 10_000, oct, lifetime): Unit
    ngr(k, "g2", "ggr", 4_000, nov, lifetime.copy(revShareBp = 0)): Unit
    assertEquals(payable(k, FactType.RevShare), Vector(2_500L))
  }

  test("CPA once per id; payable facts emit EarningsChanged for their period") {
    val k = kit
    val r = k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/goldvibe/ps-1", 5_000, oct, "UTC", now))
    assert(r.events.contains(CommissionAccount.EarningsChanged("2026-10", 5_000)))
    assert(!k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/goldvibe/ps-1", 5_000, oct, "UTC", now)).persisted)
  }

  test("an override is the delta from what was written for the source and period, dated in the period") {
    val k = kit
    k.call(CommissionAccount.recomputeOverride)(RecomputeOverride("aff-2", "2026-10", 10_000, 1_000, "UTC", now)): Unit
    k.call(CommissionAccount.recomputeOverride)(RecomputeOverride("aff-2", "2026-10", 14_000, 1_000, "UTC", now)): Unit
    assert(!k.call(CommissionAccount.recomputeOverride)(RecomputeOverride("aff-2", "2026-10", 14_000, 1_000, "UTC", now)).persisted)
    val o = facts(k).unsettled.filter(_.kind == FactType.Override)
    assertEquals(o.map(f => (f.id, f.amount, f.occurredAt)),
      Vector(("override/aff-2/2026-10/1", 1_000L, octEnd), ("override/aff-2/2026-10/2", 400L, octEnd)))
  }

  test("close claims payable facts up to the period's end, keeps its claims, and leaves later ones") {
    val k = kit
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/1", 5_000, oct, "UTC", now)): Unit
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/2", 5_000, nov, "UTC", now)): Unit
    val c = k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 1_000)).replyValue
    assertEquals((c.claimed, c.figures.total, c.facts), (true, 5_000L, 1))
    assertEquals(facts(k).claims("p-oct"), Set("cpa/1"))
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/late", 700, oct, "UTC", now)): Unit
    assertEquals(k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 1_000)).replyValue.figures.total, 5_700L)
    assert(!k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 1_000)).persisted)
  }

  test("below the threshold nothing more is claimed, earlier claims stand, and paying pays only them") {
    val k = kit
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/1", 5_000, oct, "UTC", now)): Unit
    k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 1_000)): Unit
    k.call(CommissionAccount.recordAdjustment)(RecordAdjustment("adjustment/a", -4_500, "clawback", "m-1", oct, None, "UTC", now)): Unit
    val c = k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 1_000)).replyValue
    assertEquals((c.claimed, c.figures.total), (false, 500L))
    assertEquals(facts(k).unsettled.filter(_.settledBy.contains("p-oct")).map(_.id), Vector("cpa/1"))
    k.call(CommissionAccount.markPaid)("p-oct"): Unit
    // The clawback was not in the paid payout: it is the next close's, and the CPA is not claimable again.
    assertEquals(facts(k).unsettled.map(_.id), Vector("adjustment/a"))
  }

  test("paid: the claimed facts leave unsettled, the payout cannot close again, a late fact goes to the next close") {
    val k = kit
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/1", 5_000, oct, "UTC", now)): Unit
    k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)): Unit
    k.call(CommissionAccount.markPaid)("p-oct"): Unit
    assert(!k.call(CommissionAccount.markPaid)("p-oct").persisted)
    assertEquals(k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)).errorMessage, "paid")
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/late", 700, oct, "UTC", now)): Unit
    assertEquals(k.call(CommissionAccount.close)(CloseAccount("p-nov", Instant.parse("2026-11-30T23:59:59.999Z"), 0)).replyValue.figures.total, 700L)
  }

  test("paying pays the claims only while they fold to the payout's figures; once paid, a retry and a close change nothing") {
    val k = kit
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/1", 5_000, oct, "UTC", now)): Unit
    val shown = k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)).replyValue.figures
    // A close claims a late fact before the payout shows it: paying what the manager saw is refused.
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/2", 1_000, oct, "UTC", now)): Unit
    val grown = k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)).replyValue.figures
    assert(k.call(CommissionAccount.payClaims)(PayClaims("p-oct", shown)).isError)
    assert(!facts(k).paid("p-oct"))
    k.call(CommissionAccount.payClaims)(PayClaims("p-oct", grown)): Unit
    assert(facts(k).paid("p-oct"))
    assert(!k.call(CommissionAccount.payClaims)(PayClaims("p-oct", shown)).persisted)
    assert(k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)).isError)
  }

  test("an adjustment claimed by a payout is stamped with it; one for a paid payout is refused") {
    val k = kit
    k.call(CommissionAccount.recordCpa)(RecordCpa("cpa/1", 5_000, oct, "UTC", now)): Unit
    k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)): Unit
    k.call(CommissionAccount.recordAdjustment)(RecordAdjustment("override-figure/p-oct", -2_000, "override figure", "m-1", oct, Some("p-oct"), "UTC", now)): Unit
    assertEquals(facts(k).claims("p-oct"), Set("cpa/1", "override-figure/p-oct"))
    assertEquals(k.call(CommissionAccount.close)(CloseAccount("p-oct", octEnd, 0)).replyValue.figures.total, 3_000L)
    k.call(CommissionAccount.markPaid)("p-oct"): Unit
    assertEquals(k.call(CommissionAccount.recordAdjustment)(RecordAdjustment("adjustment/x", 1, "r", "m", oct, Some("p-oct"), "UTC", now)).errorMessage, "paid")
  }

  test("snapshots every 500 events, and the state survives its codec") {
    assertEquals(CommissionAccount.snapshotEvery, Some(500))
    val k = kit
    ngr(k, "g1", "ggr", 10_000, oct, fees = FeeRates(admin = 1_000)): Unit
    val s     = facts(k)
    val codec = LedgerCodecs.state
    assertEquals(codec.fromBytes(codec.toBytes(s)), s)
  }
