package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.intake.Generation

import java.time.Instant

/**
 * A day's money in one currency (FR-005, FR-007): stakes, wins and rollbacks (GGR is stakes less rollbacks less
 * wins), the deposit pool's GGR on its rounds' days, bonus cost, chargebacks (lost disputes, counted by number too),
 * deposits confirmed and withdrawals completed. NGR is before the programme's fees.
 */
final case class Money(
    stakes: Long = 0,
    wins: Long = 0,
    rollbacks: Long = 0,
    poolGgr: Long = 0,
    bonusCost: Long = 0,
    chargebacks: Long = 0,
    chargebackCount: Long = 0,
    deposits: Long = 0,
    depositCount: Long = 0,
    withdrawals: Long = 0,
    withdrawalCount: Long = 0
):
  def ggr: Long = Figures.ggr(stakes, wins, rollbacks)
  def ngr: Long = Figures.ngr(ggr, bonusCost, chargebacks)

  private def zip(o: Money)(op: (Long, Long) => Long): Money =
    Money(op(stakes, o.stakes), op(wins, o.wins), op(rollbacks, o.rollbacks), op(poolGgr, o.poolGgr), op(bonusCost, o.bonusCost),
      op(chargebacks, o.chargebacks), op(chargebackCount, o.chargebackCount), op(deposits, o.deposits),
      op(depositCount, o.depositCount), op(withdrawals, o.withdrawals), op(withdrawalCount, o.withdrawalCount))

  def +(o: Money): Money = zip(o)(_ + _)
  def -(o: Money): Money = zip(o)(_ - _)

/** A payment provider's day: deposit attempts (initiated), completed (captured) and their value (FR-011). */
final case class PaymentFigures(attempts: Long = 0, completed: Long = 0, value: Long = 0):
  def +(o: PaymentFigures): PaymentFigures = PaymentFigures(attempts + o.attempts, completed + o.completed, value + o.value)
  def -(o: PaymentFigures): PaymentFigures = PaymentFigures(attempts - o.attempts, completed - o.completed, value - o.value)

/**
 * A withdrawal gateway's day: requests, completions and the seconds from request to completion, summed over the
 * `timed` completions whose request was seen, which the average divides by (FR-011); and the completions' amount, which
 * the day-end lists per gateway (FR-018).
 */
final case class GatewayFigures(requests: Long = 0, completed: Long = 0, completionSeconds: Long = 0, timed: Long = 0,
    amount: Long = 0):
  def +(o: GatewayFigures): GatewayFigures =
    GatewayFigures(requests + o.requests, completed + o.completed, completionSeconds + o.completionSeconds, timed + o.timed,
      amount + o.amount)
  def -(o: GatewayFigures): GatewayFigures =
    GatewayFigures(requests - o.requests, completed - o.completed, completionSeconds - o.completionSeconds, timed - o.timed,
      amount - o.amount)

/** An amount and a count, as a deposit or a withdrawal hour of a day holds them. */
final case class Flow(amount: Long = 0, count: Long = 0):
  def +(o: Flow): Flow = Flow(amount + o.amount, count + o.count)
  def -(o: Flow): Flow = Flow(amount - o.amount, count - o.count)

/**
 * One money section and its breakdowns: the money; GGR, NGR, deposits and withdrawals per hour of the day in the zone,
 * so the dashboard's yesterday is yesterday to the same hour (contracts/routes.md `kpis`); and the providers.
 */
final case class Section(
    money: Money = Money(),
    hourGgr: Vector[Long] = Section.NoHours,
    hourNgr: Vector[Long] = Section.NoHours,
    payments: Map[String, PaymentFigures] = Map.empty,
    gateways: Map[String, GatewayFigures] = Map.empty,
    hourDeposits: Vector[Flow] = Section.NoFlows,
    hourWithdrawals: Vector[Flow] = Section.NoFlows
):
  def +(d: Delta): Section =
    val h = d.hour.max(0).min(23)
    Section(money + d.money, hourGgr.updated(h, hourGgr(h) + d.money.ggr), hourNgr.updated(h, hourNgr(h) + d.money.ngr),
      Section.merge(payments, d.payments, PaymentFigures())(_ + _), Section.merge(gateways, d.gateways, GatewayFigures())(_ + _),
      hourDeposits.updated(h, hourDeposits(h) + Flow(d.money.deposits, d.money.depositCount)),
      hourWithdrawals.updated(h, hourWithdrawals(h) + Flow(d.money.withdrawals, d.money.withdrawalCount)))

  def -(o: Section): Section =
    Section(money - o.money, hourGgr.lazyZip(o.hourGgr).map(_ - _), hourNgr.lazyZip(o.hourNgr).map(_ - _),
      Section.merge(payments, o.payments, PaymentFigures())(_ - _), Section.merge(gateways, o.gateways, GatewayFigures())(_ - _),
      hourDeposits.lazyZip(o.hourDeposits).map(_ - _), hourWithdrawals.lazyZip(o.hourWithdrawals).map(_ - _))

