package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.{LedgerAnswer, LedgerPort, Money}

/**
 * The ledger port (research R4): each call to the wallet route the table names, under the key it
 * names, in the market's currency. An explicit split or merge is not offered (FR-016). A settlement
 * or payout the wallet refuses is handed to `retry` before the refusal is answered (R5), since the
 * library's consumer logs it and moves on.
 */
final class Ledger(wallet: () => WalletClient, currency: MarketId => String, retry: (WalletCall, LedgerAnswer) => Unit)
    extends LedgerPort:
  def reserve(market: MarketId, order: OrderId, money: Money): LedgerAnswer =
    wallet().reserve(money.owner.value, currency(market), Ledger.order(market, order), money.amount)

  def release(market: MarketId, order: OrderId, ordinal: Int, money: Money): LedgerAnswer =
    if money.amount == 0 then LedgerAnswer.Done
    else wallet().release(money.owner.value, currency(market), Ledger.order(market, order), ordinal, money.amount)

  def settle(market: MarketId, trade: TradeId, through: Through, legs: Vector[Leg]): LedgerAnswer =
    val c = currency(market)
    val w = wallet()
    through match
      case Through.Direct =>
        (legs.find(_.side == Side.Buy), legs.find(_.side == Side.Sell)) match
          case (Some(buy), Some(sell)) =>
            kept(w, w.settlement(Ledger.trade(market, trade), c, buy.owner.value, Ledger.order(market, buy.order), sell.owner.value, buy.charge,
              buy.released))
          case _                       => LedgerAnswer.Refused("a direct trade has a buy and a sell")
      case Through.Split =>
        Ledger.each(legs.filter(_.side == Side.Buy))(l =>
          kept(w, w.split(market.value, Ledger.key(market, trade, l), c, l.owner.value, l.charge, Ledger.order(market, l.order), l.released)))
      case Through.Merge =>
        Ledger.each(legs.filter(_.side == Side.Sell))(l => kept(w, w.merge(market.value, Ledger.key(market, trade, l), c, l.owner.value, l.paid)))

  def charge(market: MarketId, key: SetKey, money: Money): LedgerAnswer = LedgerAnswer.Refused(Ledger.NotOffered)
  def pay(market: MarketId, key: SetKey, money: Money): LedgerAnswer    = LedgerAnswer.Refused(Ledger.NotOffered)

  def payout(market: MarketId, key: PositionKey, money: Money): LedgerAnswer =
    if money.amount == 0 then LedgerAnswer.Done
    else
      val w = wallet()
      kept(w, w.payment(market.value, key.value, currency(market), money.owner.value, money.amount))

  /** The call's answer; a refusal is handed to the retry first. */
  private def kept(w: WalletClient, call: WalletCall): LedgerAnswer =
    val answer = w.send(call)
    if answer != LedgerAnswer.Done then retry(call, answer)
    answer

object Ledger:
  val NotOffered = "not offered"

  /**
   * An order's and a trade's ids as the wallet knows them: under their market, since a book numbers
   * its own (every market has a `t-1`, and a player's order ids are theirs per market), while a
   * wallet keys a player's reservations and calls across every market.
   */
  def order(market: MarketId, order: OrderId): String = s"${market.value}/${order.value}"
  def trade(market: MarketId, trade: TradeId): String = s"${market.value}/${trade.value}"

  /** A leg's own key: the trade and the leg's owner, which never repeat in a trade (spec 005 FR-009). */
  def key(market: MarketId, trade: TradeId, leg: Leg): String = s"${Ledger.trade(market, trade)}/${leg.owner.value}"

  /**
   * Every leg's call, each sent whatever the others answered: the library passes a trade the port
   * refuses, so a leg not sent now would never be. Each refused leg is handed to its own retry (by
   * `kept`, under its own key), and the trade is answered with the first refusal.
   */
  def each(legs: Vector[Leg])(call: Leg => LedgerAnswer): LedgerAnswer =
    legs.map(call).find(_ != LedgerAnswer.Done).getOrElse(LedgerAnswer.Done)
