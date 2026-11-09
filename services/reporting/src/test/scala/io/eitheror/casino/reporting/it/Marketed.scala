package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.reporting.day.DaysView
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.predictions.TradeFees

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A scenario's prediction markets (US8): its markets' lives on `markets.events`, its trades and payments on
 * `exchange.trades`, and the takers' fees moved into the fee account on `money.transactions`, as spec 023 and spec 022
 * publish them, with what each figure must be kept beside the facts. Prices are minor units per share (1 to 99), a
 * share pays 100 on its outcome; the taker fee is spec 023 FR-015's, ⌈rate × contracts × price × (100 − price) /
 * 1,000,000⌉ at `rateBp`, charged to the buyer of a direct trade.
 */
final class Marketed(kit: Kit, val day: LocalDate, val currency: String = "EUR", val rateBp: Long = 100):
  val tag: String = UUID.randomUUID().toString.take(8)
  def market(n: Int) = s"mk-$tag-m$n"
  def player(n: Int) = s"mk-$tag-p$n"
  def at(d: LocalDate, time: String): Instant = FactSet(kit.zone).at(d, time)
  def at(time: String): Instant = at(day, time)

  private val cur = Currency.unsafe(currency)
  private def m(v: Long) = MinorUnits.unsafe(v)
  private var facts = Vector.empty[() => Unit]
  private var seq = 0L
  private def add(p: => Unit): Unit = facts :+= (() => p)

  // What each figure must be, from the facts above.
  var trades     = Vector.empty[f.TradeMade]
  var paid       = Vector.empty[f.PositionPaid]
  var kept       = Vector.empty[f.RemainderKept]
  var feeMoves   = Vector.empty[(String, Long)]

  def fee(quantity: Long, price: Long): Long =
    val n = rateBp * quantity * price * (100 - price)
    n / 1_000_000 + (if n % 1_000_000 == 0 then 0 else 1)

  // ── markets.events ──────────────────────────────────────────────────────────────────────────────

  private def event(e: f.MarketsEventsFact): Unit = add(kit.publish(f.MarketsEvents, e))

  def drafted(mk: String, t: Instant, category: String = "sports", question: String = "Will it rain?"): Unit =
    event(f.MarketDrafted(mk, "op-1", question, "", category, Vector("yes", "no"), cur, None, t.plusSeconds(86_400 * 30), "2027-01-01",
      "criteria", "authority", t))

  def opened(mk: String, t: Instant): Unit = event(f.MarketOpened(mk, t))
  def closed(mk: String, t: Instant): Unit = event(f.MarketClosed(mk, None, None, t))
  def resolved(mk: String, outcome: String, t: Instant): Unit =
    event(f.MarketResolved(mk, "op-1", outcome, Vector.empty, "authority", t.plusSeconds(3_600), t))
  def corrected(mk: String, outcome: String, t: Instant): Unit =
    event(f.MarketCorrected(mk, "op-1", outcome, Vector.empty, t.plusSeconds(3_600), t))
  def voided(mk: String, t: Instant): Unit = event(f.MarketVoided(mk, "op-1", "cancelled", Vector.empty, t.plusSeconds(3_600), t))
  def finalised(mk: String, t: Instant): Unit = event(f.MarketFinal(mk, t))

  /** A market drafted and opened at `t`. */
  def open(mk: String, t: Instant, category: String = "sports"): Unit =
    drafted(mk, t.minusSeconds(3_600), category)
    opened(mk, t)

  // ── exchange.trades and the fee account ─────────────────────────────────────────────────────────

  private def trade(t: f.TradeMade): f.TradeMade =
    trades :+= t
    add(kit.publish(f.ExchangeTrades, t))
    t

  /**
   * `buyer` buys `quantity` of `outcome` at `price` from `seller`, the buyer the taker charged the fee, which moves into
   * the fee account under the trade unless `credit` says otherwise (`None` for no movement).
   */
  def direct(mk: String, buyer: String, seller: String, quantity: Long, price: Long, t: Instant, outcome: String = "yes",
      credit: Long => Option[Long] = Some(_)): f.TradeMade =
    seq += 1
    val id = s"$mk-t$seq"
    val taker = fee(quantity, price)
    val made = trade(f.TradeMade(mk, id, seq, "direct", quantity, Vector(f.OutcomePrice(outcome, price)), Vector(
      f.TradeLeg(buyer, s"$id-b", outcome, "buy", m(quantity * price), m(0), m(0), 0, Option.when(taker > 0)(m(taker))),
      f.TradeLeg(seller, s"$id-s", outcome, "sell", m(0), m(quantity * price), m(0), 0, None)), cur, t))
    credit(taker).foreach(c => feeMove(buyer, id, c, t))
    made

  /** A split of `quantity` complete sets: `yes` buys YES at `price`, `no` NO at `100 − price`; makers both, no fee. */
  def split(mk: String, yes: String, no: String, quantity: Long, price: Long, t: Instant): f.TradeMade =
    seq += 1
    val id = s"$mk-t$seq"
    trade(f.TradeMade(mk, id, seq, "split", quantity, Vector(f.OutcomePrice("yes", price), f.OutcomePrice("no", 100 - price)), Vector(
      f.TradeLeg(yes, s"$id-y", "yes", "buy", m(quantity * price), m(0), m(0), 0, None),
      f.TradeLeg(no, s"$id-n", "no", "buy", m(quantity * (100 - price)), m(0), m(0), 0, None)), cur, t))

  /** The fee account's movement under `trade` (spec 022's `account_debit` of kind `fee`). */
  def feeMove(player: String, trade: String, amount: Long, t: Instant): Unit =
    feeMoves :+= (trade -> amount)
    add(kit.publish(f.MoneyTransactions, f.AccountDebit(player, cur, s"fee-$trade-${UUID.randomUUID().toString.take(6)}", "fee",
      "brand", "fee", trade, m(amount), SignedMinorUnits(0), m(0), m(0), t), Some(currency)))

  def positionPaid(mk: String, player: String, outcome: String, quantity: Long, amount: Long, t: Instant): Unit =
    val p = f.PositionPaid(mk, s"$mk-$player-$outcome", player, outcome, quantity, m(amount), cur, t)
    paid :+= p
    add(kit.publish(f.ExchangeTrades, p))

  def remainder(mk: String, amount: Long, t: Instant): Unit =
    val r = f.RemainderKept(mk, m(amount), cur, t)
    kept :+= r
    add(kit.publish(f.ExchangeTrades, r))

  def publish(): this.type =
    facts.foreach(_())
    facts = Vector.empty
    this

  // ── what the facts say ──────────────────────────────────────────────────────────────────────────

  def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  private def of(mk: Option[String]) = trades.filter(t => mk.forall(_ == t.marketId))

  private def notionalOf(t: f.TradeMade): Long =
    t.legs.map(_.outcomeId).distinct.map(o => t.quantity * t.prices.find(_.outcomeId == o).fold(0L)(_.price)).sum

  def contracts(mk: Option[String] = None): Long = of(mk).map(_.quantity).sum
  def notional(mk: Option[String] = None): Long = of(mk).map(notionalOf).sum

  /** The trades of `d` in the zone to the end of `toHour`, as the dashboard reads yesterday to the same hour. */
  def tradesOf(d: LocalDate, toHour: Int = 23): Vector[f.TradeMade] =
    trades.filter { t =>
      val z = t.occurredAt.atZone(kit.zone)
      z.toLocalDate == d && z.getHour <= toHour
    }

  /** The dashboard's prediction figures over `ts` (FR-028), by its field names, money in minor units. */
  def dashboard(ts: Vector[f.TradeMade]): Map[String, Long] = Map(
    "contractsTraded" -> ts.map(_.quantity).sum, "notionalVolume" -> ts.map(notionalOf).sum, "trades" -> ts.size.toLong,
    "traders" -> ts.flatMap(_.legs).map(_.playerId).distinct.size.toLong,
    "takerFees" -> ts.flatMap(_.legs).flatMap(_.feeCents).map(_.value).sum)

  /**
   * What `GET /dashboard/predictions` must answer on `today` at `hour`, as `field -> (value, yesterday, trend)`: today's
   * trades against yesterday's to the end of the same hour, money as 2-dp strings.
   */
  def dashboardWanted(today: LocalDate, hour: Int): Map[String, (String, String, String)] =
    val (t, y) = (dashboard(tradesOf(today)), dashboard(tradesOf(today.minusDays(1), hour)))
    Marketed.DashboardFields.map { k =>
      val shown = (v: Long) => if Marketed.DashboardMoney(k) then money(v) else v.toString
      k -> (shown(t(k)), shown(y(k)), Figures.trend(t(k), y(k)))
    }.toMap
  def fees(mk: Option[String] = None): Long = of(mk).flatMap(_.legs).flatMap(_.feeCents).map(_.value).sum
  def traders(mk: Option[String] = None): Long = of(mk).flatMap(_.legs).map(_.playerId).distinct.size.toLong
  def payments(mk: Option[String] = None): Long = paid.filter(p => mk.forall(_ == p.marketId)).map(_.paidCents.value).sum
  def remainders(mk: Option[String] = None): Long = kept.filter(r => mk.forall(_ == r.marketId)).map(_.amountCents.value).sum

  /** The shares held of a market now: bought less sold less paid, over owners and outcomes, the positive holdings. */
  def openInterest(mk: String): Long =
    val legs = of(Some(mk)).flatMap(t => t.legs.map(l => (l.playerId, l.outcomeId) -> (if l.side == "sell" then -t.quantity else t.quantity)))
    val pays = paid.filter(_.marketId == mk).map(p => (p.playerId, p.outcomeId) -> -p.quantity)
    (legs ++ pays).groupMapReduce(_._1)(_._2)(_ + _).values.filter(_ > 0).sum

  /** A player's net result: paid less charged less fees over their legs, and their positions paid. */
  def net(player: String): Long =
    trades.flatMap(_.legs).filter(_.playerId == player).map(l => l.paidCents.value - l.chargeCents.value - l.feeCents.fold(0L)(_.value)).sum +
      paid.filter(_.playerId == player).map(_.paidCents.value).sum

  def playerContracts(player: String): Long = trades.filter(_.legs.exists(_.playerId == player)).map(t =>
    t.quantity * t.legs.count(_.playerId == player)).sum
  def playerNotional(player: String): Long = trades.flatMap(t => t.legs.filter(_.playerId == player).map(l =>
    t.quantity * t.prices.find(_.outcomeId == l.outcomeId).fold(0L)(_.price))).sum
  def playerFees(player: String): Long = trades.flatMap(_.legs).filter(_.playerId == player).flatMap(_.feeCents).map(_.value).sum
  def playerPaid(player: String): Long = paid.filter(_.playerId == player).map(_.paidCents.value).sum

  // ── reading ─────────────────────────────────────────────────────────────────────────────────────

  private def g = kit.settings.generation

  /** Every trade, payment and remainder of `d` folded into its day, and its fee movements into the trades' fees. */
  def settled(d: LocalDate = day): this.type =
    val onDay = trades.filter(t => t.occurredAt.atZone(kit.zone).toLocalDate == d)
    kit.eventually(s"$d's markets folded", 60.seconds)(DaysView.row(kit.views, g, d).flatMap(_.currencies.get(currency))
      .filter(c => onDay.map(_.marketId).distinct.forall(mk => c.predictions.get(mk).exists(_.trades == onDay.count(_.marketId == mk)))))
    kit.eventually(s"$d's fee movements", 60.seconds)(Option.when {
      val rows = TradeFees.ofDay(kit.views, g, d.toString).map(r => r.trade -> r).toMap
      onDay.forall(t => rows.get(t.tradeId).exists(_.traded)) &&
        feeMoves.filter((t, _) => onDay.exists(_.tradeId == t)).groupMapReduce(_._1)(_._2)(_ + _).forall((t, c) => rows.get(t).exists(_.credits == c))
    }(()))
    this

  def get(route: String, as: Seq[(String, String)] = kit.asFinance): Reply = kit.get(route, as)

  def marketRow(j: JsonNode, mk: String): Option[JsonNode] = j.path("markets").elements.asScala.find(_.path("marketId").asText == mk)

object Marketed:
  def fields(n: JsonNode): Map[String, String] =
    n.properties.asScala.map(e => e.getKey -> (if e.getValue.isNull then "" else e.getValue.asText)).toMap

  val DashboardFields: Vector[String] = Vector("contractsTraded", "notionalVolume", "trades", "traders", "takerFees")
  val DashboardMoney: Set[String]     = Set("notionalVolume", "takerFees")

  /** `GET /dashboard/predictions`'s answer as `field -> (value, yesterday, trend)`. */
  def dashboardOf(j: JsonNode): Map[String, (String, String, String)] =
    DashboardFields.map(k => k -> (j.path(k).path("value").asText, j.path(k).path("yesterday").asText, j.path(k).path("trend").asText)).toMap
