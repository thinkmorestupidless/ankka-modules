package io.eitheror.casino.wallet.funding

/**
 * Spec 022 FR-003: a bet, a hold and a cancel's clawback draw on real balance less what is reserved,
 * with the refusals and their order unchanged (research R1, R14). The ported tables hold the rest
 * with `reserved = 0`.
 */
class ReservedSuite extends munit.FunSuite:

  private val wallet = Wallet(realBalance = 1000, reserved = 600)

  private def active(id: String, balance: Long): Record =
    Record(id = id, status = Status.Active, balance = balance, releaseRequirement = 1_000_000L)

  test("available is real balance less reserved, never below zero") {
    assertEquals(wallet.available, 400L)
    assertEquals(Wallet(realBalance = 100, reserved = 600).available, 0L)
    assertEquals(Wallet(realBalance = 100).available, 100L)
  }

  test("a bet beyond available is refused as insufficient funds; within it, real falls in real terms") {
    assertEquals(Resolver.placeBet(wallet, Vector.empty, Stake(500), Flags()).left.map(_.code), Left(Codes.InsufficientFunds))
    val plan = Resolver.placeBet(wallet, Vector.empty, Stake(400), Flags()).fold(d => fail(d.toString), identity)
    assertEquals((plan.realLeg, plan.newRealBalance, plan.balanceBefore, plan.balanceAfter), (400L, 600L, 1000L, 600L))
  }

  test("a bet funded from real and restricted takes the real leg from available only") {
    val plan = Resolver.placeBet(wallet, Vector(active("r1", 300)), Stake(600), Flags()).fold(d => fail(d.toString), identity)
    assertEquals(plan.realLeg, 400L)
    assertEquals(plan.recordLegs.map(_.amount).sum, 200L)
    assertEquals(plan.newRealBalance, 600L)
  }

  test("an allow-negative bet beyond available is placed against real, as an allow-negative bet is today") {
    val plan = Resolver.placeBet(wallet, Vector.empty, Stake(500), Flags(allowNegative = true)).fold(d => fail(d.toString), identity)
    assertEquals(plan.newRealBalance, 500L)
  }

  test("a hold beyond available is refused as insufficient funds with the hold's message, after a pending request and before turnover") {
    val beyond = Resolver.withdraw(wallet, Vector.empty, WithdrawInput(amount = 500))
    assertEquals(beyond.left.map(d => (d.code, d.message)), Left((Codes.InsufficientFunds, Messages.InsufficientRealBalance)))
    val pending = Resolver.withdraw(wallet, Vector.empty, WithdrawInput(amount = 500, pendingWithdrawalRequestRef = "w-0"))
    assertEquals(pending.left.map(_.code), Left(Codes.WithdrawalRequestAlreadyPending))
    val turnover = Resolver.withdraw(wallet.copy(depositTurnoverPending = 10), Vector.empty, WithdrawInput(amount = 500))
    assertEquals(turnover.left.map(_.code), Left(Codes.InsufficientFunds), "insufficient is checked before turnover")
  }

  test("a hold within available pays and leaves real in real terms") {
    val plan = Resolver.withdraw(wallet, Vector.empty, WithdrawInput(amount = 400)).fold(d => fail(d.toString), identity)
    assertEquals((plan.paid, plan.newReal), (400L, 600L))
  }

  test("a hold whose clawback would leave available short is refused as insufficient after the forfeit") {
    val rec  = active("r1", 100).copy(forfeitOnWithdrawal = Policy.FundsAndWinnings)
    val deny = Resolver.withdraw(wallet, Vector(rec), WithdrawInput(amount = 400, clawback = 50))
    assertEquals(deny.left.map(_.code), Left(Codes.WithdrawalInsufficientAfterBonusForfeit))
  }

  test("a cancel's clawback is clamped to available, never taking reserved money (R14)") {
    val plan = Resolver.cancelRecord(wallet, Vector(active("r1", 100)), clawback = 700).fold(d => fail(d.toString), identity)
    assertEquals(plan.newRealBalance, 600L)
    val unreserved = Resolver.cancelRecord(Wallet(realBalance = 1000), Vector(active("r1", 100)), clawback = 700).fold(d => fail(d.toString), identity)
    assertEquals(unreserved.newRealBalance, 300L, "with nothing reserved the clamp is real balance, as the original")
  }
