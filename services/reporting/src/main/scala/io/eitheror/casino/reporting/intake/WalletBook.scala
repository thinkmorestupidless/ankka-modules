package io.eitheror.casino.reporting.intake

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.ledger.Types

import java.time.{Instant, ZoneId}

/** A restricted funds record as the last movement that changed it left it (spec 015 R11); only open ones are kept. */
final case class OpenRecord(awardId: String, status: String, balance: Long = 0, progress: Long = 0, requirement: Long = 0)

object OpenRecord:
  /** Open means `ACTIVE` or `CONVERTED_PENDING_CAP`, as the wallet's `Status.isOpen` (spec 002 FR-020). */
  def isOpen(status: String): Boolean = status == "ACTIVE" || status == "CONVERTED_PENDING_CAP"

/** The day a round's first stake fell on, so its later legs count on it (attribution's `RoundIndex`). */
final case class RoundDay(roundId: String, day: String)

/** A withdrawal's hold, placed and not yet released or reversed. */
final case class Hold(requestId: String, amount: Long)

/**
 * One wallet as reporting knows it (data-model.md `WalletBook`, id `g<n>/<player>/<currency>`): the lifetimes of the
 * player report, the balances and pools after the last movement, the open restricted funds records, the open rounds'
 * days, the holds, and the ids of the last 1,000 facts, so a redelivery is ignored. `wagered` is stakes less
 * rollbacks and `won` wins and provider credits, so `wagered − won` is the wallet's GGR (FR-008's lifetime GGR).
 */
final case class BookState(
    player: String = "",
    currency: String = "",
    deposits: Long = 0,
    wagered: Long = 0,
    won: Long = 0,
    real: Long = 0,
    restricted: Long = 0,
    depositPool: Long = 0,
    convertedPool: Long = 0,
    records: Map[String, OpenRecord] = Map.empty,
    rounds: Vector[RoundDay] = Vector.empty,
    holds: Vector[Hold] = Vector.empty,
    seen: Vector[String] = Vector.empty
):
  def roundDay(roundId: String): Option[String] = rounds.find(_.roundId == roundId).map(_.day)

sealed trait BookEvent:
  def factId: String

/**
 * One movement (data-model.md): `kind` the ledger's type (`Types`), empty for none; `amount` the movement's, signed
 * for an adjustment; `depositPart` its deposit-origin part; `day` its own day in the zone and `roundDay` its round's;
 * `casino` false for spec 022's families, which no casino figure reads (research R4). Beside data-model's fields it
 * carries the player, the currency, the transaction id the ledger shows and the balances after, which the state keeps.
 */
final case class Moved(factId: String, family: String, kind: String, amount: Long, depositPart: Long, roundId: Option[String],
    reference: String, day: String, roundDay: Option[String], at: Instant, casino: Boolean, accountKind: Option[String],
    player: String, currency: String, transactionId: String, realAfter: Long, restrictedAfter: Long,
    depositPoolAfter: Option[Long] = None, convertedPoolAfter: Option[Long] = None) extends BookEvent

/** The restricted funds records a movement changed, as they stand after it. */
final case class RecordsChanged(factId: String, records: Vector[OpenRecord]) extends BookEvent

