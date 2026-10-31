package io.eitheror.casino.wallet.publish

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.wallet.accounts.{AccountEvent, AccountState}
import io.eitheror.casino.wallet.publish.Facts.Published

/**
 * A non-player account's events as the contract's facts (spec 022 contracts/facts.md), pure. The
 * adjustment is the account's one money fact: its debits and credits are the wallet's side of the
 * same move, which the wallet publishes. The claim, the credit line and every refusal are audited.
 */
object AccountFacts:

  /** `account_adjusted` on `money.transactions`, keyed by the account; the reason is the category, never the text. */
  def moneyTransactions(accountId: String, event: AccountEvent): Option[Published[f.MoneyTransactionsMessage]] = event match
    case e: AccountEvent.AccountAdjusted =>
      val currency = Currency.unsafe(accountId.drop(accountId.lastIndexOf('/') + 1))
      Some(Published(f.AccountAdjusted(AccountState.kindOf(accountId), AccountState.nameOf(accountId), currency, e.transactionId,
        SignedMinorUnits(e.amount), e.category, e.actorId, SignedMinorUnits(e.balanceAfter), MinorUnits.unsafe(math.max(0L, e.creditLine)), e.at),
        e.transactionId))
    case _ => None

  /** `platform.audit` in spec 001's shape: the account as the subject, `<accountId>/<sequence>` the record's id. */
  def audit(brand: String, accountId: String, sequence: Long, event: AccountEvent): Option[Published[f.PlatformAuditFact]] =
    final case class A(action: String, outcome: String, actorKind: String, actorId: String, txId: String, reason: Option[String],
        correlation: String, at: java.time.Instant)
    val a: Option[A] = event match
      case e: AccountEvent.AccountClaimed  => Some(A("claim", "accepted", "service", e.owner, "", None, accountId, e.at))
      case e: AccountEvent.CreditLineSet   => Some(A("set-credit-line", "accepted", "service", e.actor, "", None, accountId, e.at))
      case e: AccountEvent.CommandRefused  => Some(A(e.commandType, e.code, "service", "", "", Some(e.code), e.key, e.at))
      case e: AccountEvent.AccountAdjusted =>
        Some(A("adjust", "accepted", "operator", e.actorId, e.transactionId, Some(e.category), e.key, e.at))
      case _: AccountEvent.AccountDebited | _: AccountEvent.AccountCredited => None
    a.map { x =>
      val recordId = s"$accountId/$sequence"
      Published(
        f.AuditRecord(recordId = s"wallet/account/$recordId", brand = brand, service = "wallet", action = x.action, actorKind = x.actorKind,
          actorId = x.actorId, approverId = None, subjectKind = "account", subjectId = accountId, outcome = x.outcome,
          reasonCode = x.reason, changes = Vector.empty, correlationId = x.correlation, causationId = x.txId, recordClass = "money",
          occurredAt = x.at),
        recordId
      )
    }
