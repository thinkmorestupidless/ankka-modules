package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.MoneyEvent
import io.eitheror.casino.attribution.record.AttributionRecord
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

/**
 * `casino.withdrawals` (research R9): requested, completed, failed and reversed are reported under the
 * payout id; approved and cancelled are nothing. The dedupe id is `<family>/<payoutId>`.
 */
final class WithdrawalsFacts(context: ConsumerContext) extends Consumer[Option[CasinoWithdrawalsFact], Unit]:
  def onMessage(fact: Option[CasinoWithdrawalsFact]): Effect = fact match
    case Some(f: WithdrawalRequested) =>
      record(f.playerId, MoneyEvent.WithdrawalRequested, "withdrawal_requested", f.payoutId, f.amountCents.value, f.currency.code, f.occurredAt)
    case Some(f: WithdrawalCompleted) =>
      record(f.playerId, MoneyEvent.WithdrawalCompleted, "withdrawal_completed", f.payoutId, f.amountCents.value, f.currency.code, f.occurredAt)
    case Some(f: WithdrawalFailed) =>
      record(f.playerId, MoneyEvent.WithdrawalFailed, "withdrawal_failed", f.payoutId, f.amountCents.value, f.currency.code, f.occurredAt)
    case Some(f: WithdrawalReversed) =>
      record(f.playerId, MoneyEvent.WithdrawalReversed, "withdrawal_reversed", f.payoutId, f.amountCents.value, f.currency.code, f.occurredAt)
    case Some(_) => effects.ignore()
    case None =>
      WithdrawalsFacts.log.warn("a casino.withdrawals fact this service cannot read was skipped (subject {})", messageContext.subject)
      effects.ignore()

  private def record(player: String, kind: String, family: String, payout: String, amount: Long, currency: String,
      at: java.time.Instant): Effect =
    context.componentClient.forEventSourcedEntity(EntityId(player)).call(AttributionRecord.money)
      .invoke(MoneyEvent(kind, currency, amount, payout, at, s"$family/$payout")): Unit
    effects.done()

object WithdrawalsFacts extends Consumer.Companion[WithdrawalsFacts, Option[CasinoWithdrawalsFact], Unit](
      componentId = ComponentId("withdrawals-facts"),
      source = ChangeSource.fromTopic(CasinoWithdrawals.name, Lenient.serializer(CasinoWithdrawals.serializer), StartFrom.Earliest,
        TopicOptions(Some(CasinoWithdrawals.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[WithdrawalsFacts])

  def create(ctx: ConsumerContext) = new WithdrawalsFacts(ctx)
