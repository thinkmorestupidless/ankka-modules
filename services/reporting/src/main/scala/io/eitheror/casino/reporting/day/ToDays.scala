package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.Settings
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.intake.*
import io.eitheror.casino.reporting.predictions.{MarketBook, MarketBookEvent}

import java.time.{Clock, Duration, Instant, ZoneId}

/**
 * What each intake event moves on which day (research R3, R4), pure: a deposit, stake, win or rollback on its own
 * day and hour; the deposit pool's leg on its round's day; a payment's attempt, capture and lost dispute; a
 * withdrawal's request and completion; a grant's cost; a registration. Spec 022's families move no casino figure.
 */
object ToDays:
  private def put(m: Map[String, Delta], day: String, d: Delta): Map[String, Delta] =
    m.updatedWith(day)(_.fold(Some(d))(e => Some(e + d)))

  private def at(day: String, d: Delta): Map[String, Delta] = Map(day -> d)

  private def hour(t: Instant, zone: ZoneId): Int = Days.hourOf(t, zone)

  private val poolKind: Map[String, String] = Map(
    "bet_placed"      -> Figures.PoolLeg.Stake,
    "win_credited"    -> Figures.PoolLeg.Win,
    "bet_rolled_back" -> Figures.PoolLeg.Rollback
  )

  def wallet(e: BookEvent, zone: ZoneId): Map[String, Delta] = e match
    case m: Moved if m.casino =>
      val h = hour(m.at, zone)
      val own = m.family match
        case "deposit_confirmed"                  => Some(Money(deposits = m.amount, depositCount = 1))
        case "bet_placed"                         => Some(Money(stakes = m.amount))
        case "win_credited" | "provider_credited" => Some(Money(wins = m.amount))
        case "bet_rolled_back"                    => Some(Money(rollbacks = m.amount))
        case _                                    => None
      val pool = poolKind.get(m.family).map(k => Money(poolGgr = Figures.poolGgr(k, m.depositPart)))
      val withOwn = own.fold(Map.empty[String, Delta])(money => at(m.day, Delta(m.currency, h, money)))
      pool.fold(withOwn)(money => put(withOwn, m.roundDay.getOrElse(m.day), Delta(m.currency, h, money)))
    case _ => Map.empty

  def payment(e: PaymentEvent, zone: ZoneId): Map[String, Delta] =
    def on(t: Instant, currency: String, d: Delta) = at(Days.dayOf(t, zone), d.copy(currency = currency, hour = hour(t, zone)))
    e match
      case i: Initiated => on(i.at, i.currency, Delta(payments = Map(i.provider -> PaymentFigures(attempts = 1))))
      case c: Captured  => on(c.at, c.currency, Delta(payments = Map(c.provider -> PaymentFigures(completed = 1, value = c.amount))))
      // A lost dispute with no amount counts by number (research, Found during implementation, T009); with no
      // currency anywhere it has no currency's day to count on.
      case d: DisputeClosed if d.chargeback && d.currency.nonEmpty =>
        on(d.at, d.currency.get, Delta(money = Money(chargebacks = d.amount.getOrElse(0L), chargebackCount = 1)))
      case _ => Map.empty

  def withdrawal(e: WithdrawalEvent, zone: ZoneId): Map[String, Delta] =
    def on(t: Instant, d: Delta) = at(Days.dayOf(t, zone), d.copy(currency = e.currency, hour = hour(t, zone)))
    e match
      case r: WithdrawalEvent.Requested => on(r.at, Delta(gateways = Map(r.gateway -> GatewayFigures(requests = 1))))
      case c: WithdrawalEvent.Completed =>
        val seconds = c.requestedAt.map(r => Duration.between(r, c.at).getSeconds.max(0L))
        on(c.at, Delta(money = Money(withdrawals = c.amount, withdrawalCount = 1),
          gateways = Map(c.gateway -> GatewayFigures(completed = 1, completionSeconds = seconds.getOrElse(0L),
            timed = seconds.fold(0L)(_ => 1L), amount = c.amount))))
      case _ => Map.empty

  /** Bonus cost (spec 011 FR-028): a funds grant's amount when granted, a free-rounds grant's winnings when settled. */
  def grant(e: GrantEvent, zone: ZoneId): Map[String, Delta] =
    def cost(t: Instant, currency: String, amount: Long) =
      if amount == 0 then Map.empty[String, Delta]
      else at(Days.dayOf(t, zone), Delta(currency, hour(t, zone), Money(bonusCost = amount)))
    e match
      case g: GrantEvent.Granted          => g.currency.fold(Map.empty[String, Delta])(cost(g.at, _, g.amount))
      case w: GrantEvent.FreeSpinsSettled => cost(w.at, w.currency, w.win)
      case _                              => Map.empty

  /**
   * A market's day (FR-028, FR-031): a trade's contracts, notional, count and taker fees, a resolution payment and a
   * remainder kept, each on its own day, in the predictions section alone, so no casino figure holds a trade (FR-032).
   */
  def market(e: MarketBookEvent, zone: ZoneId): Map[String, Delta] =
    val figures = e match
      case t: MarketBookEvent.Traded => MarketDay(contracts = t.quantity, notional = t.notional, trades = 1, fees = t.fees)
      case p: MarketBookEvent.Paid   => MarketDay(payments = p.paid)
      case k: MarketBookEvent.Kept   => MarketDay(remainder = k.amount)
    at(Days.dayOf(e.at, zone), Delta(e.currency, hour(e.at, zone), predictions = Map(e.market -> figures)))

  def player(e: PlayerLogEvent, zone: ZoneId): Map[String, Delta] = e match
    case r: PlayerLogEvent.Registered =>
      at(Days.dayOf(r.registeredAt, zone), Delta(hour = hour(r.registeredAt, zone), registrations = 1, test = r.testAccount))
    case _                            => Map.empty

