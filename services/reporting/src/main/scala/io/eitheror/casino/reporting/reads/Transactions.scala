package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{BookEvent, Generation, Moved, WalletBook}

import java.time.Instant

/**
 * One wallet movement as the transaction report lists it (data-model.md `Transactions`, key
 * `g<n>/<player>/<currency>/<factId>`; FR-010): its time, type (the original's name, `ledger/Types`), amount and the
 * reference it shows. Keyed by the fact, so a redelivery writes the same row. No personal value: the truncated email
 * is read from `Players` when the page is answered, so an erased player reads `[erased]` with the row unchanged.
 */
final case class TransactionRow(generation: Int, player: String = "", currency: String = "", transactionId: String = "",
    at: Instant = Instant.EPOCH, tpe: String = "", amount: Long = 0, reference: String = "")

object TransactionRow:
  given Serializer[TransactionRow] = ReportingCodecs.row[TransactionRow]("transaction-row")

final class TransactionsView extends KeyedView[TransactionRow]:
  /** A casino movement's row; spec 022's families have no type and are no player's transactions. */
  def onWallet(event: BookEvent, change: Change): Effect = event match
    case m: Moved if m.casino && m.kind.nonEmpty =>
      Generation.of(change.subject).fold(effects.ignore())(g =>
        effects.updateRow(s"${change.subject}/${m.factId}",
          TransactionRow(g, m.player, m.currency, m.transactionId, m.at, m.kind, m.amount, m.reference)))
    case _ => effects.ignore()

object Transactions extends KeyedView.Companion[TransactionsView, TransactionRow](
      ComponentId("transactions"),
      TransactionRow.given_Serializer_TransactionRow
    ):
  val wallets = source(ChangeSource.eventsOf(WalletBook))(_.onWallet)

  private val where =
    s"payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'at')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'at')::timestamptz < (:to)::timestamptz AND (:type = 'all' OR payload::jsonb->>'tpe' = :type OR (:type = 'bonus' AND payload::jsonb->>'tpe' LIKE 'bonus\\_%')) AND (:ids = '*' OR payload::jsonb->>'player' = ANY(string_to_array(:ids, ',')))"

  /**
   * The movements from `from` (inclusive) to `to` (exclusive), newest first, of `type` (`all`, an exact type, or `bonus`
   * for every `bonus_*` type, `ledger/Types.matches`) and of the players `ids` names (`*` for every player), from `offset`.
   */
  val between = query("between")(s"SELECT payload FROM $table WHERE $where ORDER BY (payload::jsonb->>'at')::timestamptz DESC, row_key DESC OFFSET (:offset)::int")

  def create(ctx: ViewComponentContext) = new TransactionsView

  /** What a page is asked for: the period's instants, the type filter and the players, `None` for every one. */
  final case class Asked(generation: Int, from: Instant, to: Instant, tpe: String, players: Option[Iterable[String]]):
    def values(offset: Int): Seq[(String, String)] = Seq("generation" -> generation.toString, "from" -> from.toString,
      "to" -> to.toString, "type" -> tpe, "ids" -> players.fold("*")(_.mkString(",")), "offset" -> offset.toString)

  def page(views: ViewClient, asked: Asked, offset: Int, limit: Int): Vector[TransactionRow] =
    if asked.players.exists(_.isEmpty) then Vector.empty
    else views.forView(Transactions).ask(between, limit, asked.values(offset)*)

  /** How many movements `between` holds for `asked`: the page's `totalCount`. */
  def count(views: ViewClient, asked: Asked): Long =
    if asked.players.exists(_.isEmpty) then 0L
    else
      val period = jsonText("generation") ++ sql" = ${asked.generation.toString} AND (" ++ jsonText("at") ++
        sql")::timestamptz >= (${asked.from.toString})::timestamptz AND (" ++ jsonText("at") ++
        sql")::timestamptz < (${asked.to.toString})::timestamptz"
      val typed = asked.tpe match
        case "all"   => period
        case "bonus" => period ++ SqlFragment.raw(" AND ") ++ jsonText("tpe") ++ SqlFragment.raw(" LIKE 'bonus\\_%'")
        case t       => period ++ SqlFragment.raw(" AND ") ++ jsonText("tpe") ++ sql" = $t"
      val condition = asked.players.fold(typed)(ids =>
        typed ++ SqlFragment.raw(" AND ") ++ jsonText("player") ++ sql" = ANY(string_to_array(${ids.mkString(",")}, ','))")
      views.forView(Transactions).count(condition)

  def all(views: ViewClient, asked: Asked): Vector[TransactionRow] =
    @scala.annotation.tailrec
    def from(offset: Int, acc: Vector[TransactionRow]): Vector[TransactionRow] =
      val rows = page(views, asked, offset, Page)
      if rows.size < Page then acc ++ rows else from(offset + rows.size, acc ++ rows)
    from(0, Vector.empty)

  val Page = 10_000
