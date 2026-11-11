package io.eitheror.casino.reporting.figures

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

class FiguresSuite extends munit.FunSuite:
  private val eur = Currency.unsafe("EUR")
  private val at  = Instant.parse("2026-11-02T10:00:00Z")
  private def m(v: Long)  = MinorUnits.unsafe(v)
  private def s(v: Long)  = SignedMinorUnits(v)

  private def bet(amount: Long, part: Option[Long]) =
    BetPlaced("p1", eur, "t1", "r1", "g1", m(amount), "real", s(0), m(0), at, depositPartCents = part.map(m))
  private def win(amount: Long, part: Option[Long]) =
    WinCredited("p1", eur, "t2", "r1", "g1", m(amount), "real", s(0), m(0), at, depositPartCents = part.map(m))
  private def rollback(amount: Long, part: Option[Long]) =
    BetRolledBack("p1", eur, "t3", "r1", m(amount), s(0), m(0), at, depositPartCents = part.map(m))
  private val loss     = RoundSettledAsLoss("p1", eur, "t4", "r1", s(0), m(0), at)
  private def credit(amount: Long) =
    ProviderCredited("p1", eur, "t5", "ref", m(amount), "real", None, s(0), m(0), at, depositPartCents = Some(m(amount)))

  test("GGR's legs: a stake, a win, a provider credit as a win, a rollback; a loss moves nothing") {
    assertEquals(Figures.ggrLeg(bet(1000, None)), Some(Figures.GgrLeg(1000, 0, 0)))
    assertEquals(Figures.ggrLeg(win(400, None)), Some(Figures.GgrLeg(0, 400, 0)))
    assertEquals(Figures.ggrLeg(credit(250)), Some(Figures.GgrLeg(0, 250, 0)))
    assertEquals(Figures.ggrLeg(rollback(100, None)), Some(Figures.GgrLeg(0, 0, 100)))
    assertEquals(Figures.ggrLeg(loss), Some(Figures.GgrLeg(0, 0, 0)))
    assertEquals(Figures.ggr(stakes = 1000, wins = 650, rollbacks = 100), 250L)
  }

  test("a family outside GGR counts nothing") {
    val deposit = DepositConfirmed("p1", eur, "t6", m(5000), "pay-1", 1, s(5000), m(0), at)
    assertEquals(Figures.ggrLeg(deposit), None)
    assertEquals(Figures.poolLeg(deposit), None)
  }

  test("the deposit pool counts deposit parts, as attribution's RealMoney.counted; a provider credit counts nothing") {
    assertEquals(Figures.poolLeg(bet(1000, Some(600))), Some((Figures.PoolLeg.Stake, "r1", 600L)))
    assertEquals(Figures.poolLeg(win(400, Some(300))), Some((Figures.PoolLeg.Win, "r1", 300L)))
    assertEquals(Figures.poolLeg(rollback(100, None)), Some((Figures.PoolLeg.Rollback, "r1", 0L)))
    assertEquals(Figures.poolLeg(loss), Some((Figures.PoolLeg.Loss, "r1", 0L)))
    assertEquals(Figures.poolLeg(credit(250)), None)
    assertEquals(Figures.poolGgr(Figures.PoolLeg.Stake, 600) + Figures.poolGgr(Figures.PoolLeg.Win, 300), 300L)
  }

  test("NGR is GGR less bonus cost less chargebacks, before the programme's fees") {
    assertEquals(Figures.ngr(ggr = 10000, bonusCost = 1500, chargebacks = 2500), 6000L)
    assertEquals(Figures.NgrBasis, "before programme fees")
  }

  test("the trend arrow is the original's") {
    assertEquals(Figures.trend(0, 0), "flat")
    assertEquals(Figures.trend(1, 0), "up")
    assertEquals(Figures.trend(-5, 0), "up")
    assertEquals(Figures.trend(103, 100), "up")
    assertEquals(Figures.trend(102, 100), "flat")
    assertEquals(Figures.trend(98, 100), "flat")
    assertEquals(Figures.trend(97, 100), "down")
  }

  test("a rate is to one place and absent with no attempts") {
    assertEquals(Figures.ratePct(2, 3), Some(BigDecimal("66.7")))
    assertEquals(Figures.ratePct(0, 0), None)
    assertEquals(Figures.averageHours(5400, 1), Some(BigDecimal("1.5")))
    assertEquals(Figures.averageHours(0, 0), None)
  }

  test("the conversion rate is the truncated whole percent") {
    assertEquals(Figures.conversionRatePct(2, 3), 66)
    assertEquals(Figures.conversionRatePct(0, 0), 0)
  }

  test("wagering progress is the integer mean of each record's rounded percent, over records with a requirement") {
    assertEquals(Figures.averageProgressPct(Seq(1L -> 3L, 2L -> 3L, 5L -> 0L)), 50) // 33 and 67
    assertEquals(Figures.averageProgressPct(Nil), 0)
  }