object Section:
  val NoHours: Vector[Long] = Vector.fill(24)(0L)
  val NoFlows: Vector[Flow] = Vector.fill(24)(Flow())
  val NoMarketHours: Vector[MarketDay] = Vector.fill(24)(MarketDay())

  /** Each key of `b` combined into `a`'s, a key `a` lacks combined from its zero. */
  def merge[A](a: Map[String, A], b: Map[String, A], zero: A)(op: (A, A) => A): Map[String, A] =
    b.foldLeft(a) { case (acc, (k, v)) => acc.updated(k, op(acc.getOrElse(k, zero), v)) }

/** A game's day from `RoundCheck`: the provider's stake and win and the wallet's (FR-018), keyed `<provider>/<game>`. */
final case class GameFigures(provider: String, game: String, gameName: Option[String] = None, providerStake: Long = 0,
    providerWin: Long = 0, walletStake: Long = 0, walletWin: Long = 0)

/** A market's day (FR-031, US8): contracts, notional, trades, fees, resolution payments, remainder, fee account credits. */
final case class MarketDay(contracts: Long = 0, notional: Long = 0, trades: Long = 0, fees: Long = 0, payments: Long = 0,
    remainder: Long = 0, feeCredits: Long = 0):
  def +(o: MarketDay): MarketDay = MarketDay(contracts + o.contracts, notional + o.notional, trades + o.trades, fees + o.fees,
    payments + o.payments, remainder + o.remainder, feeCredits + o.feeCredits)

/**
 * A trade whose taker fee and the fee account's credits under it differ, or that one side lacks (FR-031): `status`
 * `differs`, `fee_only` (no credit) or `credit_only` (no `trade_made`, so its market is not known).
 */
final case class FeeDifference(trade: String, market: String, currency: String, fee: Long, credit: Long, status: String)

/**
 * A day in one currency (data-model.md *The day*): `total` over every player, `test` the same sections over test
 * accounts only, subtracted at read when the brand excludes them (FR-024); the games, the markets, and the liability
 * and pending holds at close, which the close is given.
 */
final case class CurrencyDay(
    total: Section = Section(),
    test: Section = Section(),
    games: Map[String, GameFigures] = Map.empty,
    predictions: Map[String, MarketDay] = Map.empty,
    liabilityAtClose: Long = 0,
    pendingHoldsAtClose: Long = 0,
    hourPredictions: Vector[MarketDay] = Section.NoMarketHours
):
  /** The sections a read shows: every player's, or without the test accounts'. */
  def section(excludeTestAccounts: Boolean): Section = if excludeTestAccounts then total - test else total

  /** Every market's figures to the end of `hour` of the day, so the dashboard's yesterday is to the same hour (FR-028). */
  def predictionsTo(hour: Int): MarketDay = hourPredictions.take(hour + 1).foldLeft(MarketDay())(_ + _)

/**
 * One source event's part of a day (one currency, one hour): the money, the providers, the registrations (counted
 * per day, not per currency: a registration's currency is optional, and the original counted every one), and whether
 * the player was a test account at the fold. An empty currency moves only the registrations.
 */
final case class Delta(
    currency: String = "",
    hour: Int = 0,
    money: Money = Money(),
    payments: Map[String, PaymentFigures] = Map.empty,
    gateways: Map[String, GatewayFigures] = Map.empty,
    registrations: Long = 0,
    test: Boolean = false,
    predictions: Map[String, MarketDay] = Map.empty
):
  /** Two deltas of one source event for one day, as one. */
  def +(o: Delta): Delta =
    copy(money = money + o.money, payments = Section.merge(payments, o.payments, PaymentFigures())(_ + _),
      gateways = Section.merge(gateways, o.gateways, GatewayFigures())(_ + _), registrations = registrations + o.registrations,
      currency = if currency.nonEmpty then currency else o.currency,
      predictions = Section.merge(predictions, o.predictions, MarketDay())(_ + _))

