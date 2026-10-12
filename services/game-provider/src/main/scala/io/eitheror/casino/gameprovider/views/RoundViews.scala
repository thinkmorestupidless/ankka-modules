package io.eitheror.casino.gameprovider.views

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.gameprovider.rounds.{Round, RoundEvent, UnacknowledgedEntry}
import io.eitheror.casino.gameprovider.rounds.RoundEvent.*

import java.time.Instant

/** One round as the operator's listings show it (data-model.md, RoundsByPlayer and RoundsBySession). */
final case class RoundRow(
    provider: String,
    roundId: String,
    playerId: String,
    sessionId: String,
    gameCode: String,
    currency: String,
    totalStake: Long,
    win: Long,
    outcome: String,
    status: String,
    openedAt: Option[Instant],
    settledAt: Option[Instant]
)

object RoundRow:
  given Serializer[RoundRow] = Codecs.serializer[RoundRow]("round-row")

  /** The row after one event, from the row before (none before the round opened). */
  def next(row: Option[RoundRow], event: RoundEvent): Option[RoundRow] = (row, event) match
    case (_, RoundOpened(o)) =>
      Some(RoundRow(o.provider, o.roundId, o.playerId, o.sessionId.getOrElse(""), o.gameCode.getOrElse(""), o.currency, 0, 0, "",
        "open", Some(o.at), None))
    case (Some(r), DebitRecorded(tx, _)) if tx.answer.status == "ok"  => Some(r.copy(totalStake = r.totalStake + tx.amount))
    case (Some(r), BuyinRecorded(tx, _)) if tx.answer.status == "ok"  => Some(r.copy(totalStake = r.totalStake + tx.amount))
    case (Some(r), CreditRecorded(tx, _))                            => Some(r.copy(win = r.win + tx.amount))
    case (Some(r), PayoutRecorded(tx))                               => Some(r.copy(win = r.win + tx.amount))
    case (Some(r), CancelRecorded(_, _, stake, win))                 => Some(r.copy(totalStake = r.totalStake - stake, win = r.win - win))
    case (Some(r), RoundEvent.RoundSettled(s))                       => Some(r.copy(outcome = s.outcome, status = "settled", settledAt = Some(s.at)))
    case (Some(r), RoundEvent.RoundCancelled(s))                     => Some(r.copy(outcome = s.outcome, status = "cancelled", settledAt = Some(s.at)))
    case (other, _)                                                  => other

/** The rounds of a player, for the operator and for spec 015's inspection. */
final class RoundsByPlayerView extends View[RoundEvent, RoundRow]:
  def onChange(event: RoundEvent): Effect = RoundRow.next(rowState, event).fold(effects.ignore())(effects.updateRow)

object RoundsByPlayer extends View.Companion[RoundsByPlayerView, RoundEvent, RoundRow](
      componentId = ComponentId("rounds-by-player"),
      source = ChangeSource.eventsOf(Round),
      rowSerializer = RoundRow.given_Serializer_RoundRow
    ):
  def create(ctx: ViewComponentContext) = new RoundsByPlayerView

/** The rounds of a game session. The same rows as by player, queried by session (data-model.md). */
final class RoundsBySessionView extends View[RoundEvent, RoundRow]:
  def onChange(event: RoundEvent): Effect = RoundRow.next(rowState, event).fold(effects.ignore())(effects.updateRow)

object RoundsBySession extends View.Companion[RoundsBySessionView, RoundEvent, RoundRow](
      componentId = ComponentId("rounds-by-session"),
      source = ChangeSource.eventsOf(Round),
      rowSerializer = RoundRow.given_Serializer_RoundRow
    ):
  def create(ctx: ViewComponentContext) = new RoundsBySessionView

/** A round's open unacknowledged callbacks (FR-020): one row per round, listed while any is open. */
final case class UnacknowledgedRow(provider: String, roundId: String, playerId: String, currency: String,
    entries: Vector[UnacknowledgedEntry], openCount: Int)

final class UnacknowledgedView extends View[RoundEvent, UnacknowledgedRow]:
  def onChange(event: RoundEvent): Effect =
    val row = rowState.getOrElse(UnacknowledgedRow("", "", "", "", Vector.empty, 0))
    event match
      case RoundOpened(o) => effects.updateRow(row.copy(provider = o.provider, roundId = o.roundId, playerId = o.playerId, currency = o.currency))
      case CallbackUnacknowledged(entry) =>
        val entries = row.entries.filterNot(e => e.transactionId == entry.transactionId) :+ entry
        effects.updateRow(row.copy(entries = entries, openCount = entries.count(_.open)))
      case UnacknowledgedCleared(id, cleared) =>
        val entries = row.entries.map(e => if e.transactionId == id && e.open then e.copy(cleared = Some(cleared)) else e)
        effects.updateRow(row.copy(entries = entries, openCount = entries.count(_.open)))
      case _ => effects.ignore()

object Unacknowledged extends View.Companion[UnacknowledgedView, RoundEvent, UnacknowledgedRow](
      componentId = ComponentId("unacknowledged"),
      source = ChangeSource.eventsOf(Round),
      rowSerializer = Codecs.serializer[UnacknowledgedRow]("unacknowledged-row")
    ):
  def create(ctx: ViewComponentContext) = new UnacknowledgedView
