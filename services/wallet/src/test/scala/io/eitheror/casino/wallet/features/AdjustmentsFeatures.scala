package io.eitheror.casino.wallet.features

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal.reveal
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.funding.Status
import io.eitheror.casino.wallet.publish.Facts

/** features/wallet/adjustments.feature (User Story 5). */
class AdjustmentsFeatures extends WalletFeatures("adjustments.feature"):

  private val roundCause = Cause("round", "r-provider-win")

  // spec 030 W2: the cause on the answer, the row and the fact; the route's answer in MoneyPathsItSuite
  When("an operator credits {string} to real balance with reason {string} and the round it answers as its cause") {
    (a: String, reason: String) =>
      act(run(walletKit.call(WalletEntity.adjust)(Adjust(nextKey("adj"), "real", long(a), reason, "correction", "op-7", now(),
        Some(roundCause))))): Unit
  }

  Then("the answer carries the real balance before, {string}, and after, {string}") { (before: String, after: String) =>
    assertEquals((lastOutcome.balanceBefore, lastOutcome.balanceAfter, lastOutcome.cause), (Some(long(before)), Some(long(after)), Some(roundCause)))
  }

  Then("the {string} transaction carries the reason, the cause and the actor") { (kind: String) =>
    val row = rowsWritten.find(_.`type` == kind).getOrElse(fail(rowsWritten.toString))
    assertEquals((row.reason.map(_.reveal), row.cause, row.actorId, row.category),
      (Some("provider win not paid"), Some(roundCause), Some("op-7"), Some("correction")))
    val fact = lastEvents.flatMap(Facts.moneyTransactions(state.walletId, _)).map(_.fact).collectFirst { case a: f.AdminAdjusted => a }
    assertEquals(fact.map(a => (a.cause, a.actorId)), Some((Some(f.AdjustmentCause("round", "r-provider-win")), "op-7")))
  }

  private var sums: DepositSums = null

  When("an operator credits {string} to real balance with reason {string}") { (a: String, reason: String) =>
    act(adjust("real", long(a), reason)): Unit
  }

  Then("the player has a wallet in {string} with a real balance of {string} and no deposit") { (c: String, b: String) =>
    assert(!before.created, "the wallet existed before the adjustment")
    assertEquals((state.currency, state.created, state.realBalance, state.depositOrdinal, state.totalDeposits), (c, true, long(b), 0, 0L))
    // What the adjustment opened can be reserved against, which an uncreated wallet refuses.
    assert(reserve(long(b), "o1").outcome.accepted)
  }

  Then("an {string} transaction carries the reason and the actor") { (kind: String) =>
    val row = rowsWritten.find(_.`type` == kind).getOrElse(fail(rowsWritten.toString))
    assertEquals((row.reason.map(_.reveal), row.actorId), (Some("goodwill"), Some("op-7")))
  }

  When("an operator debits {string} from real balance with reason {string}") { (a: String, reason: String) =>
    act(adjust("real", -long(a), reason)): Unit
  }

  Then("it is refused as {string}, naming {string} and {string}") { (phrase: String, a: String, b: String) =>
    refusedAs(phrase)
    val message = lastOutcome.message.getOrElse("")
    assert(message.contains(a) && message.contains(b), message)
  }

  When("an operator credits {string} to real balance with no reason") { (a: String) => act(adjust("real", long(a), "")): Unit }

  Given("an active restricted funds record of {string} with no open round") { (b: String) =>
    addRecord(record("r1", long(b), edit = _.copy(openRoundCount = 0L)))
  }

  When("an operator debits {string} from restricted funds with reason {string}") { (a: String, reason: String) =>
    act(adjust("restricted", -long(a), reason)): Unit
  }

  Then("the record is active with a balance of {string}") { (b: String) =>
    assertEquals((focused.status, focused.balance), (Status.Active, long(b)))
  }

  When("an operator credits {string} to restricted funds with reason {string}") { (a: String, reason: String) =>
    act(adjust("restricted", long(a), reason)): Unit
  }

  When("a provider credit of {string} arrives") { (a: String) =>
    act(run(walletKit.call(WalletEntity.providerCredit)(ProviderCredit(nextKey("pc"), long(a), "fs-1", "free spins", "st8", now())))): Unit
  }

  Then("the record's balance rises by {string}") { (a: String) =>
    assertEquals(focused.balance - recordBefore(focusId).balance, long(a))
  }

  Given("an {string} transaction of {string}") { (kind: String, a: String) =>
    assertEquals(kind, "admin_adjust")
    act(adjust("real", long(a), "goodwill")): Unit
  }

  When("deposit totals, GGR or deposit limit usage are computed") { () =>
    sums = walletKit.call(WalletEntity.depositSums)(DepositSumsAt(clock)).replyValue
  }

  Then("the adjustment is not counted as a deposit") { () =>
    // Deposit limit usage: the sums responsible-gambling decides on.
    assertEquals(sums, DepositSums(0L, 0L, 0L))
    // Deposit totals: the wallet's own count and total of deposits.
    assertEquals((state.totalDeposits, state.depositOrdinal), (0L, 0))
    // GGR and every reader's totals: the fact on money.transactions is an adjustment, not a deposit.
    val facts = lastEvents.flatMap(Facts.moneyTransactions(state.walletId, _)).map(_.fact)
    assert(facts.nonEmpty && facts.forall(_.isInstanceOf[f.AdminAdjusted]), facts.toString)
    assert(rowsWritten.nonEmpty && rowsWritten.forall(_.`type` == "admin_adjust"), rowsWritten.toString)
  }