/** A late fact for a closed day: dated when it arrived, changing nothing fixed (FR-019). */
final case class Correction(source: String, arrivedAt: Instant, delta: Delta)

/** A round the close lists: its figures differ, or one side lacks it (FR-018). */
final case class Difference(player: String, currency: String, roundId: String, status: String, providerStake: Long = 0,
    providerWin: Long = 0, walletStake: Long = 0, walletWin: Long = 0, walletRollback: Long = 0, provider: String = "")

/** A correction to an earlier day that arrived during this one, so the day-end opens with it (FR-019). */
final case class OpeningCorrection(day: String, arrivedAt: Instant, delta: Delta)

/**
 * What the close reads from outside the day, from views over every wallet and round (research R7): the games and
 * the differences from `Rounds`, the liability from `Wallets`, the pending holds from `WithdrawalsView`, and the
 * corrections to earlier days that arrived during it; with the predictions module, the fee account's credits per
 * currency and market and the trades whose fee and credit differ (`TradeFees`). Each defaults to none, so a day closes
 * without them.
 */
final case class CloseInputs(
    games: Map[String, Map[String, GameFigures]] = Map.empty,
    differences: Vector[Difference] = Vector.empty,
    liability: Map[String, Long] = Map.empty,
    pendingHolds: Map[String, Long] = Map.empty,
    openingCorrections: Vector[OpeningCorrection] = Vector.empty,
    feeCredits: Map[String, Map[String, Long]] = Map.empty,
    feeDifferences: Vector[FeeDifference] = Vector.empty
)

/**
 * A reporting day (data-model.md `BrandDay`, id `g<n>/<day>`): open until `Close`, then closed once and for ever.
 * `lastSeq` is the last event folded per source entity, so a redelivered event folds nothing.
 */
final case class DayState(
    closed: Boolean = false,
    zone: String = "",
    closedAt: Option[Instant] = None,
    registrations: Long = 0,
    testRegistrations: Long = 0,
    currencies: Map[String, CurrencyDay] = Map.empty,
    differences: Vector[Difference] = Vector.empty,
    openingCorrections: Vector[OpeningCorrection] = Vector.empty,
    corrections: Vector[Correction] = Vector.empty,
    lastSeq: Map[String, Long] = Map.empty,
    hourRegistrations: Vector[Long] = Section.NoHours,
    hourTestRegistrations: Vector[Long] = Section.NoHours,
    feeDifferences: Vector[FeeDifference] = Vector.empty
):
  def folded(source: String, seq: Long): Boolean = lastSeq.get(source).exists(_ >= seq)

sealed trait DayEvent

object DayEvent:
  final case class Folded(source: String, seq: Long, delta: Delta) extends DayEvent
  final case class Corrected(source: String, seq: Long, arrivedAt: Instant, delta: Delta) extends DayEvent
  /** The day-end, fixed: every section per currency as it stood at the close, in the zone it closed in (FR-018). */
  final case class DayFixed(zone: String, closedAt: Instant, sections: Map[String, CurrencyDay], differences: Vector[Difference],
      openingCorrections: Vector[OpeningCorrection], registrations: Long = 0, testRegistrations: Long = 0,
      feeDifferences: Vector[FeeDifference] = Vector.empty) extends DayEvent

/** `source` is the source entity's component and id; `seq` its event's sequence; `arrivedAt` when the fold was asked. */
final case class Fold(source: String, seq: Long, delta: Delta, arrivedAt: Instant)

final case class Close(at: Instant, zone: String, inputs: CloseInputs = CloseInputs())

