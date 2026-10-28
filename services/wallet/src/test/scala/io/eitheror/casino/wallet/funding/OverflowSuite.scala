package io.eitheror.casino.wallet.funding

import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * SC-004: the original's #662. A win's allocation multiplies minor units by minor units
 * (win × stake leg), which overflowed 32 bits in production and credited a negative amount. Every
 * product here is beyond `Int.MaxValue` and must be carried exactly in `Long`.
 */
class OverflowSuite extends munit.FunSuite with LogCapturing:

  private def win(w: Wallet, recs: Seq[Record], legs: Legs, amount: Long): WinPlan =
    Resolver.creditWin(w, recs.toVector, legs, amount).fold(d => fail(s"unexpected deny: $d"), identity)

  private val record = Record(id = "restricted-funds/1", status = Status.Active, openRoundCount = 1)

  test("a 500,000 win on a 50,000 real-funded stake credits exactly 500,000") {
    assert(500000L * 50000L > Int.MaxValue)
    val plan = win(Wallet(realBalance = 0), Nil, Legs(real = 50000), 500000)
    assertEquals((plan.realShare, plan.newRealBalance), (500000L, 500000L))
  }

  test("a 5,000,000 win on a 500,000 real-funded stake credits exactly 5,000,000") {
    assert(5000000L * 500000L > Int.MaxValue)
    val plan = win(Wallet(realBalance = 2397), Nil, Legs(real = 500000), 5000000)
    assertEquals((plan.realShare, plan.newRealBalance), (5000000L, 5002397L))
  }

  test("a 500,000 win on a 50,000 split stake splits exactly in the stake's proportion") {
    val plan = win(Wallet(realBalance = 0), Seq(record), Legs(real = 30000, records = Vector(Leg(record.id, 20000))), 500000)
    assertEquals((plan.realShare, plan.recordShares.map(_.amount)), (300000L, Vector(200000L)))
    assertEquals(plan.realShare + plan.recordShares.map(_.amount).sum, 500000L)
  }

  test("a 5,000,000 win on a 500,000 split stake splits exactly in the stake's proportion") {
    val plan = win(Wallet(realBalance = 0), Seq(record), Legs(real = 300000, records = Vector(Leg(record.id, 200000))), 5000000)
    assertEquals((plan.realShare, plan.recordShares.map(_.amount)), (3000000L, Vector(2000000L)))
    assertEquals(findBalance(plan), 2000000L)
  }

  test("a product far beyond 32 bits is carried in Long without loss") {
    val amount = 3000000000L // itself above Int.MaxValue
    val plan   = win(Wallet(realBalance = 0), Seq(record), Legs(real = 1000000000L, records = Vector(Leg(record.id, 2000000000L))), amount)
    assertEquals((plan.realShare, plan.recordShares.map(_.amount)), (1000000000L, Vector(2000000000L)))
  }

  test("a product beyond 64 bits is refused, not wrapped") {
    intercept[ArithmeticException](win(Wallet(realBalance = 0), Nil, Legs(real = Long.MaxValue / 2), Long.MaxValue / 2))
  }

  private def findBalance(plan: WinPlan): Long =
    plan.recordUpdates.find(_.id == record.id).fold(fail("no update"))(_.balance)
