package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.WalletEvent.HoldPlaced
import io.eitheror.casino.wallet.funding.Status

/** features/wallet/withdrawals.feature (User Story 4). */
class WithdrawalsFeatures extends WalletFeatures("withdrawals.feature"):

  private def placed: HoldPlaced = lastEvents.collectFirst { case e: HoldPlaced => e }.getOrElse(fail(s"no hold placed: $lastOutcome"))

  private def recordUnder(policy: String) =
    state.records.find(_.record.forfeitOnWithdrawal == policy).map(_.record).getOrElse(fail(s"no record under $policy"))

  private def reverse(request: String): Result =
    run(walletKit.call(WalletEntity.reverse)(Reverse(s"reverse-$request", request, now())))

  private def confirmed(amount: Long, request: String): Unit =
    seeding(_.copy(real = 10000L))
    hold(amount, request): Unit
    run(walletKit.call(WalletEntity.confirm)(Confirm(s"confirm-$request", request, now()))): Unit

  Given("a real balance of {string}, no restricted funds and no deposit turnover pending") { (r: String) =>
    seeding(_.copy(real = long(r), pending = 0L))
  }

  When("a hold of {string} is placed for request {string}") { (a: String, request: String) => act(hold(long(a), request)): Unit }

  Then("{string} is the pending request") { (request: String) => assertEquals(state.pendingRequestId, Some(request)) }

  Then("a withdrawal transaction of {string} is written") { (a: String) =>
    assert(rowsWritten.exists(r => r.`type` == "withdrawal" && r.amount == long(a)), rowsWritten.toString)
  }

  Given("a hold is pending for request {string}") { (request: String) =>
    seeding(_.copy(real = 10000L))
    hold(4000L, request): Unit
  }

  Given("a real balance of {string} and deposit turnover pending of {string}") { (r: String, p: String) =>
    seeding(_.copy(real = long(r), pending = long(p)))
  }

  Given("an active restricted funds record of {string} under withdrawal policy {string} with a clawback of {string} computed for it") {
    (b: String, policy: String, c: String) =>
      addRecord(record("r1", long(b), edit = _.copy(forfeitOnWithdrawal = policy)))
      clawbacks += focusId -> long(c)
  }

  Then("the clawback of {string} is applied once") { (c: String) =>
    if lastOutcome.accepted then assertEquals(placed.clawback, long(c))
    else assertEquals(lastOutcome.details.get("clawback"), Some(c))
  }

  Then("it is refused as {string} because {string} is less than {string}") { (phrase: String, real: String, amount: String) =>
    refusedAs(phrase)
    assertEquals((lastOutcome.details.get("realBalance"), lastOutcome.details.get("amount")), (Some(real), Some(amount)))
    assert(long(real) < long(amount))
  }

  Given("active restricted funds records under withdrawal policies {string} and {string}") { (a: String, b: String) =>
    addRecord(record("r1", 1000L, priority = 0L, edit = _.copy(forfeitOnWithdrawal = a)))
    addRecord(record("r2", 1000L, priority = 1L, edit = _.copy(forfeitOnWithdrawal = b)))
  }

  Then("the record under {string} is forfeited with no clawback") { (policy: String) =>
    assertEquals(recordUnder(policy).status, Status.Forfeited)
    assertEquals(lastOutcome.clawbackApplied, Some(0L))
  }

  Then("the record under {string} is untouched") { (policy: String) =>
    val now = recordUnder(policy)
    assertEquals(now, recordBefore(now.id))
    assertEquals(now.status, Status.Active)
  }

  Then("the hold requires the player's confirmation") { () => assertEquals(lastOutcome.confirmationRequired, Some(true)) }

  Given("an active restricted funds record under withdrawal policy {string}") { (policy: String) =>
    addRecord(record("r1", 1000L, edit = _.copy(forfeitOnWithdrawal = policy)))
  }

  Then("its award is reported for cancellation with reason {string}") { (reason: String) =>
    assertEquals(lastOutcome.cancelGrants, Vector(focused.grantRef))
    assertEquals(lastOutcome.details.get("cancelReason"), Some(reason))
  }

  Given("a restricted funds record awaiting its cap with deposits since conversion of {string} and a remaining allowance of {string}") {
    (d: String, a: String) =>
      addRecord(record("r1", 0L, status = Status.ConvertedPendingCap, edit = _.copy(depositsSinceRelease = long(d), remainingAllowance = long(a))))
  }

  Then("{string} is drawn from deposits since conversion and {string} from the remaining allowance") { (d: String, a: String) =>
    assertEquals(placed.capDraws.map(c => (c.fromDeposits, c.fromAllowance)), Vector((long(d), long(a))))
  }

  Then("{string} is voided as {string}") { (v: String, kind: String) =>
    assertEquals(lastOutcome.voided, Some(long(v)))
    assert(rowsWritten.exists(r => r.`type` == kind && r.amount == long(v)), rowsWritten.toString)
  }

  Then("the paid amount is {string}") { (p: String) => assertEquals(lastOutcome.paid, Some(long(p))) }

  Then("real balance is debited by the full {string}") { (a: String) => assertEquals(before.realBalance - state.realBalance, long(a)) }

  Then("the record closes because its allowance is exhausted") { () =>
    assertEquals((focused.status, focused.remainingAllowance), (Status.Closed, 0L))
  }

  Given("a hold of {string} is pending for request {string}") { (a: String, request: String) =>
    seeding(_.copy(real = 10000L))
    hold(long(a), request): Unit
  }

  When("request {string} is released as {word}") { (request: String, outcome: String) =>
    act(run(walletKit.call(WalletEntity.release)(Release(nextKey("release"), request, outcome, now())))): Unit
  }

  Then("the real balance rises by {string} as a {string}") { (a: String, kind: String) =>
    assertEquals(state.realBalance - before.realBalance, long(a))
    assert(rowsWritten.exists(r => r.`type` == kind && r.amount == long(a)), rowsWritten.toString)
  }

  Then("no request is pending") { () => assertEquals(state.pendingRequestId, None) }

  When("request {string} is confirmed") { (request: String) =>
    act(run(walletKit.call(WalletEntity.confirm)(Confirm(nextKey("confirm"), request, now())))): Unit
  }

  /**
   * A release the feature does not name as a cancellation: the original answers a player's cancel of
   * a request that is not pending as WITHDRAWAL_REQUEST_NOT_CANCELLABLE, and a reject or failure as
   * REQUEST_NOT_PENDING, which is what this outline names.
   */
  When("request {string} is released") { (request: String) =>
    act(run(walletKit.call(WalletEntity.release)(Release(nextKey("release"), request, "rejected", now())))): Unit
  }

  Given("request {string} of {string} was confirmed") { (request: String, a: String) => confirmed(long(a), request) }

  When("request {string} is reversed") { (request: String) => act(reverse(request)): Unit }

  Given("request {string} of {string} was reversed") { (request: String, a: String) =>
    confirmed(long(a), request)
    firstOutcome = reverse(request).outcome
  }

  When("request {string} is reversed again") { (request: String) => act(reverse(request)): Unit }

  Then("the first outcome is returned") { () => assertEquals(lastOutcome, firstOutcome) }

  Then("the real balance is unchanged") { () => assertEquals(state.realBalance, before.realBalance) }

  Given("request {string} is not the pending request") { (_: String) => seeding(_.copy(real = 10000L)) }

