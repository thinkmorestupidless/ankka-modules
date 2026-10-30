package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.{MoneyEvent, Wiring}
import io.eitheror.casino.attribution.money.*
import io.eitheror.casino.attribution.money.RealMoney.Leg
import io.eitheror.casino.attribution.record.AttributionRecord
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

/**
 * `money.transactions` (research R10): every family advances the wallet's cursor, so the next fact's real
 * movement is measured from it; the real legs go to the tally of their round's day (the day of its first
 * stake, else the leg's own), and a restricted award, or a provider credit landing on restricted money,
 * is bonus cost. A redelivered fact is given the delta it was given before, and every write after the
 * cursor is idempotent by the dedupe id, so a delivery cut short is finished by the next.
 */
final class MoneyFacts(context: ConsumerContext) extends Consumer[Option[MoneyTransactionsMessage], Unit]:
  private def client = context.componentClient

  def onMessage(fact: Option[MoneyTransactionsMessage]): Effect = fact match
    case Some(f: MoneyTransactionsFact) =>
      val currency = f.currency.code
      val dedupe   = RealMoney.dedupeId(f, None)
      // the cursor keeps the wallet's recent dedupe ids; since spec 025 a fact's amount is its deposit
      // part, not the cursor's delta
      client.forKeyValueEntity(EntityId(WalletCursor.id(f.playerId, currency))).call(WalletCursor.advance)
        .invoke(Advance(dedupe, f.realBalanceAfter.value)): Unit
      RealMoney.counted(f).filter(l => l.amount != 0 || l.kind == Leg.Loss).foreach(tally(f, currency, dedupe, _))
      RealMoney.bonusCost(f).foreach { (amount, reference) =>
        client.forEventSourcedEntity(EntityId(f.playerId)).call(AttributionRecord.money)
          .invoke(MoneyEvent(MoneyEvent.BonusCost, currency, amount, reference, f.occurredAt, dedupe)): Unit
      }
      effects.done()
    // a non-player account's message (spec 022's account_adjusted): no player's money, no wallet's cursor
    case Some(_) => effects.ignore()
    case None =>
      MoneyFacts.log.warn("a money.transactions fact this service cannot read was skipped (subject {})", messageContext.subject)
      effects.ignore()

  /** A leg on its round's day: a stake remembers the day (the first one wins); a later leg reads it. */
  private def tally(f: MoneyTransactionsFact, currency: String, dedupe: String, leg: Leg): Unit =
    val own   = Days.dayOf(f.occurredAt, Wiring.settings.get().reportingZone)
    val round = client.forKeyValueEntity(EntityId(RoundIndex.id(f.playerId, currency, leg.roundId)))
    val day =
      if leg.kind == Leg.Stake then round.call(RoundIndex.remember).invoke(Remember(own, f.occurredAt)).day.getOrElse(own)
      else round.call(RoundIndex.get).invoke().day.getOrElse(own)
    client.forEventSourcedEntity(EntityId(DayTally.id(f.playerId, currency, day))).call(DayTally.leg)
      .invoke(Tally.LegCmd(f.playerId, currency, day, leg.kind, leg.roundId, dedupe, leg.amount, f.occurredAt)): Unit

object MoneyFacts extends Consumer.Companion[MoneyFacts, Option[MoneyTransactionsMessage], Unit](
      componentId = ComponentId("money-facts"),
      source = ChangeSource.fromTopic(MoneyTransactions.name, Lenient.serializer(MoneyTransactions.serializer), StartFrom.Earliest,
        TopicOptions(Some(MoneyTransactions.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[MoneyFacts])

  def create(ctx: ConsumerContext) = new MoneyFacts(ctx)
