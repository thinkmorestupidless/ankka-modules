package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.day.ToDays
import io.eitheror.casino.reporting.figures.Figures
import io.eitheror.casino.reporting.intake.{BookEvent, Generation, WalletBook}

import java.time.{LocalDate, ZoneOffset}

/**
 * A player's day in one currency (data-model.md `PlayerDays`, key `g<n>/<player>/<currency>/<day>`): stakes, wins and
 * rollbacks on their own day and the deposit pool's GGR on its rounds' days, as `ToDays` folds the brand's day, so the
 * rankings' players sum to the day's figures. `lastSeq` is the wallet's last event folded, so a change handled again
 * folds nothing (research R20 item 4).
 */
final case class PlayerDayRow(
    generation: Int,
    player: String = "",
    currency: String = "",
    day: String = "",
    stakes: Long = 0,
    wins: Long = 0,
    rollbacks: Long = 0,
    poolGgr: Long = 0,
    lastSeq: Long = 0
):
  def ggr: Long     = Figures.ggr(stakes, wins, rollbacks)
  def wagered: Long = stakes - rollbacks

object PlayerDayRow:
  given Serializer[PlayerDayRow] = ReportingCodecs.row[PlayerDayRow]("player-day-row")

final class PlayerDaysView extends KeyedView[PlayerDayRow]:
  /**
   * The days a wallet's event moves, each row read and written whole. `ToDays.wallet` decides the days from the
   * event's own, so the zone it is given decides only the hour, which no row keeps.
   */
  def onWallet(event: BookEvent, change: Change): Effect =
    val subject = change.subject
    Generation.of(subject).fold(effects.ignore()) { g =>
      val seq = change.sequenceNumber
      val moved = ToDays.wallet(event, ZoneOffset.UTC).toVector.sortBy(_._1).filter { (_, d) =>
        // A deposit moves the brand's day, and no figure a player's day keeps.
        d.money.stakes != 0 || d.money.wins != 0 || d.money.rollbacks != 0 || d.money.poolGgr != 0
      }
      val rows = moved.flatMap { (day, delta) =>
        val key    = s"$subject/$day"
        val before = change.rows.get(key)
        Option.unless(before.exists(_.lastSeq >= seq)) {
          val wallet = Generation.rest(subject)
          val row    = before.getOrElse(PlayerDayRow(g, wallet.takeWhile(_ != '/'), delta.currency, day))
          val m      = delta.money
          key -> row.copy(stakes = row.stakes + m.stakes, wins = row.wins + m.wins, rollbacks = row.rollbacks + m.rollbacks,
            poolGgr = row.poolGgr + m.poolGgr, lastSeq = seq)
        }
      }
      if rows.isEmpty then effects.ignore() else effects.updateRows(rows)
    }

object PlayerDays extends KeyedView.Companion[PlayerDaysView, PlayerDayRow](
      ComponentId("player-days"),
      PlayerDayRow.given_Serializer_PlayerDayRow
    ):
  val wallets = source(ChangeSource.eventsOf(WalletBook))(_.onWallet)

  /** The player days in `currency` from `from` to `to`, ISO days and inclusive. */
  val between = query("between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'currency' = :currency AND payload::jsonb->>'day' >= :from AND payload::jsonb->>'day' <= :to")

  /**
   * The players with the most GGR over `between`'s days, summed in the database (review should fix 4: a month of a
   * brand's player days is too many to read for ten), each as one row of their sums (no day, no `lastSeq`), GGR
   * descending then player id in code-point order, leaving out the ids `out` lists (comma-separated, empty for none).
   * Each row's payload is parsed once (`OFFSET 0` keeps the planner from folding the parse back into every term): a
   * third of the time of parsing it per term, measured over 100,000 player days.
   */
  val topByGgr = query("top-by-ggr")(
    s"""SELECT jsonb_build_object('generation', (:generation)::int, 'player', p, 'currency', :currency, 'day', '', 'stakes', s, 'wins', w, 'rollbacks', r, 'poolGgr', 0, 'lastSeq', 0)::text AS payload FROM (SELECT j->>'player' AS p, sum((j->>'stakes')::bigint) AS s, sum((j->>'wins')::bigint) AS w, sum((j->>'rollbacks')::bigint) AS r FROM (SELECT payload::jsonb AS j FROM $table OFFSET 0) AS parsed WHERE j->>'generation' = :generation AND j->>'currency' = :currency AND j->>'day' >= :from AND j->>'day' <= :to AND NOT (j->>'player' = ANY(string_to_array(:out, ','))) GROUP BY 1) AS sums ORDER BY (s - r - w) DESC, p COLLATE "C"""")

  def create(ctx: ViewComponentContext) = new PlayerDaysView

  def rows(views: ViewClient, generation: Int, currency: String, from: LocalDate, to: LocalDate): Vector[PlayerDayRow] =
    Paging.bounded(views.forView(PlayerDays), between, "generation" -> generation.toString, "currency" -> currency,
      "from" -> from.toString, "to" -> to.toString)

  /** The `n` players with the most GGR from `from` to `to` in `currency`, `out` left out: `topByGgr`. */
  def top(views: ViewClient, generation: Int, currency: String, from: LocalDate, to: LocalDate, out: Set[String], n: Int): Vector[PlayerDayRow] =
    views.forView(PlayerDays).ask(topByGgr, n, "generation" -> generation.toString, "currency" -> currency, "from" -> from.toString,
      "to" -> to.toString, "out" -> out.toVector.sorted.mkString(","))