/** What the intake decides of a wallet fact (pure). */
object Book:
  val OpenRounds = 500
  val Holds      = 100

  /** Spec 022's families: a reservation, a trade's settlement, a resolution payment, an account's split or merge. */
  def casino(fact: f.MoneyTransactionsFact): Boolean = fact match
    case _: f.ReservationPlaced | _: f.ReservationReleased | _: f.SettlementDebit | _: f.SettlementCredit |
        _: f.ResolutionPayment | _: f.AccountDebit | _: f.AccountCredit => false
    case _ => true

  def family(fact: f.MoneyTransactionsFact): String =
    f.MoneyTransactions.families.find(_.sample.getClass == fact.getClass).fold(fact.getClass.getSimpleName)(_.name)

  private def units(m: Option[io.eitheror.casino.contract.money.MinorUnits]): Long = m.fold(0L)(_.value)

  /** The movement's amount, its deposit part, its round and the reference the ledger shows. */
  private def parts(fact: f.MoneyTransactionsFact): (Long, Long, Option[String], String) = fact match
    case x: f.DepositConfirmed         => (x.amountCents.value, units(x.depositPartCents), None, x.paymentReference)
    case x: f.BetPlaced                => (x.amountCents.value, units(x.depositPartCents), Some(x.roundId), x.roundId)
    case x: f.WinCredited              => (x.amountCents.value, units(x.depositPartCents), Some(x.roundId), x.roundId)
    case x: f.BetRolledBack            => (x.amountCents.value, units(x.depositPartCents), Some(x.roundId), x.roundId)
    case x: f.RoundSettledAsLoss       => (0L, 0L, Some(x.roundId), x.roundId)
    case x: f.RestrictedFundsAwarded   => (x.amountCents.value, 0L, None, x.awardId)
    case x: f.RestrictedFundsConverted => (x.amountCents.value, units(x.depositPartCents), None, x.awardId)
    case x: f.RestrictedFundsForfeited => (x.amountCents.value, units(x.depositPartCents), None, x.awardId)
    case x: f.RestrictedFundsClosed    => (0L, 0L, None, x.awardId)
    case x: f.AdminAdjusted            => (x.amount.value, units(x.depositPartCents), None, x.transactionId)
    case x: f.HoldPlaced               => (x.amountCents.value, units(x.depositPartCents), None, x.withdrawalRequestId)
    case x: f.HoldReleased             => (x.amountCents.value, units(x.depositPartCents), None, x.withdrawalRequestId)
    case x: f.WalletWithdrawalReversed => (x.amountCents.value, units(x.depositPartCents), None, x.withdrawalRequestId)
    case x: f.ProviderCredited         => (x.amountCents.value, units(x.depositPartCents), None, x.providerRef)
    case x: f.ReservationPlaced        => (x.amountCents.value, 0L, None, x.orderId)
    case x: f.ReservationReleased      => (x.amountCents.value, 0L, None, x.orderId)
    case x: f.SettlementDebit          => (x.amountCents.value, units(x.depositPartCents), None, x.tradeId)
    case x: f.SettlementCredit         => (x.amountCents.value, units(x.depositPartCents), None, x.tradeId)
    case x: f.ResolutionPayment        => (x.amountCents.value, units(x.depositPartCents), None, x.positionId)
    case x: f.AccountDebit             => (x.amountCents.value, units(x.depositPartCents), None, x.reference)
    case x: f.AccountCredit            => (x.amountCents.value, units(x.depositPartCents), None, x.reference)

  /** The records the movement carries (ten families, research R11); absent means it changed none. */
  def records(fact: f.MoneyTransactionsFact): Vector[f.RecordProgress] = (fact match
    case x: f.BetPlaced                => x.records
    case x: f.WinCredited              => x.records
    case x: f.BetRolledBack            => x.records
    case x: f.RestrictedFundsAwarded   => x.records
    case x: f.RestrictedFundsConverted => x.records
    case x: f.RestrictedFundsForfeited => x.records
    case x: f.RestrictedFundsClosed    => x.records
    case x: f.AdminAdjusted            => x.records
    case x: f.HoldPlaced               => x.records
    case x: f.ProviderCredited         => x.records
    case _                             => None
  ).getOrElse(Vector.empty)

  private def accountKind(fact: f.MoneyTransactionsFact): Option[String] = fact match
    case x: f.AccountDebit  => Some(x.accountKind)
    case x: f.AccountCredit => Some(x.accountKind)
    case _                  => None

  /**
   * The events of one fact: none for one already seen; else its `Moved`, then its records when it carries any. A
   * round's day is the day its first stake was seen on, as attribution's `RoundIndex`; a leg of a round no stake was
   * seen for counts on its own day.
   */
  def move(s: BookState, factId: String, fact: f.MoneyTransactionsFact, zone: ZoneId): Vector[BookEvent] =
    if s.seen.contains(factId) then Vector.empty
    else
      val (amount, depositPart, roundId, reference) = parts(fact)
      val day      = Days.dayOf(fact.occurredAt, zone)
      val roundDay = roundId.map(r => s.roundDay(r).getOrElse(day))
      val moved = Moved(factId, family(fact), Types.of(fact).getOrElse(""), amount, depositPart, roundId, reference, day,
        roundDay, fact.occurredAt, casino(fact), accountKind(fact), fact.playerId, fact.currency.code, fact.transactionId,
        fact.realBalanceAfter.value, fact.restrictedBalanceAfter.value, fact.depositPoolAfter.map(_.value),
        fact.convertedPoolAfter.map(_.value))
      val changed = records(fact).map(r =>
        OpenRecord(r.awardId, r.status, r.balanceCents.value, r.progressCents.value, r.requirementCents.value))
      if changed.isEmpty then Vector(moved) else Vector(moved, RecordsChanged(factId, changed))

  /** What a movement adds to the lifetimes (deposits, wagered, won); spec 022's families add nothing. */
  def lifetimes(m: Moved): (Long, Long, Long) = if !m.casino then (0L, 0L, 0L) else m.family match
    case "deposit_confirmed"                  => (m.amount, 0L, 0L)
    case "bet_placed"                         => (0L, m.amount, 0L)
    case "bet_rolled_back"                    => (0L, -m.amount, 0L)
    case "win_credited" | "provider_credited" => (0L, 0L, m.amount)
    case _                                    => (0L, 0L, 0L)

  /** The open records after a change: an upsert by award, a record no longer open removed. */
  def records(open: Map[String, OpenRecord], changed: Vector[OpenRecord]): Map[String, OpenRecord] =
    changed.foldLeft(open)((acc, rec) => if OpenRecord.isOpen(rec.status) then acc.updated(rec.awardId, rec) else acc - rec.awardId)

  def apply(s: BookState, event: BookEvent): BookState = event match
    case m: Moved =>
      val (d, w, n) = Book.lifetimes(m)
      val lived = s.copy(deposits = s.deposits + d, wagered = s.wagered + w, won = s.won + n)
      val rounds = m.roundId match
        case Some(r) if m.family == "bet_placed" && s.roundDay(r).isEmpty =>
          (lived.rounds :+ RoundDay(r, m.roundDay.getOrElse(m.day))).takeRight(OpenRounds)
        case _ => lived.rounds
      val holds = m.family match
        case "hold_placed"                            => (lived.holds.filterNot(_.requestId == m.reference) :+ Hold(m.reference, m.amount)).takeRight(Holds)
        case "hold_released" | "withdrawal_reversed" => lived.holds.filterNot(_.requestId == m.reference)
        case _                                        => lived.holds
      lived.copy(player = m.player, currency = m.currency, real = m.realAfter, restricted = m.restrictedAfter,
        depositPool = m.depositPoolAfter.getOrElse(s.depositPool), convertedPool = m.convertedPoolAfter.getOrElse(s.convertedPool),
        rounds = rounds, holds = holds, seen = FactId.remember(s.seen, m.factId))
    case r: RecordsChanged =>
      // Several facts of one movement carry the same list: an upsert by award, never an addition.
      s.copy(records = records(s.records, r.records))

