package io.eitheror.casino.rg.usage

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * What this service reads of other services' facts. Each reads its topic from `StartFrom.Latest` through a
 * lenient serializer: a fact it cannot read is logged by its subject, never its payload, and skipped.
 * Every command it sends is idempotent, so a redelivery changes nothing.
 */
object Read:
  val log = LoggerFactory.getLogger("io.eitheror.casino.rg.usage")

  def lenient[F <: Fact](topic: Topic[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    private val facts = topic.serializer
    def manifest: String                         = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]   = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

  def from[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, lenient(topic), StartFrom.Latest, TopicOptions(Some(topic.contract), None, false))

  def unreadable(topic: String, subject: String): Unit =
    log.warn("a {} fact this service cannot read was skipped (subject {})", topic, subject)

/** `money.transactions` into the usage of the player's currency (R7): deposits, and stakes less wins and rollbacks as the net loss. */
final class UsageConsumer(client: ComponentClient) extends Consumer[Option[MoneyTransactionsMessage], Nothing]:
  def onMessage(message: Option[MoneyTransactionsMessage]): Effect =
    // a non-player account's message (spec 022's account_adjusted) is read, and is no player's usage
    val fact = message.collect { case f: MoneyTransactionsFact => f }
    val record = fact.flatMap {
      case f: DepositConfirmed => Some(Record(f.transactionId, Record.Deposit, f.amountCents.value, f.occurredAt))
      case f: BetPlaced        => Some(Record(f.transactionId, Record.Loss, f.amountCents.value, f.occurredAt))
      case f: WinCredited      => Some(Record(f.transactionId, Record.Loss, -f.amountCents.value, f.occurredAt))
      case f: BetRolledBack    => Some(Record(f.transactionId, Record.Loss, -f.amountCents.value, f.occurredAt))
      case _                   => None
    }
    (fact, record) match
      case (Some(f), Some(r)) =>
        client.forKeyValueEntity(EntityId(UsageEntity.id(f.playerId, f.currency.code))).call(UsageEntity.record).invoke(r): Unit
        effects.done()
      case (Some(_), None) => effects.ignore()
      case (None, _) if message.isDefined => effects.ignore()
      case (None, _) =>
        Read.unreadable(MoneyTransactions.name, messageContext.subject)
        effects.ignore()

object UsageConsumer extends Consumer.Companion[UsageConsumer, Option[MoneyTransactionsMessage], Nothing](
      ComponentId("rg-usage-consumer"), Read.from(MoneyTransactions)):
  def create(ctx: ConsumerContext) = new UsageConsumer(ctx.componentClient)

/**
 * `casino.sessions` into the session day (R8): a start on the day it started; an end on the day it ended,
 * which closes the session if it started that day and is no change otherwise.
 */
final class SessionMinutesConsumer(client: ComponentClient) extends Consumer[Option[CasinoSessionsFact], Nothing]:
  def onMessage(fact: Option[CasinoSessionsFact]): Effect =
    def day(playerId: String, at: java.time.Instant) = client.forKeyValueEntity(EntityId(SessionDayEntity.id(playerId, at)))
    fact match
      case Some(f: GameSessionStarted) =>
        day(f.playerId, f.occurredAt).call(SessionDayEntity.started).invoke(SessionStarted(f.sessionId, f.occurredAt)): Unit
        effects.done()
      case Some(f: GameSessionEnded) =>
        day(f.playerId, f.occurredAt).call(SessionDayEntity.ended).invoke(SessionEnded(f.sessionId, f.occurredAt)): Unit
        effects.done()
      case Some(_) => effects.ignore()
      case None =>
        Read.unreadable(CasinoSessions.name, messageContext.subject)
        effects.ignore()

object SessionMinutesConsumer extends Consumer.Companion[SessionMinutesConsumer, Option[CasinoSessionsFact], Nothing](
      ComponentId("rg-session-minutes-consumer"), Read.from(CasinoSessions)):
  def create(ctx: ConsumerContext) = new SessionMinutesConsumer(ctx.componentClient)
