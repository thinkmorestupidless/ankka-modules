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
    at: Instant = Instant.EPOCH, tpe: String = "", amount: Long = 0, reference: String = "", factId: String = ""):
  /** The row's key: its wallet's id and its fact's. */
  def key: String = s"${Generation.id(generation, player, currency)}/$factId"

object TransactionRow:
  given Serializer[TransactionRow] = ReportingCodecs.row[TransactionRow]("transaction-row")

final class TransactionsView extends KeyedView[TransactionRow]:
  /** A casino movement's row; spec 022's families have no type and are no player's transactions. */
  def onWallet(event: BookEvent, change: Change): Effect = event match
    case m: Moved if m.casino && m.kind.nonEmpty =>
      Generation.of(change.subject).fold(effects.ignore())(g =>
        effects.updateRow(s"${change.subject}/${m.factId}",
          TransactionRow(g, m.player, m.currency, m.transactionId, m.at, m.kind, m.amount, m.reference, m.factId)))
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

  /**
   * `between`'s rows in its order, a page at a time before the row at `before_at` keyed `before` (`infinity` and `""` for
   * the first page): what an export reads every row with, where an offset would read every row before it again.
   */
  val betweenBefore = query("between-before")(s"SELECT payload FROM $table WHERE $where AND ((payload::jsonb->>'at')::timestamptz, row_key) < ((:before_at)::timestamptz, :before) ORDER BY (payload::jsonb->>'at')::timestamptz DESC, row_key DESC")

  def create(ctx: ViewComponentContext) = new TransactionsView

  /** What a page is asked for: the period's instants, the type filter and the players, `None` for every one. */
  final case class Asked(generation: Int, from: Instant, to: Instant, tpe: String, players: Option[Iterable[String]]):
    def values(offset: Int): Seq[(String, String)] = Seq("generation" -> generation.toString, "from" -> from.toString,
      "to" -> to.toString, "type" -> tpe, "ids" -> players.fold("*")(_.mkString(",")), "offset" -> offset.toString)

  def page(views: ViewClient, asked: Asked, offset: Int, limit: Int): Vector[TransactionRow] =
    if asked.players.exists(_.isEmpty) || limit <= 0 then Vector.empty
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

  /** Every row `between` holds for `asked`, a page of `size` at a time as the iterator is read, never all at once. */
  def pages(views: ViewClient, asked: Asked, size: Int = Paging.Page): Iterator[Vector[TransactionRow]] =
    if asked.players.exists(_.isEmpty) then Iterator.empty
    else
      val values = asked.values(0).filterNot(_._1 == "offset")
      def after(last: Option[TransactionRow]) = views.forView(Transactions).ask(betweenBefore, size,
        (values ++ Seq("before_at" -> last.fold("infinity")(_.at.toString), "before" -> last.fold("")(_.key)))*)
      Iterator.unfold(Option(Option.empty[TransactionRow])) {
        case None       => None
        case Some(last) =>
          val rows = after(last)
          Option.when(rows.nonEmpty)(rows -> Option.when(rows.size == size)(rows.lastOption))
      }

  def all(views: ViewClient, asked: Asked): Vector[TransactionRow] = pages(views, asked).flatten.toVector
