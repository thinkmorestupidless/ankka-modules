package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.MoneyEvent
import io.eitheror.casino.attribution.money.RealMoney.Leg
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/** Every family of `money.transactions` against contracts/facts.md's table. */
class RealMoneySuite extends munit.FunSuite with LogCapturing:
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
    ProviderCredited("p", zar, "t14", "prov-2", m(900), "restricted", Some("aw-3"), s(7400), m(900), at),
    RestrictedFundsClosed("p", zar, "t22", "aw-1", "cap_expired", s(7400), m(900), at)
  )

  /** Spec 025: the parts a fact carries, deposit and converted. */
  private def parts(d: Long, c: Long) = (Some(m(d)), Some(m(c)))

  test("the counted families: a stake, a win, a rollback, a loss; a fact without parts counts 0") {
    val counted = all.flatMap(f => RealMoney.counted(f).map(RealMoney.family(f) -> _))
    assertEquals(counted, Vector(
      "bet_placed" -> Leg(Leg.Stake, "r1", 0),
      "win_credited" -> Leg(Leg.Win, "r1", 0),
      "bet_rolled_back" -> Leg(Leg.Rollback, "r1", 0),
      "round_settled_as_loss" -> Leg(Leg.Loss, "r2", 0)))
  }

  test("spec 025: a bet counts its deposit part, not its real movement") {
    val (d, c) = parts(400, 600)
    val bet = BetPlaced("p", zar, "t", "r", "g", m(1000), "real", s(4000), m(0), at, depositPartCents = d, convertedPartCents = c)
    assertEquals(RealMoney.counted(bet), Some(Leg(Leg.Stake, "r", 400)))
    val split = BetPlaced("p", zar, "t", "r", "g", m(1000), "mixed", s(4400), m(400), at, depositPartCents = Some(m(600)), convertedPartCents = Some(m(0)))
    assertEquals(RealMoney.counted(split), Some(Leg(Leg.Stake, "r", 600)))
  }

  test("spec 025: a stake of converted money counts nothing") {
    val (d, c) = parts(0, 1000)
    assertEquals(RealMoney.counted(BetPlaced("p", zar, "t", "r", "g", m(1000), "real", s(0), m(0), at, depositPartCents = d, convertedPartCents = c)),
      Some(Leg(Leg.Stake, "r", 0)))
  }

  test("spec 025: a win counts its deposit part") {
    val (d, c) = parts(166, 834)
    assertEquals(RealMoney.counted(WinCredited("p", zar, "t", "r", "g", m(1000), "real", s(5000), m(0), at, depositPartCents = d, convertedPartCents = c)),
      Some(Leg(Leg.Win, "r", 166)))
  }

  test("spec 025: a rollback counts the deposit part it returns") {
    val (d, c) = parts(1000, 5000)
    assertEquals(RealMoney.counted(BetRolledBack("p", zar, "t", "r", m(6000), s(6000), m(0), at, depositPartCents = d, convertedPartCents = c)),
      Some(Leg(Leg.Rollback, "r", 1000)))
  }

  test("spec 025: a loss settles its round with nothing, and a provider credit to real counts nothing") {
    assertEquals(RealMoney.counted(RoundSettledAsLoss("p", zar, "t", "r", s(0), m(0), at)), Some(Leg(Leg.Loss, "r", 0)))
    assertEquals(RealMoney.counted(ProviderCredited("p", zar, "t", "prov", m(700), "real", None, s(700), m(0), at,
      depositPartCents = Some(m(0)), convertedPartCents = Some(m(700)))), None)
  }

  test("spec 025: a fact without parts counts 0, not its real movement") {
    assertEquals(RealMoney.counted(BetPlaced("p", zar, "t", "r", "g", m(1000), "real", s(4000), m(0), at)), Some(Leg(Leg.Stake, "r", 0)))
  }

  test("a restricted award's cost and a credit landing on restricted; nothing else costs") {
    assertEquals(all.flatMap(RealMoney.bonusCost), Vector(2000L -> "aw-1", 900L -> "prov-2"))
  }

  /** Spec 022's movements: counted nowhere in GGR, no bonus cost (spec 025's Assumptions). */
  private val ledger: Vector[MoneyTransactionsFact] = Vector(
    ReservationPlaced("p", zar, "t15", "o1", m(600), s(7400), m(900), m(600), at),
    ReservationReleased("p", zar, "t16", "o1", m(100), s(7400), m(900), m(500), at),
    SettlementDebit("p", zar, "t17", "tr-1", "o1", m(300), m(200), "q", s(7100), m(900), m(0), at),
    SettlementCredit("p", zar, "t18", "tr-2", m(300), "q", s(7400), m(900), m(0), at),
    ResolutionPayment("p", zar, "t19", "mk-1", "pos-1", m(100), s(7500), m(900), m(0), at),
    AccountDebit("p", zar, "t20", "pool", "mk-1", "split", "sp-1", m(100), s(7400), m(900), m(0), at),
    AccountCredit("p", zar, "t21", "pool", "mk-1", "merge", "mg-1", m(100), s(7500), m(900), m(0), at)
  )

  test("every family is named as the topic names it; account_adjusted is no wallet's and never reaches a tally") {
    assertEquals((all ++ ledger).map(RealMoney.family).distinct.toSet,
      MoneyTransactions.families.map(_.name).toSet - "account_adjusted")
  }

  test("spec 022's movements count nothing and cost nothing") {
    assertEquals(ledger.flatMap(RealMoney.counted), Vector.empty)
    assertEquals(ledger.flatMap(RealMoney.bonusCost), Vector.empty)
  }

  test("the dedupe id is the ce-id when present, else family, transaction and award") {
    assertEquals(RealMoney.dedupeId(all(1), Some("ce-9")), "ce-9")
    assertEquals(RealMoney.dedupeId(all(1), None), "bet_placed/t2/")
    assertEquals(RealMoney.dedupeId(all(5), None), "restricted_funds_awarded/t6/aw-1")
    assertEquals(RealMoney.dedupeId(all(13), Some("")), "provider_credited/t14/aw-3")
  }

  /**
   * Spec 011 SC-004: what the wallet publishes over one reward's life, keyed as `rewards` keys its calls (a funds
   * reward's award key is the reward id; a free-rounds reward's winnings award is `<reward>/winnings`, a late round's
   * `<reward>/late/<round>`, and its provider credit's reference the reward id). The bonus cost is a funds reward's
   * award, once, under the reward; a free-rounds reward's winnings awards cost nothing here, since its cost is
   * `free_spins_settled`'s; a provider credit on restricted money is cost here, and the record drops it when the
   * reference is a free-rounds reward's. The conversion, the clawback, the close and a redelivered award add nothing.
   */
  test("spec 011: a reward's life costs its award once, under the reward id; free-rounds winnings awards cost nothing") {
    val award = RestrictedFundsAwarded("p", zar, "t30", "rw-1", m(3000), m(90000), s(5000), m(3000), at)
    val life: Vector[MoneyTransactionsFact] = Vector(
      award,
      RestrictedFundsConverted("p", zar, "t31", "rw-1", m(900), s(5900), m(0), at),
      RestrictedFundsClosed("p", zar, "t32", "rw-1", "cap_expired", s(5900), m(0), at),
      RestrictedFundsAwarded("p", zar, "t33", "rw-2", m(1000), m(10000), s(5900), m(1000), at),
      RestrictedFundsForfeited("p", zar, "t34", "rw-2", m(1000), "adjustment", m(1200), s(4700), m(0), at),
      ProviderCredited("p", zar, "t35", "rw-3", m(250), "restricted", Some("rw-1"), s(4700), m(250), at),
      RestrictedFundsAwarded("p", zar, "t36", "rw-4/winnings", m(600), m(6000), s(4700), m(850), at),
      RestrictedFundsAwarded("p", zar, "t37", "rw-4/late/rd-9", m(50), m(500), s(4700), m(900), at)
    )
    assertEquals(life.flatMap(RealMoney.bonusCost), Vector(3000L -> "rw-1", 1000L -> "rw-2", 250L -> "rw-3"))
    // a redelivered award is the same message, which the attribution record keeps once by its dedupe id
    assertEquals(RealMoney.dedupeId(award, None), RealMoney.dedupeId(award.copy(), None))
    assertEquals(RealMoney.dedupeId(life(2), None), "restricted_funds_closed/t32/rw-1")
  }

  test("spec 011: a free-rounds grant's winnings award is told by its key; a funds reward's is not") {
    assert(RealMoney.freeRoundsWinnings("rw-4/winnings"))
    assert(RealMoney.freeRoundsWinnings("rw-4/late/rd-9"))
    assert(!RealMoney.freeRoundsWinnings("rw-1"))
    assert(!RealMoney.freeRoundsWinnings("winnings"))
  }

  private def settled(reward: String, win: Long, when: Instant) =
    FreeSpinsSettled("p", reward, "Spins", "o-1", "free_spins", m(win), zar, when)

  test("spec 011: free_spins_settled is a free-rounds grant's bonus cost, each settlement under its own reference") {
    val first = RealMoney.freeRoundsCost(settled("rw-4", 600, at), None).get
    assertEquals((first.kind, first.currency, first.amount, first.at), (MoneyEvent.BonusCost, "ZAR", 600L, at))
    assertEquals(first.reference, s"rw-4/settled/${at.toEpochMilli}")
    assertEquals(first.dedupeId, s"free_spins_settled/rw-4/${at.toEpochMilli}/600")
    // a late round's settlement is a second cost, not the first again; a redelivery is the same
    val late = RealMoney.freeRoundsCost(settled("rw-4", 50, at.plusSeconds(60)), None).get
    assertNotEquals(late.reference, first.reference)
    assertNotEquals(late.dedupeId, first.dedupeId)
    assertEquals(RealMoney.freeRoundsCost(settled("rw-4", 600, at), None), Some(first))
    // the ce-id, when the runtime hands it over, is the dedupe id
    assertEquals(RealMoney.freeRoundsCost(settled("rw-4", 600, at), Some("rw-4/7")).map(_.dedupeId), Some("rw-4/7"))
    // rounds that won nothing cost nothing
    assertEquals(RealMoney.freeRoundsCost(settled("rw-5", 0, at), None), None)
  }
