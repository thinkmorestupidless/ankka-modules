package io.eitheror.casino.programme.commission

import java.time.Instant

/** What the original's cases do not cover. */
class CommissionSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-08-15T00:00:00Z")
  private def f(id: String, t: FactType, amount: Long, period: String = "2026-08", settled: Option[String] = None, when: Instant = at) =
    LedgerFact(id, t, amount, when, when, period, None, settled)

  test("a rate is basis points, HALF_UP: 7.5% of 333 is 24.975, so 25") {
    assertEquals(Commission.onNgr(333, 750), 25L)
    assertEquals(Fees.on(-333, 750), -25L)
  }

  test("a zero delta is nothing to write") {
    val sums = Sums.of(Seq(f("g", FactType.Ggr, 10_000), f("r", FactType.RevShare, 2_000)))
    assertEquals(RevShare.delta(Window.Period, sums, "2026-08", 2000), 0L)
  }

  test("a negative delta claws back what NGR no longer supports, in the window only") {
    val sums = Sums.of(Seq(f("g", FactType.Ggr, 10_000), f("r", FactType.RevShare, 2_000), f("c", FactType.Chargeback, 5_000)))
    assertEquals(RevShare.delta(Window.Period, sums, "2026-08", 2000), -1_000L)
    val lastMonth = sums.add(f("old", FactType.Ggr, 50_000, "2026-07"))
    assertEquals(RevShare.delta(Window.Period, lastMonth, "2026-08", 2000), -1_000L)
    assertEquals(RevShare.delta(Window.Lifetime, lastMonth, "2026-08", 2000), 9_000L)
  }

  test("sums count each type for an FTD count") {
    assertEquals(Sums.of(Seq(f("a", FactType.Cpa, 5000), f("b", FactType.Cpa, 5000))).count(FactType.Cpa), 2L)
  }

  test("select: below the threshold claims nothing; at it claims the candidates") {
    val unsettled = Vector(f("a", FactType.Cpa, 3000), f("b", FactType.RevShare, 1000))
    val end       = Instant.parse("2026-09-01T00:00:00Z")
    assertEquals(Claim.select(unsettled, end, Set.empty, 5000), Claim.Outcome.BelowThreshold(Claim.Figures(cpa = 3000, revShare = 1000)))
    Claim.select(unsettled, end, Set.empty, 4000) match
      case Claim.Outcome.Claimed(facts, figures) => assertEquals((facts.map(_.id), figures.total), (Vector("a", "b"), 4000L))
      case other => fail(other.toString)
  }

  test("select: the payout's earlier claims stay in scope; another payout's claims and later facts do not") {
    val end = Instant.parse("2026-09-01T00:00:00Z")
    val unsettled = Vector(
      f("mine", FactType.Cpa, 5000, settled = Some("p-1")),
      f("theirs", FactType.Cpa, 7000, settled = Some("p-0")),
      f("late", FactType.Adjustment, 100, when = Instant.parse("2026-09-02T00:00:00Z")),
      f("new", FactType.RevShare, 200)
    )
    Claim.select(unsettled, end, Set("mine"), 0) match
      case Claim.Outcome.Claimed(facts, figures) => assertEquals((facts.map(_.id).toSet, figures.total), (Set("mine", "new"), 5200L))
      case other => fail(other.toString)
  }

