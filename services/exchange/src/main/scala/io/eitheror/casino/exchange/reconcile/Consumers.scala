package io.eitheror.casino.exchange.reconcile

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.exchange.{ExchangeEvent, MarketId, Side}
import com.thinkmorestupidless.predictions.exchange.components.{ExchangeEntity, PlacementOutcome, PlacementState, PlacementWorkflow}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.exchange.ExchangeRuntime
import io.eitheror.casino.exchange.ports.Ledger

import scala.concurrent.duration.*
import scala.util.Try

/** The checks' timers (R12): daily from a book's opening, and once after its finality, when the wallet's last facts are in. */
object Checks:
  def daily(market: String) = s"reconcile-daily/$market"
  def last(market: String)  = s"reconcile-final/$market"

  @volatile var dailyEvery: FiniteDuration = 24.hours
  @volatile var afterFinal: FiniteDuration = 10.minutes

/**
 * The exchange's side, from its journal: each event's moves onto its keys' tallies, the orders and
 * trades it names indexed to the market, and the checks scheduled at the opening and the finality.
 */
final class ExchangeTally(context: ConsumerContext, timers: () => TimerScheduler) extends Consumer[ExchangeEvent, Nothing]:
  def onMessage(event: ExchangeEvent): Effect =
    val market = messageContext.subject
    def index(id: String) = context.componentClient.forKeyValueEntity(EntityId(id)).call(MarketOfEntity.set).invoke(MarketOf(market)): Unit
    event match
      case e: ExchangeEvent.OrderPlaced => index(s"order/${Ledger.order(MarketId(market), e.order)}")
      case e: ExchangeEvent.TradeMade   => index(s"trade/${Ledger.trade(MarketId(market), e.trade)}")
      case _: ExchangeEvent.Opened =>
        timers().createRecurringTimer(Checks.daily(market), Checks.dailyEvery, Checks.dailyEvery, Reconcile.daily.deferred(market))
      case _: ExchangeEvent.Final =>
        timers().delete(Checks.daily(market))
        timers().createSingleTimer(Checks.last(market), Checks.afterFinal, Reconcile.last.deferred(market))
      case _ => ()
    Tally.record(context.componentClient, market, s"x/${messageContext.sequenceNumber}", Tally.moves(MarketId(market), event), exchange = true)
    effects.done()

final class ExchangeTallyCompanion(timers: () => TimerScheduler)
    extends Consumer.Companion[ExchangeTally, ExchangeEvent, Nothing](ComponentId("exchange-tally-exchange"), ChangeSource.eventsOf(ExchangeEntity)):
  def create(ctx: ConsumerContext) = new ExchangeTally(ctx, timers)

/**
 * The placements' side the journal does not hold: an order indexed to its market as soon as it is
 * placed (its reservation comes before the book), and a buy the book refused after its cost was held,
 * which the wallet reserved and released under ordinal 0.
 */
final class PlacementTally(context: ConsumerContext) extends Consumer[PlacementState, Nothing]:
  def onMessage(state: PlacementState): Effect =
    state.request.foreach { r =>
      val market = r.market.value
      val order  = Ledger.order(r.market, r.order.order)
      context.componentClient.forKeyValueEntity(EntityId(s"order/$order")).call(MarketOfEntity.set).invoke(MarketOf(market)): Unit
      state.outcome match
        case PlacementOutcome.Refused(_) if state.reserved && r.order.side == Side.Buy =>
          Tally.record(context.componentClient, market, s"r/$order", Tally.refusedMoves(RefusedPlacement(order, r.order.quantity * r.order.price)),
            exchange = true)
        case _ => ()
    }
    effects.done()

final class PlacementTallyCompanion(placement: PlacementWorkflow)
    extends Consumer.Companion[PlacementTally, PlacementState, Nothing](ComponentId("exchange-tally-placements"), ChangeSource.stateOf(placement)):
  def create(ctx: ConsumerContext) = new PlacementTally(ctx)

/**
 * The wallet's side, from `money.transactions` (the brand project's own topic, so no grant): each fact
 * of the exchange's onto its keys' tallies. A fact naming an order or trade not yet indexed is
 * thrown back, so it comes again once the exchange's consumers have caught up.
 */
