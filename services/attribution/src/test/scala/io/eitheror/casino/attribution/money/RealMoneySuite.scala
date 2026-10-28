package io.eitheror.casino.attribution.money

import io.eitheror.casino.attribution.money.RealMoney.Leg
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/** Every family of `money.transactions` against contracts/facts.md's table. */
class RealMoneySuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-25T10:00:00Z")
  private val zar = Currency.unsafe("ZAR")
  private def m(v: Long) = MinorUnits.unsafe(v)
  private def s(v: Long) = SignedMinorUnits(v)

  private val all: Vector[MoneyTransactionsFact] = Vector(
    DepositConfirmed("p", zar, "t1", m(5000), "pay-1", 1, s(5000), m(0), at),
    BetPlaced("p", zar, "t2", "r1", "g1", m(1000), "real", s(4000), m(0), at),
    WinCredited("p", zar, "t3", "r1", "g1", m(300), "real", s(4300), m(0), at),
    BetRolledBack("p", zar, "t4", "r1", m(1000), s(5300), m(0), at),
    RoundSettledAsLoss("p", zar, "t5", "r2", s(5300), m(0), at),
    RestrictedFundsAwarded("p", zar, "t6", "aw-1", m(2000), m(40000), s(5300), m(2000), at),
    RestrictedFundsConverted("p", zar, "t7", "aw-1", m(500), s(5800), m(0), at),
    RestrictedFundsForfeited("p", zar, "t8", "aw-2", m(100), "expired", m(0), s(5800), m(0), at),
    AdminAdjusted("p", zar, "t9", s(-100), "real", "goodwill", "staff-1", s(5700), m(0), at),
    HoldPlaced("p", zar, "t10", "w-1", m(1000), m(0), s(4700), m(0), at),
    HoldReleased("p", zar, "t11", "w-1", m(1000), s(5700), m(0), at),
    WalletWithdrawalReversed("p", zar, "t12", "w-1", m(1000), s(6700), m(0), at),
    ProviderCredited("p", zar, "t13", "prov-1", m(700), "real", None, s(7400), m(0), at),
    ProviderCredited("p", zar, "t14", "prov-2", m(900), "restricted", Some("aw-3"), s(7400), m(900), at)
  )

  test("the counted families: a stake, a win, a credit landing on real, a rollback, a loss") {
    val counted = all.flatMap(f => RealMoney.counted(f, delta = 100).map(RealMoney.family(f) -> _))
    assertEquals(counted, Vector(
      "bet_placed" -> Leg(Leg.Stake, "r1", -100),
      "win_credited" -> Leg(Leg.Win, "r1", 100),
      "bet_rolled_back" -> Leg(Leg.Rollback, "r1", 100),
      "round_settled_as_loss" -> Leg(Leg.Loss, "r2", 0),
      "provider_credited" -> Leg(Leg.Win, "prov-1", 100)))
  }

  test("a split bet counts only its real part: the balance difference") {
    val split = BetPlaced("p", zar, "t", "r", "g", m(1000), "mixed", s(4400), m(400), at)
    assertEquals(RealMoney.counted(split, delta = 4400 - 5000), Some(Leg(Leg.Stake, "r", 600)))
    val bonusOnly = BetPlaced("p", zar, "t", "r", "g", m(1000), "restricted", s(5000), m(0), at)
    assertEquals(RealMoney.counted(bonusOnly, delta = 0), Some(Leg(Leg.Stake, "r", 0)))
  }

  test("a restricted award's cost and a credit landing on restricted; nothing else costs") {
    assertEquals(all.flatMap(RealMoney.bonusCost), Vector(2000L -> "aw-1", 900L -> "prov-2"))
  }

  test("every family is named as the topic names it") {
    assertEquals(all.map(RealMoney.family).distinct.toSet, MoneyTransactions.families.map(_.name).toSet)
  }

  test("the dedupe id is the ce-id when present, else family, transaction and award") {
    assertEquals(RealMoney.dedupeId(all(1), Some("ce-9")), "ce-9")
    assertEquals(RealMoney.dedupeId(all(1), None), "bet_placed/t2/")
    assertEquals(RealMoney.dedupeId(all(5), None), "restricted_funds_awarded/t6/aw-1")
    assertEquals(RealMoney.dedupeId(all(13), Some("")), "provider_credited/t14/aw-3")
  }
