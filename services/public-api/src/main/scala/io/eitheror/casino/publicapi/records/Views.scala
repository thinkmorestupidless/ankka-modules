package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.wire.{Money, Page}

/**
 * The six record views (data-model.md *Records*): one row per record the contract can serve, the record's state
 * without its applied facts. A record the contract cannot serve is refused here (`Records.refused`), so it is `404` by
 * id and in no page. Each child collection is a declared query in data-model.md's order (its time, then id) with the
 * page's `LIMIT` and `OFFSET` (research R16.1's fallback), and a count over the same condition.
 */
object Views:
  val Uuid     = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r
  val GameCode = "^[A-Za-z0-9._-]{1,64}$".r

  def money(currency: String): Either[String, Unit] = Money.of(0L, currency).left.map(_.message).map(_ => ())

  def ordered(table: String, where: String, time: String): String =
    s"SELECT payload FROM $table WHERE $where ORDER BY (payload::jsonb->>'$time')::timestamptz, payload::jsonb->>'id' " +
      "LIMIT (:limit)::bigint OFFSET (:offset)::bigint"

  def by(field: String, value: String): SqlFragment = jsonText(field) ++ sql" = $value"

  def page[R](q: ViewQueries[R], query: DeclaredQuery, page: Page, values: (String, String)*): Vector[R] =
    q.ask(query, page.limit, (values ++ Seq("limit" -> page.limit.toString, "offset" -> page.offset.toString))*)

  /** The row for a record, or none (and the record refused) when the contract cannot serve it. */
  def checked[S](kind: String, id: String, check: Either[String, Unit])(row: => S): Option[S] =
    check match
      case Right(()) => Some(row)
      case Left(why) => Records.refused(kind, id, why); None


import Views.*

final class PlayersUpdater extends View[PlayerState, PlayerState]:
  def onChange(s: PlayerState): Effect =
    // A player is not served until registration arrives (the contract requires registeredAt); that is not a refusal.
    if s.registeredAt.isEmpty || s.id.isEmpty then effects.ignore() else effects.updateRow(s.copy(applied = Vector.empty))

object Players extends View.Companion[PlayersUpdater, PlayerState, PlayerState](
      componentId = ComponentId("players"),
      source = ChangeSource.stateOf(PlayerRecord),
      rowSerializer = PublicApiCodecs.row[PlayerState]("player-row")
    ):
  def create(ctx: ViewComponentContext) = new PlayersUpdater
  val listed = query("listed")(ordered(table, "TRUE", "registeredAt"))

final class SessionsUpdater extends View[SessionState, SessionState]:
  def onChange(s: SessionState): Effect =
    val row = checked("session", s.id, Either.cond(s.startedAt.isDefined && Uuid.matches(s.id) && Uuid.matches(s.playerId), (), "no start or not an id"))(
      s.copy(applied = Vector.empty, gameId = s.gameId.filter(GameCode.matches)))
    row.fold(if rowState.isDefined then effects.deleteRow() else effects.ignore())(effects.updateRow)

object Sessions extends View.Companion[SessionsUpdater, SessionState, SessionState](
      componentId = ComponentId("sessions"),
      source = ChangeSource.stateOf(SessionRecord),
      rowSerializer = PublicApiCodecs.row[SessionState]("session-row")
    ):
  def create(ctx: ViewComponentContext) = new SessionsUpdater
  val ofPlayer = query("of-player")(ordered(table, "payload::jsonb->>'playerId' = :player", "startedAt"))

final class RoundsUpdater extends View[RoundState, RoundState]:
  def onChange(s: RoundState): Effect =
    val check = for
      _ <- Either.cond(GameCode.matches(s.gameId), (), "no game code")
      _ <- Either.cond(s.startedAt.isDefined && Uuid.matches(s.playerId), (), "no start or player")
      _ <- money(s.currency)
    yield ()
    checked("round", s.id, check)(s.copy(applied = Vector.empty, walletTxIds = Vector.empty, sessionId = s.sessionId.filter(Uuid.matches)))
      .fold(if rowState.isDefined then effects.deleteRow() else effects.ignore())(effects.updateRow)