/**
 * The consumer `to-days` (data-model.md *Consumers*), one per intake entity, since a consumer reads one source: each
 * event of this generation's entities folded into its days' `BrandDay`, under the source entity and the event's
 * sequence, so a redelivery folds nothing twice. The delta is marked a test account's when the player's `PlayerLog`
 * says so at the fold (research R4); a flag changed later does not restate a day already folded. Every event read, of
 * this generation or another, is told to the consumer's `FoldProgress` with its fact time, which `close-days` waits on
 * (research R7).
 */
abstract class DayFolder[E](client: ComponentClient, settings: Settings, clock: Clock, source: String, consumer: String)
    extends Consumer[E, Nothing]:
  protected def deltas(e: E, zone: ZoneId): Map[String, Delta]
  protected def player(e: E): String
  /** The event's fact time, which its consumer's progress is told; none for an event that has none. */
  protected def factAt(e: E): Option[Instant]
  /** The flag the event itself carries, when it carries one. */
  protected def testFlag(e: E): Option[Boolean] = None

  def onMessage(e: E): Effect =
    val subject = messageContext.subject
    val now     = clock.instant()
    if !Generation.belongs(subject, settings.generation) then
      FoldProgress.observed(client, settings, consumer, None, now)
      effects.ignore()
    else
      val ds = deltas(e, settings.reportingZone)
      if ds.nonEmpty then
        val test = testFlag(e).getOrElse(
          client.forEventSourcedEntity(EntityId(PlayerLog.id(settings.generation, player(e)))).call(PlayerLog.testAccount).invoke())
        val seq = messageContext.sequenceNumber
        ds.toSeq.sortBy(_._1).foreach { (day, d) =>
          client.forEventSourcedEntity(EntityId(BrandDay.id(settings.generation, day))).call(BrandDay.fold)
            .invoke(Fold(s"$source/$subject", seq, d.copy(test = test || d.test), now)): Unit
        }
      FoldProgress.observed(client, settings, consumer, factAt(e), now)
      if ds.isEmpty then effects.ignore() else effects.done()

final class WalletDays(client: ComponentClient, settings: Settings, clock: Clock)
    extends DayFolder[BookEvent](client, settings, clock, WalletBook.componentId.toString, ToDaysConsumers.WalletsName):
  protected def deltas(e: BookEvent, zone: ZoneId) = ToDays.wallet(e, zone)
  protected def factAt(e: BookEvent) = e match
    case m: Moved => Some(m.at)
    case _        => None
  protected def player(e: BookEvent) = e match
    case m: Moved => m.player
    case _        => ""

final class PaymentDays(client: ComponentClient, settings: Settings, clock: Clock)
    extends DayFolder[PaymentEvent](client, settings, clock, Payment.componentId.toString, ToDaysConsumers.PaymentsName):
  protected def deltas(e: PaymentEvent, zone: ZoneId) = ToDays.payment(e, zone)
  protected def factAt(e: PaymentEvent) = Some(e.at)
  protected def player(e: PaymentEvent) = e.player

final class WithdrawalDays(client: ComponentClient, settings: Settings, clock: Clock)
    extends DayFolder[WithdrawalEvent](client, settings, clock, Withdrawal.componentId.toString, ToDaysConsumers.WithdrawalsName):
  protected def deltas(e: WithdrawalEvent, zone: ZoneId) = ToDays.withdrawal(e, zone)
  protected def factAt(e: WithdrawalEvent) = Some(e.at)
  protected def player(e: WithdrawalEvent) = e.player

final class GrantDays(client: ComponentClient, settings: Settings, clock: Clock)
    extends DayFolder[GrantEvent](client, settings, clock, Grant.componentId.toString, ToDaysConsumers.GrantsName):
  protected def deltas(e: GrantEvent, zone: ZoneId) = ToDays.grant(e, zone)
  protected def factAt(e: GrantEvent) = Some(e.at)
  protected def player(e: GrantEvent) = e.player