object Day:
  import DayEvent.*

  def fold(s: DayState, c: Fold): Option[DayEvent] =
    if s.folded(c.source, c.seq) then None
    else if s.closed then Some(Corrected(c.source, c.seq, c.arrivedAt, c.delta))
    else Some(Folded(c.source, c.seq, c.delta))

  def close(s: DayState, c: Close): Option[DayEvent] =
    Option.unless(s.closed) {
      val currencies = (s.currencies.keySet ++ c.inputs.games.keySet ++ c.inputs.liability.keySet ++ c.inputs.pendingHolds.keySet ++
        c.inputs.feeCredits.keySet)
      val sections = currencies.toSeq.map { cur =>
        val d = s.currencies.getOrElse(cur, CurrencyDay())
        val credits = c.inputs.feeCredits.getOrElse(cur, Map.empty).map((market, credit) => market -> MarketDay(feeCredits = credit))
        cur -> d.copy(games = c.inputs.games.getOrElse(cur, d.games), liabilityAtClose = c.inputs.liability.getOrElse(cur, 0L),
          pendingHoldsAtClose = c.inputs.pendingHolds.getOrElse(cur, 0L),
          predictions = Section.merge(d.predictions.map((m, f) => m -> f.copy(feeCredits = 0)), credits, MarketDay())(_ + _))
      }.toMap
      DayFixed(c.zone, c.at, sections, c.inputs.differences, c.inputs.openingCorrections, s.registrations, s.testRegistrations,
        c.inputs.feeDifferences)
    }

  private def add(s: DayState, d: Delta): DayState =
    val h    = d.hour.max(0).min(23)
    val test = if d.test then d.registrations else 0L
    val regs = s.copy(registrations = s.registrations + d.registrations, testRegistrations = s.testRegistrations + test,
      hourRegistrations = s.hourRegistrations.updated(h, s.hourRegistrations(h) + d.registrations),
      hourTestRegistrations = s.hourTestRegistrations.updated(h, s.hourTestRegistrations(h) + test))
    if d.currency.isEmpty then regs
    else
      val day = regs.currencies.getOrElse(d.currency, CurrencyDay())
      val markets = d.predictions.values.foldLeft(MarketDay())(_ + _)
      regs.copy(currencies = regs.currencies.updated(d.currency,
        day.copy(total = day.total + d, test = if d.test then day.test + d else day.test,
          predictions = Section.merge(day.predictions, d.predictions, MarketDay())(_ + _),
          hourPredictions = if d.predictions.isEmpty then day.hourPredictions
            else day.hourPredictions.updated(h, day.hourPredictions(h) + markets))))

  def apply(s: DayState, e: DayEvent): DayState = e match
    case f: Folded    => add(s, f.delta).copy(lastSeq = s.lastSeq.updated(f.source, f.seq))
    case c: Corrected =>
      s.copy(corrections = s.corrections :+ Correction(c.source, c.arrivedAt, c.delta), lastSeq = s.lastSeq.updated(c.source, c.seq))
    case x: DayFixed =>
      s.copy(closed = true, zone = x.zone, closedAt = Some(x.closedAt), currencies = x.sections, differences = x.differences,
        openingCorrections = x.openingCorrections, registrations = x.registrations, testRegistrations = x.testRegistrations,
        feeDifferences = x.feeDifferences)

final class BrandDay(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[DayState, DayEvent]:
  def emptyState: DayState = DayState()

  def applyEvent(event: DayEvent): DayState = Day.apply(currentState, event)

  /** Whether the delta was folded, on the open day or as a correction to the closed one. */
  def fold(c: Fold): Effect[Boolean] =
    Day.fold(currentState, c).fold(effects.reply(false))(e => effects.persist(e).thenReply(_ => true))

  def close(c: Close): Effect[DayState] =
    Day.close(currentState, c).fold(effects.reply(currentState))(e => effects.persist(e).thenReplyState)

  def read: ReadOnlyEffect[DayState] = effects.reply(currentState)

object BrandDay extends EventSourcedEntity.Companion[BrandDay, DayState, DayEvent](
      componentId = ComponentId("brand-day"),
      stateSerializer = ReportingCodecs.serializer[DayState]("brand-day"),
      eventSerializer = ReportingCodecs.serializer[DayEvent]("brand-day-event")
    ):
  given Serializer[Fold]  = ReportingCodecs.serializer[Fold]("brand-day-fold")
  given Serializer[Close] = ReportingCodecs.serializer[Close]("brand-day-close")
  import ReportingCodecs.given_Serializer_Boolean

  override def snapshotEvery: Option[Int] = Some(100)

  def id(generation: Int, day: String): String = Generation.id(generation, day)

  def create(context: EventSourcedEntityContext) = new BrandDay(context)

  val fold  = command("fold")(_.fold)
  val close = command("close")(_.close)
  val read  = query("read")(_.read)