object Rounds extends View.Companion[RoundsUpdater, RoundState, RoundState](
      componentId = ComponentId("rounds"),
      source = ChangeSource.stateOf(RoundRecord),
      rowSerializer = PublicApiCodecs.row[RoundState]("round-row")
    ):
  def create(ctx: ViewComponentContext) = new RoundsUpdater
  val ofPlayer  = query("of-player")(ordered(table, "payload::jsonb->>'playerId' = :player", "startedAt"))
  val ofSession = query("of-session")(ordered(table, "payload::jsonb->>'sessionId' = :session", "startedAt"))

final class BetsUpdater extends View[BetState, BetState]:
  def onChange(s: BetState): Effect =
    val check = Either.cond(s.placedAt.isDefined && Uuid.matches(s.id) && Uuid.matches(s.playerId), (), "no placement or not an id").flatMap(_ => money(s.currency))
    checked("bet", s.id, check)(s.copy(applied = Vector.empty, sessionId = s.sessionId.filter(Uuid.matches)))
      .fold(if rowState.isDefined then effects.deleteRow() else effects.ignore())(effects.updateRow)

object Bets extends View.Companion[BetsUpdater, BetState, BetState](
      componentId = ComponentId("bets"),
      source = ChangeSource.stateOf(BetRecord),
      rowSerializer = PublicApiCodecs.row[BetState]("bet-row")
    ):
  def create(ctx: ViewComponentContext) = new BetsUpdater
  val ofPlayer = query("of-player")(ordered(table, "payload::jsonb->>'playerId' = :player", "placedAt"))
  val ofRound  = query("of-round")(ordered(table, "payload::jsonb->>'roundId' = :round", "placedAt"))

final class TransactionsUpdater extends View[TransactionState, TransactionState]:
  def onChange(s: TransactionState): Effect =
    val check = Either.cond(s.createdAt.isDefined && s.typeWord.nonEmpty && Uuid.matches(s.id) && Uuid.matches(s.playerId), (),
      "no time, type or id").flatMap(_ => money(s.currency))
    checked("transaction", s.id, check)(s.copy(applied = Vector.empty, gameId = s.gameId.filter(GameCode.matches)))
      .fold(if rowState.isDefined then effects.deleteRow() else effects.ignore())(effects.updateRow)

object Transactions extends View.Companion[TransactionsUpdater, TransactionState, TransactionState](
      componentId = ComponentId("transactions"),
      source = ChangeSource.stateOf(TransactionRecord),
      rowSerializer = PublicApiCodecs.row[TransactionState]("transaction-row")
    ):
  def create(ctx: ViewComponentContext) = new TransactionsUpdater
  val ofPlayer = query("of-player")(ordered(table, "payload::jsonb->>'playerId' = :player", "createdAt"))

final class BonusAwardsUpdater extends View[BonusAwardState, BonusAwardState]:
  def onChange(s: BonusAwardState): Effect =
    // An award the grant has not reached yet (only the wallet's progress) is not served; that is not a refusal.
    if s.grantedAt.isEmpty then effects.ignore()
    else
      val check = for
        _ <- Either.cond(s.offerId.isDefined && s.valueCents.isDefined && s.status.isDefined && Uuid.matches(s.id) && Uuid.matches(s.playerId),
          (), "no offer, value, status or id")
        _ <- s.currency.toRight("no currency").flatMap(money)
      yield ()
      checked("bonus-award", s.id, check)(s.copy(applied = Vector.empty))
        .fold(if rowState.isDefined then effects.deleteRow() else effects.ignore())(effects.updateRow)

object BonusAwards extends View.Companion[BonusAwardsUpdater, BonusAwardState, BonusAwardState](
      componentId = ComponentId("bonus-awards"),
      source = ChangeSource.stateOf(BonusAwardRecord),
      rowSerializer = PublicApiCodecs.row[BonusAwardState]("bonus-award-row")
    ):
  def create(ctx: ViewComponentContext) = new BonusAwardsUpdater
  val ofPlayer = query("of-player")(ordered(table, "payload::jsonb->>'playerId' = :player", "grantedAt"))