final class WalletTally(context: ConsumerContext) extends Consumer[Option[f.MoneyTransactionsMessage], Nothing]:
  def onMessage(fact: Option[f.MoneyTransactionsMessage]): Effect =
    fact.flatMap(x => Tally.moves(x).map(x -> _)) match
      case None => effects.ignore()
      case Some((x: f.MoneyTransactionsFact, (where, moves))) =>
        val market = where.fold(
          id =>
            val m = context.componentClient.forKeyValueEntity(EntityId(id)).call(MarketOfEntity.get).invoke().market
            if m.isEmpty then throw IllegalStateException(s"$id is not yet known to the exchange") else m,
          identity
        )
        Tally.record(context.componentClient, market, s"w/${x.transactionId}", moves, exchange = false)
        effects.done()
      case Some(_) => effects.ignore()

/** A fact this service cannot read is skipped: another family of the wallet's, or a newer shape. */
object LenientMoney extends Serializer[Option[f.MoneyTransactionsMessage]]:
  private val facts = f.MoneyTransactions.serializer
  def manifest: String = facts.manifest
  def toBytes(value: Option[f.MoneyTransactionsMessage]): Array[Byte] = value.fold(Array.emptyByteArray)(facts.toBytes)
  def fromBytes(bytes: Array[Byte]): Option[f.MoneyTransactionsMessage] = Try(facts.fromBytes(bytes)).toOption

object WalletTally
    extends Consumer.Companion[WalletTally, Option[f.MoneyTransactionsMessage], Nothing](ComponentId("exchange-tally-wallet"),
      ChangeSource.fromTopic(f.MoneyTransactions.name, LenientMoney, StartFrom.Latest,
        TopicOptions(Some(f.MoneyTransactions.contract), None, false))):
  def create(ctx: ConsumerContext) = new WalletTally(ctx)

/**
 * A market's check when its timer falls due: its unbalanced keys, read from the keys' view, kept as
 * the market's next check. The view trails the keys by moments, and a check runs daily or ten minutes
 * after finality, so a key it has not yet seen is in the next one.
 */
final class Reconcile(context: TimedActionContext) extends TimedAction:
  def check(market: String, last: Boolean): Effect =
    context.componentClient.forKeyValueEntity(EntityId(market)).call(TallyEntity.checkNow)
      .invoke(CheckNow(ExchangeRuntime.now(), last, Reconcile.differences(market))): Unit
    effects.done()

object Reconcile extends TimedAction.Companion[Reconcile](ComponentId("exchange-reconcile")):
  import com.thinkmorestupidless.ankka.core.Serializers.given
  def create(context: TimedActionContext) = new Reconcile(context)

  /** A market's differences now: its keys whose two sides differ, as the keys' view has them. */
  def differences(market: String): Vector[Difference] =
    Tally.differences(ExchangeRuntime.views.forView(UnbalancedKeys).ask(UnbalancedKeys.of, "market" -> market))
  val daily = handler[String]("daily")(a => m => a.check(m, last = false))
  val last  = handler[String]("final")(a => m => a.check(m, last = true))

/** One `platform.audit` record per difference a check found (R12), naming the market, the family, the key and both figures. */
final class ReconcileAudit extends Consumer[Tally, f.PlatformAuditFact]:
  def onMessage(tally: Tally): Effect =
    val market = messageContext.subject
    tally.check.filter(_.differences.nonEmpty).fold(effects.ignore()) { c =>
      effects.produceAll(c.differences.zipWithIndex.map { (d, i) =>
        val id = s"reconcile/$market/${c.seq}/$i"
        val record = f.AuditRecord(recordId = id, brand = ExchangeRuntime.settings.brand, service = "exchange",
          action = "exchange.reconciliation_difference", actorKind = "system", actorId = "exchange", approverId = None,
          subjectKind = "market", subjectId = market, outcome = "refused", reasonCode = Some(d.family),
          changes = Vector(f.AuditChange("key", None, Some(d.key)), f.AuditChange("wallet", None, Some(d.wallet.toString)),
            f.AuditChange("exchange", None, Some(d.exchange.toString)), f.AuditChange("check", None, Some(if c.last then "final" else "daily"))),
          correlationId = id, causationId = id, recordClass = "system", occurredAt = c.at)
        effects.message(record: f.PlatformAuditFact).withKey(record.key).withMetadata(Metadata.empty.set("ce-id", id))
      })
    }

object ReconcileAudit
    extends Consumer.Companion[ReconcileAudit, Tally, f.PlatformAuditFact](ComponentId("exchange-reconcile-audit"), ChangeSource.stateOf(TallyEntity)):
  override val outputSerializer: Option[Serializer[f.PlatformAuditFact]] = Some(f.PlatformAudit.serializer)
  override val produceTo: Option[String]                                = Some(f.PlatformAudit.name)
  override val produces: Option[Publication] = Some(Publication(f.PlatformAudit.name, Some(f.PlatformAudit.contract), None))
  def create(ctx: ConsumerContext) = new ReconcileAudit
