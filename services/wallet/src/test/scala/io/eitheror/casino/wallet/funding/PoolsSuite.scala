package io.eitheror.casino.wallet.funding

/**
 * The original's `internal/pools/pools_test.go` (spec 025 T004): `TestApplyTable` row for row, each
 * row driven through the `Pools` operation its movement type uses, and `TestMulDivNoOverflow`.
 * Each test is named `pools_test.go/<row>`; expected values are the Go table's.
 *
 * Not ported, each for a reason:
 *  - `win settlement-ratio fallback` and `win fallback both empty`: a win on a bet with no recorded
 *    parts. Every bet here records its parts, because there is no unstamped wallet (spec 025
 *    clarification 2026-10-28); a win with no bet at all is converted (`win bet leg zero`).
 *  - `unknown type`: movement types are a closed set of operations here, not strings.
 *  - `TestReplay*` and `TestNormalisation`: the original's `pools-backfill` ledger replay, which runs
 *    in the original before cutover (research R8), never in this wallet.
 *  - `TestBonusCreditIsExplicitNoOp`: a credit to a restricted funds record has no pool step; it is
 *    `PoolsGuestsSuite`'s `TestCreditPrimaryPools` restricted case.
 */
class PoolsSuite extends munit.FunSuite:

  private def right[A](e: Either[Deny, A]): A = e.fold(d => fail(s"unexpected deny: $d"), identity)

  /** checkInvariant: no negative deposit pool, and the pools moved by exactly the real balance's change. */
  private def step(name: String, start: Pools, delta: Long, end: Pools, split: Parts)(run: => (Pools, Parts))(using munit.Location): Unit =
    test(s"pools_test.go/$name") {
      val (got, parts) = run
      assertEquals((got, parts), (end, split))
      assert(got.deposit >= 0, s"negative deposit pool $got")
      assertEquals(got.total - start.total, delta, "total moved")
    }

  private def credit(p: Pools, parts: Parts): (Pools, Parts) = right(Pools.credit(p, parts))

  step("stake converted-first", Pools(5000, 5000), -1000, Pools(5000, 4000), Parts(0, 1000))(
    right(Pools.bet(Pools(5000, 5000), 1000, 0, allowNegative = false)))

  step("stake spills to deposit", Pools(5000, 5000), -6000, Pools(4000, 0), Parts(1000, 5000))(
    right(Pools.bet(Pools(5000, 5000), 6000, 0, allowNegative = false)))

  step("win in stake ratio", Pools(4000, 0), 3000, Pools(4500, 2500), Parts(500, 2500))(
    credit(Pools(4000, 0), Pools.winSplit(3000, 0, Parts(1000, 5000))))

  step("win rounding", Pools(4000, 0), 1000, Pools(4333, 667), Parts(333, 667))(
    credit(Pools(4000, 0), Pools.winSplit(1000, 0, Parts(1, 2))))

  step("win restricted-only bet", Pools(4000, 0), 700, Pools(4000, 700), Parts(0, 700))(
    credit(Pools(4000, 0), Pools.winSplit(700, 700, Parts.zero)))

  step("win bet leg zero", Pools(10, 10), 50, Pools(10, 60), Parts(0, 50))(
    credit(Pools(10, 10), Pools.winSplit(50, 0, Parts.zero)))

  step("win partly orphaned", Pools(0, 0), 1000, Pools(300, 700), Parts(300, 700))(
    credit(Pools(0, 0), Pools.winSplit(1000, 400, Parts(1, 1))))

  step("rollback", Pools(4000, 0), 6000, Pools(5000, 5000), Parts(1000, 5000))(
    credit(Pools(4000, 0), Parts(1000, 5000)))

  // The resolver restores a bet with no recorded parts to converted (rollback.go :81).
  step("rollback unknown split", Pools(4000, 0), 600, Pools(4000, 600), Parts(0, 600))(
    credit(Pools(4000, 0), Parts.ofConverted(600)))

  test("pools_test.go/loss moves nothing") {
    val plan = right(Resolver.settleLoss(Wallet(4007, pools = Some(Pools(4000, 7))), Vector.empty, Legs(real = 100)))
    assertEquals(plan.forfeited, Vector.empty[String]) // LossPlan carries no pools: nothing moves
  }

  step("withdrawal deposit-first", Pools(5000, 5000), -6000, Pools(0, 4000), Parts(5000, 1000))(
    right(Pools.debit(Pools(5000, 5000), 6000, convertedFirst = false)))

  step("cap void converted-first", Pools(5000, 5000), -2000, Pools(5000, 3000), Parts(0, 2000))(
    right(Pools.debit(Pools(5000, 5000), 2000, convertedFirst = true)))

  step("clawback converted-first", Pools(5000, 500), -800, Pools(4700, 0), Parts(300, 500))(
    right(Pools.debit(Pools(5000, 500), 800, convertedFirst = true)))

  step("negative adjustment", Pools(500, 5000), -800, Pools(0, 4700), Parts(500, 300))(
    right(Pools.debit(Pools(500, 5000), 800, convertedFirst = false)))

  step("positive adjustment", Pools(0, 0), 250, Pools(250, 0), Parts(250, 0))(credit(Pools(0, 0), Parts.ofDeposit(250)))

  step("provider credit", Pools(0, 0), 400, Pools(0, 400), Parts(0, 400))(credit(Pools(0, 0), Parts.ofConverted(400)))

  step("conversion then stake", Pools(1000, 0), 2500, Pools(1000, 2500), Parts(0, 500))(
    right(Pools.bet(Pools(1000, 0), 500, 3000, allowNegative = false)))

  step("deposit", Pools(1, 2), 10, Pools(11, 2), Parts(10, 0))(credit(Pools(1, 2), Parts.ofDeposit(10)))

  step("bonus_conversion", Pools(1, 2), 10, Pools(1, 12), Parts(0, 10))(credit(Pools(1, 2), Parts.ofConverted(10)))

  step("withdrawal return restores split", Pools(0, 0), 6000, Pools(5000, 1000), Parts(5000, 1000))(
    credit(Pools(0, 0), Pools.restored(Parts(5000, 1000), 6000)))

  step("withdrawal reversal legacy => deposit", Pools(0, 0), 600, Pools(600, 0), Parts(600, 0))(
    credit(Pools(0, 0), Pools.restored(Parts.zero, 600)))

  test("pools_test.go/overdraft") {
    assertEquals(Pools.debit(Pools(100, 100), 300, convertedFirst = false).left.map(_.code), Left(Codes.PoolsInvariant))
  }

  test("pools_test.go/TestMulDivNoOverflow") {
    val big = 1L << 50
    assertEquals(Pools.mulDiv(big, big, big), big)
  }

  test("mulDiv truncates, and is exact where the product overflows 64 bits") {
    assertEquals(Pools.mulDiv(1000, 1, 3), 333L)
    assertEquals(Pools.mulDiv(Long.MaxValue, Long.MaxValue - 1, Long.MaxValue), Long.MaxValue - 1)
    assertEquals(Pools.mulDiv(0, 5, 7), 0L)
  }
