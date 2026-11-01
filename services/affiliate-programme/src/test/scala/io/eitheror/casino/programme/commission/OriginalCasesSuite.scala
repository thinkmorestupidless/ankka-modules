package io.eitheror.casino.programme.commission

import io.eitheror.casino.programme.periods.Periods

import java.time.{Instant, ZoneOffset}

/**
 * The original's `CommissionEngineSpec` and `CommissionFormulasSpec` (`S/../test/scala/io/eitheror/affiliate/`),
 * case for case, amounts already in minor units there and here; a percent is basis points here (7% is
 * 700). Not carried: `mergeFacts`, `parseFact`, `toCents`, the revenue cache and `excludedGgr`, which
 * belong to the original's twins and their projection lag; the programme folds its entities' own
 * events. The first-deposit guard is not a formula and has no case in these two specs: its change
 * (change 2, lifetime) is `PlayerAttributionSuite`'s.
 */
class OriginalCasesSuite extends munit.FunSuite:

  private val P    = "2026-08"
  private val PREV = "2026-07"

  private def fact(
      tpe: FactType,
      amount: Long,
      period: String = P,
      occurredAt: String = "2026-08-15T00:00:00Z",
      source: Option[String] = None,
      settled: Option[String] = None,
      id: String = "t"
  ): LedgerFact =
    LedgerFact(id, tpe, amount, Instant.parse(occurredAt), Instant.parse(occurredAt), period, source, settled)

  private var seq = 0
  private def f(tpe: FactType, amount: Long, period: String = P, source: Option[String] = None, settled: Option[String] = None) =
    seq += 1
    fact(tpe, amount, period, s"$period-15T00:00:00Z", source, settled, s"${tpe.wire}-$seq")

  private def ggr(c: Long, period: String = P) = f(FactType.Ggr, c, period)
  private def bonusCost(c: Long)               = f(FactType.BonusCost, c)
  private def chargeback(c: Long)              = f(FactType.Chargeback, c)
  private def adminFee(c: Long)                = f(FactType.AdminFee, c)
  private def paymentFee(c: Long)              = f(FactType.PaymentFee, c)
  private def providerFee(c: Long)             = f(FactType.ProviderFee, c)
  private def cpa(c: Long, period: String = P) = f(FactType.Cpa, c, period)
  private def revShare(c: Long)                = f(FactType.RevShare, c)
  private def override_(c: Long, src: String)  = f(FactType.Override, c, source = Some(src))
  private def adjustment(c: Long)              = f(FactType.Adjustment, c)
  private def paidCpa(c: Long)                 = f(FactType.Cpa, c, settled = Some("payout-1"))

  private def sums(facts: LedgerFact*): Sums = Sums.of(facts)
  private def ngr(window: Window, facts: LedgerFact*): Long = Ngr.of(sums(facts*), window, P)
  private def earnings(facts: LedgerFact*): Long = Earnings.of(sums(facts*), P)
  private def comm(amount: Long, bp: Int): Long = Commission.onNgr(amount, bp)
  private def end(period: String): Instant = Periods.periodEnd(period, ZoneOffset.UTC)

  // ── CommissionEngineSpec ─────────────────────────────────────────────────────────────────────

  test("ngr: GGR minus bonus costs minus chargebacks") {
    assertEquals(ngr(Window.Lifetime, fact(FactType.Ggr, 10000), fact(FactType.BonusCost, 1000), fact(FactType.Chargeback, 500)), 8500L)
  }

  test("ngr: admin, payment and provider fees are subtracted") {
    assertEquals(ngr(Window.Lifetime, ggr(10000), adminFee(200), paymentFee(150), providerFee(100)), 9550L)
  }

  test("ngr: every deduction together") {
    assertEquals(ngr(Window.Lifetime, ggr(10000), bonusCost(500), chargeback(200), adminFee(100), paymentFee(75), providerFee(125)), 9000L)
  }

  test("ngr is stored negative; the commission on it floors at zero") {
    assertEquals(ngr(Window.Lifetime, ggr(100), bonusCost(500)), -400L)
    assertEquals(comm(-400L, 700), 0L)
  }

  test("the period window counts the matching period only") {
    val facts = Seq(ggr(10000, PREV), ggr(5000))
    assertEquals(Ngr.of(Sums.of(facts), Window.Period, P), 5000L)
    assertEquals(Ngr.of(Sums.of(facts), Window.Lifetime, P), 15000L)
  }

  test("earnings: CPA, RevShare and adjustments of the period; override and NGR inputs excluded") {
    assertEquals(earnings(cpa(5000), revShare(700), adjustment(-100), override_(300, "x"), ggr(10000), adminFee(200)), 5600L)
  }

  test("earnings: strictly the requested period") {
    assertEquals(earnings(cpa(5000, PREV), cpa(3000), revShare(400)), 3400L)
  }

  test("earnings: zero with no payable facts") {
    assertEquals(earnings(ggr(10000)), 0L)
  }

  test("fees: no floor; a negative amount is a negative fee") {
    assertEquals(Fees.on(-500L, 1000), -50L)
  }

  test("fees: as the commission for a positive amount") {
    assertEquals(Fees.on(10000L, 2000), comm(10000L, 2000))
  }

  test("fees: zero for a zero amount, and for a zero rate") {
    assertEquals(Fees.on(0L, 1500), 0L)
    assertEquals(Fees.on(-500L, 0), 0L)
  }

  test("fees sum linearly across rounds; flooring each round would overstate them") {
    val events = Seq(1000L, -500L, 1000L)
    val feeSum = events.map(Fees.on(_, 1000)).sum
    assertEquals(feeSum, comm(events.sum, 1000))
    assert(events.map(comm(_, 1000)).sum > feeSum)
  }

  test("override: a percent of CPA, RevShare and adjustments") {
    val e = earnings(cpa(5000), revShare(700), adjustment(0))
    assertEquals(e, 5700L)
    assertEquals(comm(e, 1000), 570L)
  }

  test("override is additive: the sub keeps its commission") {
    val e = earnings(cpa(5000), revShare(700))
    assertEquals(e + comm(e, 1000), 6270L)
  }

  test("revshare: three GGR events each credit only what is new") {
    val d1 = Commission.onNgr(10000, 700) - 0
    val d2 = Commission.onNgr(20000, 700) - 700
    val d3 = Commission.onNgr(30000, 700) - 1400
    assertEquals((d1, d2, d3), (700L, 700L, 700L))
    assertEquals(d1 + d2 + d3, comm(30000, 700))
  }

  test("revshare: a late chargeback is a negative correction") {
    assertEquals(Commission.onNgr(0L, 700) - 700L, -700L)
  }

  test("already emitted: overrides are kept per source") {
    val s = sums(override_(500, "child-a"), override_(300, "child-b"))
    assertEquals(s.scoped(Window.Lifetime, P).amount(FactType.Override, Some("child-a")), 500L)
    assertEquals(s.scoped(Window.Lifetime, P).amount(FactType.Override, Some("child-b")), 300L)
  }

  test("already emitted: settled and unsettled both count") {
    assertEquals(sums(f(FactType.RevShare, 700, settled = Some("payout-1")), revShare(200)).amount(FactType.RevShare), 900L)
  }

  test("candidates: an earlier period's straggler is included") {
    val stale = fact(FactType.Cpa, 5000, period = PREV, occurredAt = "2026-07-30T23:00:00Z")
    assert(Claim.candidates(Seq(stale), end(P)).contains(stale))
  }

  test("candidates: a fact after the period is excluded") {
    assertEquals(Claim.candidates(Seq(fact(FactType.Cpa, 5000, period = "2026-09", occurredAt = "2026-09-05T00:00:00Z")), end(P)), Vector.empty)
  }

  test("candidates: a settled fact is excluded") {
    assertEquals(Claim.candidates(Seq(fact(FactType.Cpa, 5000, settled = Some("payout-1"))), end(P)), Vector.empty)
  }

  test("candidates: NGR inputs are never payable") {
    assertEquals(Claim.candidates(Seq(ggr(5000), adminFee(200), paymentFee(100), providerFee(50)), end(P)), Vector.empty)
  }

  test("figures: each payable type summed") {
    val b = Claim.figures(Seq(cpa(1000), cpa(2000), revShare(500), override_(300, "s"), adjustment(-100)))
    assertEquals((b.cpa, b.revShare, b.overrideAmount, b.adjustments, b.total), (3000L, 500L, 300L, -100L, 3700L))
  }

  // ── CommissionFormulasSpec ───────────────────────────────────────────────────────────────────

  test("CPA: a fixed amount per FTD") { assertEquals(Claim.figures(Seq(cpa(5000))).cpa, 5000L) }

  test("CPA: three FTDs accumulate") { assertEquals(Claim.figures(Seq(cpa(5000), cpa(5000), cpa(5000))).cpa, 15000L) }

  test("CPA: unaffected by fees") {
    assertEquals(Claim.figures(Seq(ggr(1_000_000), adminFee(200_000), paymentFee(50_000), providerFee(30_000), cpa(5000))).cpa, 5000L)
  }

  test("CPA: no FTD, no commission") { assertEquals(Claim.figures(Seq(ggr(1_000_000))).total, 0L) }

  test("revshare on GGR alone") {
    val n = ngr(Window.Period, ggr(1_000_000))
    assertEquals(n, 1_000_000L)
    assertEquals(comm(n, 2000), 200_000L)
  }

  test("revshare: the admin fee is deducted first") {
    val n = ngr(Window.Period, ggr(1_000_000), adminFee(200_000))
    assertEquals((n, comm(n, 2000)), (800_000L, 160_000L))
  }

  test("revshare: the payment fee is deducted first") {
    val n = ngr(Window.Period, ggr(1_000_000), paymentFee(50_000))
    assertEquals((n, comm(n, 2000)), (950_000L, 190_000L))
  }

  test("revshare: the provider fee is deducted first") {
    val n = ngr(Window.Period, ggr(1_000_000), providerFee(30_000))
    assertEquals((n, comm(n, 2000)), (970_000L, 194_000L))
  }

  test("revshare: the bonus cost is deducted first") {
    val n = ngr(Window.Period, ggr(1_000_000), bonusCost(100_000))
    assertEquals((n, comm(n, 2000)), (900_000L, 180_000L))
  }

  test("revshare: the chargeback is deducted first") {
    val n = ngr(Window.Period, ggr(1_000_000), chargeback(50_000))
    assertEquals((n, comm(n, 2000)), (950_000L, 190_000L))
  }

  test("revshare: the operator's whole formula") {
    val n = ngr(Window.Period, ggr(1_000_000), bonusCost(150_000), paymentFee(75_000), providerFee(75_000), adminFee(200_000), chargeback(0))
    assertEquals((n, comm(n, 2000)), (500_000L, 100_000L))
  }

  test("revshare floors at zero when deductions exceed GGR") {
    val n = ngr(Window.Period, ggr(100_000), bonusCost(100_000), adminFee(50_000))
    assertEquals((n, comm(n, 2000)), (-50_000L, 0L))
  }

  test("revshare delta: each successive GGR event adds only the increment") {
    val d1 = comm(100_000, 2000)
    val d2 = comm(200_000, 2000) - d1
    val d3 = comm(300_000, 2000) - (d1 + d2)
    assertEquals((d1, d2, d3), (20_000L, 20_000L, 20_000L))
  }

  test("revshare delta: a late admin fee, and a late chargeback, are negative corrections") {
    assertEquals(comm(0L, 2000) - 200_000L, -200_000L)
  }

  test("revshare delta: out-of-order fees self-correct to the commission on the final NGR") {
    val d1 = comm(1_000_000, 2000)
    val d2 = comm(800_000, 2000) - d1
    assertEquals(d1 + d2, comm(800_000, 2000))
  }

  test("revshare delta through RevShare.delta: what is emitted is subtracted, in the plan's window") {
    val facts = Seq(ggr(100_000), revShare(10_000))
    assertEquals(RevShare.delta(Window.Period, Sums.of(facts), P, 2000), 10_000L)
  }

  test("carryover: a lifetime window carries a prior deficit") {
    val n = Ngr.of(Sums.of(Seq(ggr(100_000, PREV), bonusCost(200_000), ggr(500_000))), Window.Lifetime, P)
    assertEquals((n, comm(n, 2000)), (400_000L, 80_000L))
  }

  test("carryover: a period window ignores the prior period") {
    val n = Ngr.of(Sums.of(Seq(ggr(100_000, PREV), bonusCost(200_000), ggr(500_000))), Window.Period, P)
    assertEquals((n, comm(n, 2000)), (300_000L, 60_000L))
  }

  test("carryover: a lifetime window carries a prior positive balance") {
    val n = Ngr.of(Sums.of(Seq(ggr(500_000, PREV), ggr(500_000), adminFee(200_000))), Window.Lifetime, P)
    assertEquals((n, comm(n, 2000)), (800_000L, 160_000L))
  }

  test("hybrid: CPA unaffected by deductions") {
    assertEquals(Claim.figures(Seq(ggr(1_000_000), adminFee(200_000), cpa(5000))).cpa, 5000L)
  }

  test("hybrid: RevShare beside CPA") {
    val b = Claim.figures(Seq(ggr(1_000_000), adminFee(200_000), cpa(5000), revShare(160_000)))
    assertEquals((b.cpa, b.revShare, b.total), (5000L, 160_000L, 165_000L))
  }

  test("hybrid: CPA, RevShare and adjustments") {
    val b = Claim.figures(Seq(ggr(1_000_000), adminFee(200_000), providerFee(50_000), cpa(5000), cpa(5000), revShare(150_000), adjustment(-10_000)))
    assertEquals((b.cpa, b.revShare, b.adjustments, b.total), (10_000L, 150_000L, -10_000L, 150_000L))
  }

  test("override on CPA earnings") {
    val e = earnings(cpa(5000), cpa(5000), cpa(5000))
    assertEquals((e, comm(e, 500)), (15_000L, 750L))
  }

  test("override on fee-adjusted RevShare") {
    val e = earnings(ggr(1_000_000), adminFee(200_000), revShare(160_000))
    assertEquals((e, comm(e, 500)), (160_000L, 8_000L))
  }

  test("override on CPA and RevShare together") {
    val e = earnings(ggr(1_000_000), adminFee(200_000), cpa(5000), cpa(5000), revShare(160_000))
    assertEquals((e, comm(e, 500)), (170_000L, 8_500L))
  }

  test("override is additive to the operator's cost") {
    assertEquals(170_000L + comm(170_000L, 500), 178_500L)
  }

  test("override delta: a second CPA adds only the increment") {
    val d1 = Override.delta(5_000, 500, 0)
    val d2 = Override.delta(10_000, 500, d1)
    assertEquals((d1, d2), (250L, 250L))
    assertEquals(d1 + d2, comm(10_000, 500))
  }

  test("override is period-scoped") {
    val e = earnings(cpa(10_000, PREV), cpa(5_000))
    assertEquals((e, comm(e, 500)), (5_000L, 250L))
  }

  test("override: each source independently") {
    val master = Sums.of(Seq(override_(500, "sub-a"), override_(0, "sub-b"))).forPeriod(P)
    assertEquals(master.amount(FactType.Override, Some("sub-a")), 500L)
    assertEquals(master.amount(FactType.Override, Some("sub-b")), 0L)
    assertEquals(Override.delta(20_000, 500, master.amount(FactType.Override, Some("sub-b"))), 1_000L)
  }

  test("override: a negative delta when the source's commission is corrected down") {
    val e = earnings(revShare(10_000), adjustment(-5_000))
    assertEquals(e, 5_000L)
    assertEquals(Override.delta(e, 500, 500L), -250L)
  }

  test("multi-tier: an override received is not in the parent's own earnings") {
    val parentOverride = comm(earnings(revShare(100_000)), 500)
    assertEquals(parentOverride, 5_000L)
    assertEquals(earnings(override_(parentOverride, "affiliate")), 0L)
  }

  test("multi-tier: a 0% tier does not block the grandparent") {
    assertEquals(comm(100_000L, 0), 0L)
    assertEquals(comm(100_000L, 500), 5_000L)
  }

  test("close: every unsettled payable fact up to the period's end") {
    val facts = Seq(cpa(5000), revShare(20_000), override_(1_000, "sub-a"), adjustment(-500))
    assertEquals(Claim.candidates(facts, end(P)).map(_.amount).sum, 25_500L)
    assertEquals(Claim.candidates(facts, end(P)).size, 4)
  }

  test("close: a fact settled before is excluded") {
    val swept = Claim.candidates(Seq(paidCpa(5000), cpa(5000)), end(P))
    assertEquals(swept.map(_.amount), Vector(5000L))
  }

  test("close: NGR inputs and fees are never payable") {
    assertEquals(Claim.candidates(Seq(ggr(1_000_000), bonusCost(100_000), chargeback(50_000), adminFee(200_000), paymentFee(50_000), providerFee(30_000)), end(P)), Vector.empty)
  }

  test("close: a prior period's straggler still within the cutoff") {
    val straggler = fact(FactType.Cpa, 5000, PREV, s"$PREV-28T23:59:00Z")
    assert(Claim.candidates(Seq(straggler), end(P)).contains(straggler))
  }

  test("close: a fact after the period is excluded") {
    assertEquals(Claim.candidates(Seq(fact(FactType.Cpa, 5000, "2026-09", "2026-09-05T00:00:00Z")), end(P)), Vector.empty)
  }

  test("close: the total is CPA, RevShare, override and adjustments, never fees") {
    val facts = Seq(ggr(1_000_000), adminFee(200_000), cpa(5000), revShare(160_000), override_(8_000, "sub-a"), adjustment(-10_000))
    assertEquals(Claim.candidates(facts, end(P)).map(_.amount).sum, 163_000L)
  }