/** A wallet fact and the zone its day is counted in. The contract's codec; the entity stores only its events. */
final case class MoveFact(factId: String, zone: String, fact: f.MoneyTransactionsFact)

final class WalletBook(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[BookState, BookEvent]:
  def emptyState: BookState = BookState()

  def applyEvent(event: BookEvent): BookState = Book.apply(currentState, event)

  /** Whether the fact was new. */
  def move(c: MoveFact): Effect[Boolean] =
    val events = Book.move(currentState, c.factId, c.fact, ZoneId.of(c.zone))
    if events.isEmpty then effects.reply(false) else effects.persistAll(events).thenReply(_ => true)

  def get: ReadOnlyEffect[BookState] = effects.reply(currentState)

object WalletBook extends EventSourcedEntity.Companion[WalletBook, BookState, BookEvent](
      componentId = ComponentId("wallet-book"),
      stateSerializer = ReportingCodecs.serializer[BookState]("wallet-book"),
      eventSerializer = ReportingCodecs.serializer[BookEvent]("wallet-book-event")
    ):
  given Serializer[MoveFact] = Serializer.json[MoveFact]("reporting.wallet-book-move")(using JsonCodecMaker.make[MoveFact])
  import ReportingCodecs.given_Serializer_Boolean

  override def snapshotEvery: Option[Int] = Some(100)

  def id(generation: Int, player: String, currency: String): String = Generation.id(generation, player, currency)

  def create(context: EventSourcedEntityContext) = new WalletBook(context)

  val move = command("move")(_.move)
  val get  = query("get")(_.get)

/** `money.transactions` into each wallet's `WalletBook` (data-model.md *Consumers*); a non-player account's message is not a wallet's. */
final class MoneyFactsConsumer(client: ComponentClient, settings: Settings)
    extends Consumer[Option[f.MoneyTransactionsMessage], Nothing]:
  def onMessage(fact: Option[f.MoneyTransactionsMessage]): Effect = fact match
    case None =>
      Lenient.unreadable(f.MoneyTransactions.name, messageContext.subject)
      effects.ignore()
    case Some(m: f.MoneyTransactionsFact) =>
      client.forEventSourcedEntity(EntityId(WalletBook.id(settings.generation, m.playerId, m.currency.code)))
        .call(WalletBook.move).invoke(MoveFact(FactId.of(f.MoneyTransactions, m), settings.reportingZone.getId, m)): Unit
      Coverage.observed(client, settings, f.MoneyTransactions.name, m.occurredAt)
      effects.done()
    case Some(_) => effects.ignore()

final class MoneyFacts(settings: Settings) extends Consumer.Companion[MoneyFactsConsumer, Option[f.MoneyTransactionsMessage], Nothing](
      Generation.componentId("money-facts", settings.generation), Lenient.from(f.MoneyTransactions)):
  def create(ctx: ConsumerContext) = new MoneyFactsConsumer(ctx.componentClient, settings)
