package io.eitheror.casino.reporting.predictions

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.{ReportingCodecs, ReportingRuntime}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.intake.Generation
import io.eitheror.casino.reporting.reads.Paging

import java.time.{Instant, LocalDate}

/**
 * A player's day in one market (data-model.md `Traders`, keyed `g<n>/<player>/<market>/<day>`, the day so a period's
 * rows are found): contracts traded, notional, taker fees paid, positions paid, and the net result, what the trades
 * and payments moved to and from the player's real balance (FR-030). `lastSeq` is the book's last event folded;
 * `firstTraded` the day's first trade's time, so the dashboard counts yesterday's traders to the same hour (FR-028).
 */
final case class TraderRow(
    generation: Int,
    player: String = "",
    market: String = "",
    currency: String = "",
    day: String = "",
    contracts: Long = 0,
    notional: Long = 0,
    fees: Long = 0,
    positionsPaid: Long = 0,
    net: Long = 0,
    lastSeq: Long = 0,
    firstTraded: Option[Instant] = None
)

object TraderRow:
  given Serializer[TraderRow] = ReportingCodecs.row[TraderRow]("trader-row")

final class TradersView extends KeyedView[TraderRow]:
  /** Each player a book's event moves, on the event's day in the brand's zone. */
  def onBook(event: MarketBookEvent, change: Change): Effect =
    val subject = change.subject
    Generation.of(subject).fold(effects.ignore()) { g =>
      val seq = change.sequenceNumber
      val day = Days.dayOf(event.at, ReportingRuntime.settings.get().reportingZone)
      val moves: Vector[(String, TraderRow => TraderRow)] = event match
        case t: MarketBookEvent.Traded =>
          t.legs.map(l => l.player -> ((r: TraderRow) => r.copy(contracts = r.contracts + l.quantity, notional = r.notional + l.notional,
            fees = r.fees + l.fee, net = r.net + l.net,
            firstTraded = Some(r.firstTraded.fold(t.at)(f => if t.at.isBefore(f) then t.at else f)))))
        case p: MarketBookEvent.Paid =>
          Vector(p.player -> ((r: TraderRow) => r.copy(positionsPaid = r.positionsPaid + p.paid, net = r.net + p.paid)))
        case _: MarketBookEvent.Kept => Vector.empty
      // Two legs of one trade may be the same owner's; they fold into one row.
      val rows = moves.groupMap(_._1)(_._2).toVector.sortBy(_._1).flatMap { (player, fs) =>
        val key    = s"${Generation.id(g, s"$player/${event.market}/$day")}"
        val before = change.rows.get(key)
        Option.unless(before.exists(_.lastSeq >= seq)) {
          val row = before.getOrElse(TraderRow(g, player, event.market, event.currency, day))
          key -> fs.foldLeft(row)((r, f) => f(r)).copy(lastSeq = seq)
        }
      }
      if rows.isEmpty then effects.ignore() else effects.updateRows(rows)
    }

object Traders extends KeyedView.Companion[TradersView, TraderRow](
      ComponentId("traders"),
      TraderRow.given_Serializer_TraderRow
    ):
  val books = source(ChangeSource.eventsOf(MarketBook))(_.onBook)

  /** The rows in `currency` from `from` to `to`, ISO days and inclusive. */
  val between = query("between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'currency' = :currency AND payload::jsonb->>'day' >= :from AND payload::jsonb->>'day' <= :to ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new TradersView

  def rows(views: ViewClient, generation: Int, currency: String, from: LocalDate, to: LocalDate): Vector[TraderRow] =
    Paging.bounded(views.forView(Traders), between, "generation" -> generation.toString, "currency" -> currency,
      "from" -> from.toString, "to" -> to.toString)
