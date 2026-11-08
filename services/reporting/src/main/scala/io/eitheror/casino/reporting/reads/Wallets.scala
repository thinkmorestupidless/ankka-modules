package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Book, BookEvent, Generation, Moved, OpenRecord, RecordsChanged, WalletBook}

/**
 * A wallet as the player report and the bonus summary read it (data-model.md `Wallets`): the lifetimes (FR-008), the
 * restricted balance and the open restricted funds records with their balance and progress (FR-015). `openRecords`
 * says whether any is open, so a query finds the wallets with one without reading every row.
 */
final case class WalletRow(
    generation: Int,
    player: String = "",
    currency: String = "",
    deposits: Long = 0,
    wagered: Long = 0,
    won: Long = 0,
    restricted: Long = 0,
    records: Map[String, OpenRecord] = Map.empty,
    openRecords: Boolean = false
):
  /** The wallet's GGR over every transaction: wagered (stakes less rollbacks) less won. */
  def ggr: Long = wagered - won

object WalletRow:
  given Serializer[WalletRow] = ReportingCodecs.row[WalletRow]("wallet-row")

  /** Each `WalletBook` event applied as `Book.apply` applies it to the entity's lifetimes and records. */
  def next(row: WalletRow, e: BookEvent): WalletRow = e match
    case m: Moved =>
      val (d, w, n) = Book.lifetimes(m)
      row.copy(player = m.player, currency = m.currency, deposits = row.deposits + d, wagered = row.wagered + w, won = row.won + n,
        restricted = m.restrictedAfter)
    case r: RecordsChanged =>
      val open = Book.records(row.records, r.records)
      row.copy(records = open, openRecords = open.nonEmpty)

final class WalletRows extends View[BookEvent, WalletRow]:
  def onChange(event: BookEvent): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore()) { g =>
      effects.updateRow(WalletRow.next(rowState.getOrElse(WalletRow(g)), event))
    }

object Wallets extends View.Companion[WalletRows, BookEvent, WalletRow](
      componentId = ComponentId("wallets"),
      source = ChangeSource.eventsOf(WalletBook),
      rowSerializer = WalletRow.given_Serializer_WalletRow
    ):
  /** The wallets of the players `ids` names, comma separated. */
  val byPlayers = query("by-players")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'player' = ANY(string_to_array(:ids, ',')) ORDER BY row_key")

  /** The wallets in `currency` holding an open restricted funds record. */
  val withOpenRecords = query("with-open-records")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'currency' = :currency AND payload::jsonb->>'openRecords' = 'true' ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new WalletRows

  def ofPlayers(views: ViewClient, generation: Int, ids: Iterable[String]): Vector[WalletRow] =
    if ids.isEmpty then Vector.empty
    else views.forView(Wallets).ask(byPlayers, 100_000, "generation" -> generation.toString, "ids" -> ids.mkString(","))

  def open(views: ViewClient, generation: Int, currency: String): Vector[WalletRow] =
    views.forView(Wallets).ask(withOpenRecords, 1_000_000, "generation" -> generation.toString, "currency" -> currency)

  /** How many of the generation's wallets are in `currency`: the default currency's rule (research R6). */
  def count(views: ViewClient, generation: Int, currency: String): Long =
    views.forView(Wallets).count(jsonText("generation") ++ sql" = ${generation.toString} AND " ++ jsonText("currency") ++ sql" = $currency")