final class PlayerDaysFolder(client: ComponentClient, settings: Settings, clock: Clock)
    extends DayFolder[PlayerLogEvent](client, settings, clock, PlayerLog.componentId.toString, ToDaysConsumers.PlayersName):
  protected def deltas(e: PlayerLogEvent, zone: ZoneId) = ToDays.player(e, zone)
  protected def factAt(e: PlayerLogEvent) = Some(e.at)
  protected def player(e: PlayerLogEvent) = e.player
  override protected def testFlag(e: PlayerLogEvent) = e match
    case r: PlayerLogEvent.Registered => Some(r.testAccount)
    case _                            => None

/** A market's events: about no one player, so no test account's. */
final class MarketDays(client: ComponentClient, settings: Settings, clock: Clock)
    extends DayFolder[MarketBookEvent](client, settings, clock, MarketBook.componentId.toString, ToDaysConsumers.MarketsName):
  protected def deltas(e: MarketBookEvent, zone: ZoneId) = ToDays.market(e, zone)
  protected def factAt(e: MarketBookEvent) = Some(e.at)
  protected def player(e: MarketBookEvent) = ""
  override protected def testFlag(e: MarketBookEvent) = Some(false)

/** The `to-days` companions, each `to-days-<source>-g<n>`; the markets' only when the brand runs `predictions`. */
object ToDaysConsumers:
  val WalletsName     = "to-days-wallets"
  val PaymentsName    = "to-days-payments"
  val WithdrawalsName = "to-days-withdrawals"
  val GrantsName      = "to-days-grants"
  val PlayersName     = "to-days-players"
  val MarketsName     = "to-days-markets"

  /** The consumers the settings register, by name (without the generation): every one a due day waits for (R7). */
  def names(settings: Settings): Seq[String] =
    Seq(WalletsName, PaymentsName, WithdrawalsName, GrantsName, PlayersName) ++ Option.when(settings.predictions)(MarketsName)

  final class Wallets(settings: Settings, clock: Clock = Clock.systemUTC())
      extends Consumer.Companion[WalletDays, BookEvent, Nothing](
        Generation.componentId(WalletsName, settings.generation), ChangeSource.eventsOf(WalletBook)):
    def create(ctx: ConsumerContext) = new WalletDays(ctx.componentClient, settings, clock)

  final class Payments(settings: Settings, clock: Clock = Clock.systemUTC())
      extends Consumer.Companion[PaymentDays, PaymentEvent, Nothing](
        Generation.componentId(PaymentsName, settings.generation), ChangeSource.eventsOf(Payment)):
    def create(ctx: ConsumerContext) = new PaymentDays(ctx.componentClient, settings, clock)

  final class Withdrawals(settings: Settings, clock: Clock = Clock.systemUTC())
      extends Consumer.Companion[WithdrawalDays, WithdrawalEvent, Nothing](
        Generation.componentId(WithdrawalsName, settings.generation), ChangeSource.eventsOf(Withdrawal)):
    def create(ctx: ConsumerContext) = new WithdrawalDays(ctx.componentClient, settings, clock)

  final class Grants(settings: Settings, clock: Clock = Clock.systemUTC())
      extends Consumer.Companion[GrantDays, GrantEvent, Nothing](
        Generation.componentId(GrantsName, settings.generation), ChangeSource.eventsOf(Grant)):
    def create(ctx: ConsumerContext) = new GrantDays(ctx.componentClient, settings, clock)

  final class Players(settings: Settings, clock: Clock = Clock.systemUTC())
      extends Consumer.Companion[PlayerDaysFolder, PlayerLogEvent, Nothing](
        Generation.componentId(PlayersName, settings.generation), ChangeSource.eventsOf(PlayerLog)):
    def create(ctx: ConsumerContext) = new PlayerDaysFolder(ctx.componentClient, settings, clock)

  final class Markets(settings: Settings, clock: Clock = Clock.systemUTC())
      extends Consumer.Companion[MarketDays, MarketBookEvent, Nothing](
        Generation.componentId(MarketsName, settings.generation), ChangeSource.eventsOf(MarketBook)):
    def create(ctx: ConsumerContext) = new MarketDays(ctx.componentClient, settings, clock)

  def all(settings: Settings, clock: Clock = Clock.systemUTC()): Seq[com.thinkmorestupidless.ankka.core.ComponentDescriptor] = Seq(
    Wallets(settings, clock).descriptor,
    Payments(settings, clock).descriptor,
    Withdrawals(settings, clock).descriptor,
    Grants(settings, clock).descriptor,
    Players(settings, clock).descriptor
  ) ++ Option.when(settings.predictions)(Markets(settings, clock).descriptor)
