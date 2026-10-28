package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{MoneyTransactions, MoneyTransactionsFact}
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.facts.Lenient

/**
 * A wallet's balances as its last movement left them (FR-034), one row per player and currency, folded
 * from `money.transactions` (each fact carries the balances after it), so the operator's player page
 * calls no wallet.
 */
final case class WalletSummaryRow(playerId: String, currency: String, realBalanceAfter: Long, restrictedBalanceAfter: Long,
    lastMovementAt: Long)

final class WalletSummaryViewRows extends View[Option[MoneyTransactionsFact], WalletSummaryRow]:
  def onChange(fact: Option[MoneyTransactionsFact]): Effect = fact match
    case Some(f) if rowState.forall(_.lastMovementAt <= f.occurredAt.toEpochMilli) =>
      effects.updateRow(WalletSummaryRow(f.playerId, f.currency.code, f.realBalanceAfter.value, f.restrictedBalanceAfter.value,
        f.occurredAt.toEpochMilli))
    case _ => effects.ignore()

object WalletSummaryView extends View.Companion[WalletSummaryViewRows, Option[MoneyTransactionsFact], WalletSummaryRow](
      componentId = ComponentId("wallet-summary"),
      source = ChangeSource.fromTopic(MoneyTransactions.name, Lenient.of(MoneyTransactions), StartFrom.Earliest,
        TopicOptions(Some(MoneyTransactions.contract), None, false)),
      rowSerializer = PlayersCodecs.serializer[WalletSummaryRow]("wallet-summary-row")
    ):
  def create(ctx: ViewComponentContext) = new WalletSummaryViewRows

  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player ORDER BY payload::jsonb->>'currency' ASC")

  def ofPlayer(views: ViewClient, playerId: String): Vector[WalletSummaryRow] =
    views.forView(WalletSummaryView).ask(byPlayer, 100, "player" -> playerId)
