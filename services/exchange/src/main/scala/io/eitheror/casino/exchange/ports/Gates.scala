package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.predictions.exchange.{ExchangeCommand, MarketId, OwnerId, Side}
import com.thinkmorestupidless.predictions.exchange.components.{GateAnswer, GatePort}
import io.eitheror.casino.exchange.Gate as Switch

/**
 * The gate port (contracts/ports.md): the account always, then the play gate and the bet gate as the
 * brand's `predictions-gates` name them, in that order and failing closed. A sell stakes nothing, so
 * the bet gate is asked for buys only, with the order's cost.
 */
final class Gates(neighbours: () => Neighbours, gates: () => Set[Switch], currency: MarketId => String) extends GatePort:
  def mayPlace(owner: OwnerId, market: MarketId, order: ExchangeCommand.Place): GateAnswer =
    val n = neighbours()
    val on = gates()
    val asked = LazyList(
      Some("account" -> (() => n.account(owner.value))),
      Option.when(on(Switch.PlayGate))("play gate" -> (() => n.playCheck(owner.value))),
      Option.when(on(Switch.MayBet) && order.side == Side.Buy)(
        "bet gate" -> (() => n.mayBet(owner.value, currency(market), order.quantity * order.price)))
    ).flatten
    asked.map((name, ask) => name -> ask()).collectFirst {
      case (name, Gate.Refused(word))  => GateAnswer.Refused(s"$name: $word")
      case (name, Gate.Unavailable(_)) => GateAnswer.Refused(s"$name: unavailable")
    }.getOrElse(GateAnswer.Allow)
