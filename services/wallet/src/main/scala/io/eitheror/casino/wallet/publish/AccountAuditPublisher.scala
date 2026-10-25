package io.eitheror.casino.wallet.publish

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{MoneyTransactions, MoneyTransactionsMessage, PlatformAudit, PlatformAuditFact}
import io.eitheror.casino.wallet.accounts.{AccountEntity, AccountEvent}

/**
 * A non-player account's facts (spec 022 contracts/facts.md): the operator's adjustment on
 * `money.transactions` under the account's key, and the claim, the credit line and every refusal on
 * `platform.audit`. One consumer per topic, as a consumer produces one type.
 */
final class AccountAuditPublisher(brand: String) extends Consumer[AccountEvent, PlatformAuditFact]:
  def onMessage(event: AccountEvent): Effect =
    AccountFacts.audit(brand, messageContext.subject, messageContext.sequenceNumber, event) match
      case Some(p) => effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Publish.withId(p.dedupeId))))
      case None    => effects.ignore()

object AccountAuditPublisher
    extends Consumer.Companion[AccountAuditPublisher, AccountEvent, PlatformAuditFact](
      componentId = ComponentId("account-audit-publisher"),
      source = ChangeSource.eventsOf(AccountEntity)
    ):
  def create(ctx: ConsumerContext) = new AccountAuditPublisher(sys.env.getOrElse("CASINO_BRAND", ""))
  override val outputSerializer: Option[Serializer[PlatformAuditFact]] = Some(PlatformAudit.serializer)
  override val produceTo: Option[String]                               = Some(PlatformAudit.name)
  override val produces: Option[Publication] = Some(Publication(PlatformAudit.name, Some(PlatformAudit.contract), None))

/** `account_adjusted` on `money.transactions`, keyed `<kind>/<name>/<currency>`. */
final class AccountTransactionsPublisher extends Consumer[AccountEvent, MoneyTransactionsMessage]:
  def onMessage(event: AccountEvent): Effect =
    AccountFacts.moneyTransactions(messageContext.subject, event) match
      case Some(p) => effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Publish.withId(p.dedupeId))))
      case None    => effects.ignore()

object AccountTransactionsPublisher
    extends Consumer.Companion[AccountTransactionsPublisher, AccountEvent, MoneyTransactionsMessage](
      componentId = ComponentId("account-transactions-publisher"),
      source = ChangeSource.eventsOf(AccountEntity)
    ):
  def create(ctx: ConsumerContext) = new AccountTransactionsPublisher
  override val outputSerializer: Option[Serializer[MoneyTransactionsMessage]] = Some(MoneyTransactions.serializer)
  override val produceTo: Option[String]                                   = Some(MoneyTransactions.name)
  override val produces: Option[Publication] = Some(Publication(MoneyTransactions.name, Some(MoneyTransactions.contract), None))
