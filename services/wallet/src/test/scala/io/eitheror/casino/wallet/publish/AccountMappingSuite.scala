package io.eitheror.casino.wallet.publish

import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.wallet.accounts.AccountEvent.*
import io.eitheror.casino.wallet.entity.Outcome

import java.time.Instant

/** contracts/facts.md for a non-player account: the adjustment's fact under the account's key, and the audit records. */
class AccountMappingSuite extends munit.FunSuite:

  private val t0      = Instant.parse("2026-10-25T10:00:00Z")
  private val account = "clearing/north/EUR"
  private val ok      = Outcome()

  test("an adjustment is account_adjusted keyed by the account, with the category as its reason, never the text") {
    val e    = AccountAdjusted("a-1", "d", "t-a", 500, "net settled with the pool, per the ops sheet", "correction", "op-7", 0, 500, t0, ok)
    val p    = AccountFacts.moneyTransactions(account, e).get
    val fact = p.fact.asInstanceOf[f.AccountAdjusted]
    assertEquals((fact.key, fact.accountKind, fact.accountName, fact.reason, fact.actorId), (account, "clearing", "north", "correction", "op-7"))
    assertEquals((fact.amount.value, fact.balanceAfter.value, fact.creditLine.value, p.dedupeId), (500L, 0L, 500L, "t-a"))
    assert(!AccountFacts.audit("goldvibe", account, 3, e).get.fact.toString.contains("ops sheet"))
  }

  test("a debit or a credit of the account publishes nothing: the wallet's side of the move is the fact") {
    assertEquals(AccountFacts.moneyTransactions(account, AccountDebited("d-1", "d", "t", 100, "clearing", "ada", "d-1", -100, t0, ok)), None)
    assertEquals(AccountFacts.audit("goldvibe", account, 2, AccountCredited("c-1", "d", "t", 100, "clearing", "ada", "c-1", 0, t0, ok)), None)
  }

  test("the claim, the credit line and a refusal are audited with the account as the subject, under <accountId>/<sequence>") {
    val claimed = AccountFacts.audit("goldvibe", account, 1, AccountClaimed("service:pool-north/clearing", t0)).get
    val c       = claimed.fact.asInstanceOf[f.AuditRecord]
    assertEquals((c.action, c.actorId, c.subjectKind, c.subjectId, claimed.dedupeId), ("claim", "service:pool-north/clearing", "account", account,
      s"$account/1"))
    val line = AccountFacts.audit("goldvibe", account, 2, CreditLineSet(500, "service:pool-north/clearing", t0)).get.fact.asInstanceOf[f.AuditRecord]
    assertEquals(line.action, "set-credit-line")
    val refused = CommandRefused("d-9", "d", "debit", "CREDIT_LINE_REACHED", "m", Map.empty, t0, ok)
    val r       = AccountFacts.audit("goldvibe", account, 3, refused).get.fact.asInstanceOf[f.AuditRecord]
    assertEquals((r.action, r.outcome, r.reasonCode), ("debit", "CREDIT_LINE_REACHED", Some("CREDIT_LINE_REACHED")))
  }

  test("the publishers key by the account and set the dedupe id as ce-id") {
    val e      = AccountAdjusted("a-1", "d", "t-a", 500, "r", "correction", "op-7", 0, 500, t0, ok)
    val result = ConsumerTestKit.of(AccountTransactionsPublisher).onMessage(e, subject = account, sequenceNumber = 4)
    assertEquals(result.recordKeys, Vector(Some(account)))
    assertEquals(result.messages.head.metadata.get("ce-id"), Some("t-a"))
    val audit = ConsumerTestKit.of(AccountAuditPublisher).onMessage(AccountClaimed("o", t0), subject = account, sequenceNumber = 1)
    assertEquals(audit.messages.head.metadata.get("ce-id"), Some(s"$account/1"))
  }
