package io.eitheror.casino.players.rounds

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.facts.Lenient
import io.eitheror.casino.players.player.Done

/**
 * A round as an operator reads it (spec 030 P6, P10; FR-023): what `casino.rounds` said of it, opened and
 * then settled, with the stake, the win and the net in minor units, the game session it was played in and
 * the transaction ids every reader searches by. Nothing personal. Times in epoch milliseconds.
 */
final case class RoundRow(
    roundId: String = "",
    playerId: String = "",
    sessionId: String = "",
    gameId: String = "",
    currency: String = "",
    provider: String = "",
    providerRoundId: String = "",
    status: String = "",
    outcome: Option[String] = None,
    stake: Long = 0L,
    win: Long = 0L,
    net: Long = 0L,
    transactionIds: Vector[String] = Vector.empty,
    providerTransactionIds: Vector[String] = Vector.empty,
    walletTransactionIds: Vector[String] = Vector.empty,
    freeRoundsGrant: Option[String] = None,
    openedAt: Option[Long] = None,
    settledAt: Option[Long] = None
):
  def exists: Boolean = roundId.nonEmpty
  def at: Long        = settledAt.orElse(openedAt).getOrElse(0L)

/** A round's status: open until `round_settled`, then settled, or cancelled when it settled so. */
object RoundStatus:
  val Open      = "open"
  val Settled   = "settled"
  val Cancelled = "cancelled"
  val all: Vector[String] = Vector(Open, Settled, Cancelled)

object RoundRow:
  def opened(f: RoundOpened): RoundRow =
    RoundRow(f.roundId, f.playerId, f.sessionId, f.gameId, f.currency.code, f.provider, f.providerRoundId, RoundStatus.Open,
      freeRoundsGrant = f.freeRoundsGrant, openedAt = Some(f.occurredAt.toEpochMilli))

  def settled(f: RoundSettled): RoundRow =
    RoundRow(f.roundId, f.playerId, f.sessionId, f.gameId, f.currency.code, f.provider, f.providerRoundId,
      if f.outcome == RoundSettled.Outcome.Cancelled then RoundStatus.Cancelled else RoundStatus.Settled, Some(f.outcome),
      f.stakeCents.value, f.winCents.value, f.netResultCents.value, f.transactionIds, f.providerTransactionIds, f.walletTransactionIds,
      f.freeRoundsGrant, f.openedAt.map(_.toEpochMilli), Some(f.occurredAt.toEpochMilli))

/** One round under its id; its settlement replaces its opening, and an opening after the settlement changes nothing. */
final class RoundEntry(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RoundRow]:
  def emptyState: RoundRow = RoundRow()

  def opened(r: RoundRow): Effect[Done] =
    if currentState.exists then effects.reply(Done.unchanged) else effects.updateState(r).thenReply(_ => Done(changed = true))

  def settled(r: RoundRow): Effect[Done] =
    val next = r.copy(openedAt = r.openedAt.orElse(currentState.openedAt))
    if currentState == next then effects.reply(Done.unchanged) else effects.updateState(next).thenReply(_ => Done(changed = true))

object RoundEntry
    extends KeyValueEntity.Companion[RoundEntry, RoundRow](
      componentId = ComponentId("round-entry"),
      stateSerializer = PlayersCodecs.serializer[RoundRow]("round-row")
    ):
  given Serializer[RoundRow] = PlayersCodecs.serializer[RoundRow]("round")
  given Serializer[Done]     = PlayersCodecs.serializer[Done]("done")

  def create(context: KeyValueEntityContext) = new RoundEntry(context)

  val opened  = command("opened")(_.opened)
  val settled = command("settled")(_.settled)

  def id(roundId: String): EntityId = EntityId(s"round/$roundId")

/**
 * `casino.rounds` into [[RoundEntry]]: a consumer of its own, so a deployment that already read the topic for
 * the activity reads it again from the earliest message the broker holds.
 */
final class RoundsRecorder(client: ComponentClient) extends Consumer[Option[CasinoRoundsFact], Nothing]:
  def onMessage(fact: Option[CasinoRoundsFact]): Effect =
    fact.foreach {
      case f: RoundOpened  => client.forKeyValueEntity(RoundEntry.id(f.roundId)).call(RoundEntry.opened).invoke(RoundRow.opened(f)): Unit
      case f: RoundSettled => client.forKeyValueEntity(RoundEntry.id(f.roundId)).call(RoundEntry.settled).invoke(RoundRow.settled(f)): Unit
    }
    effects.done()

object RoundsRecorder extends Consumer.Companion[RoundsRecorder, Option[CasinoRoundsFact], Nothing](
      ComponentId("rounds-recorder"),
      ChangeSource.fromTopic(CasinoRounds.name, Lenient.of(CasinoRounds), StartFrom.Earliest, TopicOptions(Some(CasinoRounds.contract), None, false))):
  def create(ctx: ConsumerContext) = new RoundsRecorder(ctx.componentClient)

/** The rounds, one row each, as the bets routes ask them. */
final class RoundsViewRows extends View[RoundRow, RoundRow]:
  def onChange(state: RoundRow): Effect = if state.exists then effects.updateRow(state) else effects.ignore()
  override def onDelete: Effect = effects.deleteRow()

object RoundsView extends View.Companion[RoundsViewRows, RoundRow, RoundRow](
      componentId = ComponentId("rounds"),
      source = ChangeSource.stateOf(RoundEntry),
      rowSerializer = PlayersCodecs.serializer[RoundRow]("round-view-row")
    ):
  def create(ctx: ViewComponentContext) = new RoundsViewRows

  private val at = "COALESCE((payload::jsonb->>'settledAt')::bigint, (payload::jsonb->>'openedAt')::bigint, 0)"

  /**
   * Which rounds a read wants (spec 030 P10): a player's or a game session's; `statuses` (none for any); a
   * round by its id or its provider's; a transaction by any of its ids; `type` `bet` (a stake) or `win` (a
   * win); `[from, to)` on the settlement, or the opening of an open round, in epoch milliseconds.
   */
  final case class Wanted(
      playerId: Option[String] = None,
      sessionId: Option[String] = None,
      statuses: Set[String] = Set.empty,
      roundId: Option[String] = None,
      transactionId: Option[String] = None,
      kind: Option[String] = None,
      from: Option[Long] = None,
      to: Option[Long] = None
  ):
    private def text(field: String) = SqlFragment.raw(s"COALESCE(payload::jsonb->>'$field', '')")
    private def number(field: String) = SqlFragment.raw(s"COALESCE((payload::jsonb->>'$field')::bigint, 0)")
    private def holds(field: String, v: String) =
      SqlFragment.raw(s"COALESCE(payload::jsonb->'$field', '[]'::jsonb) @> jsonb_build_array(CAST(") ++ sql"$v" ++ SqlFragment.raw(" AS text))")

    def condition: SqlFragment =
      val terms = Vector.newBuilder[SqlFragment]
      playerId.foreach(p => terms += text("playerId") ++ sql" = $p")
      sessionId.foreach(s => terms += text("sessionId") ++ sql" = $s")
      if statuses.nonEmpty then terms += text("status") ++ sql" = ANY(string_to_array(${statuses.toVector.sorted.mkString(",")}, ','))"
      roundId.foreach(r => terms += SqlFragment.raw("(") ++ text("roundId") ++ sql" = $r" ++ SqlFragment.raw(" OR ") ++ text("providerRoundId") ++ sql" = $r" ++ SqlFragment.raw(")"))
      transactionId.foreach(t => terms += SqlFragment.raw("(") ++ holds("transactionIds", t) ++ SqlFragment.raw(" OR ") ++
        holds("providerTransactionIds", t) ++ SqlFragment.raw(" OR ") ++ holds("walletTransactionIds", t) ++ SqlFragment.raw(")"))
      kind.foreach {
        case "win" => terms += number("win") ++ SqlFragment.raw(" > 0")
        case _     => terms += number("stake") ++ SqlFragment.raw(" > 0")
      }
      from.foreach(f => terms += SqlFragment.raw(at) ++ sql" >= $f")
      to.foreach(t => terms += SqlFragment.raw(at) ++ sql" < $t")
      val all = terms.result()
      if all.isEmpty then SqlFragment.raw("TRUE")
      else all.tail.foldLeft(SqlFragment.raw("(") ++ all.head)((acc, t) => acc ++ SqlFragment.raw(") AND (") ++ t) ++ SqlFragment.raw(")")

  /** The wanted rounds, newest first (the settlement, else the opening), from `offset`. */
  def page(views: ViewClient, w: Wanted, offset: Int, limit: Int): Vector[RoundRow] =
    // the view client puts its LIMIT after the order, and Postgres takes OFFSET and LIMIT in either order
    views.forView(RoundsView).ordered(w.condition, SqlFragment.raw(s"$at DESC, row_key ASC OFFSET ${offset.max(0)}"), limit)

  def total(views: ViewClient, w: Wanted): Long = views.forView(RoundsView).count(w.condition)

  /** At most this many rounds are summed for a read's totals. */
  val SumLimit: Int = 100_000

  /** Every wanted round, up to [[SumLimit]], for the totals. */
  def all(views: ViewClient, w: Wanted): Vector[RoundRow] = views.forView(RoundsView).where(w.condition, SumLimit)

/** What a set of rounds staked, won and netted, in one currency (never summed across currencies). */
final case class RoundTotals(currency: String, rounds: Int, staked: Long, won: Long, net: Long)

object RoundTotals:
  def of(rows: Vector[RoundRow]): Vector[RoundTotals] =
    rows.groupBy(_.currency).toVector.sortBy(_._1).map((c, rs) => RoundTotals(c, rs.size, rs.map(_.stake).sum, rs.map(_.win).sum, rs.map(_.net).sum))

// ── a game session's wagering, from the wallet's bets ───────────────────────────────────────────────────

/**
 * What the wallet took in bets during one game session (spec 030 P5; the original's `total_wagered`), each bet
 * once under its transaction id. Set beside the rounds' stakes it shows a gap: bets with no round recorded.
 */
final case class SessionWagersState(sessionId: String = "", playerId: String = "", currency: String = "", bets: Map[String, Long] = Map.empty):
  def exists: Boolean = sessionId.nonEmpty
  def wagered: Long   = bets.values.sum

final case class BetSeen(sessionId: String, playerId: String, currency: String, transactionId: String, amount: Long)

final class SessionWagers(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[SessionWagersState]:
  def emptyState: SessionWagersState = SessionWagersState()

  def bet(b: BetSeen): Effect[Done] =
    if currentState.bets.contains(b.transactionId) then effects.reply(Done.unchanged)
    else
      effects.updateState(SessionWagersState(b.sessionId, b.playerId, b.currency, currentState.bets.updated(b.transactionId, b.amount)))
        .thenReply(_ => Done(changed = true))

  def get: ReadOnlyEffect[SessionWagersState] = effects.reply(currentState)

object SessionWagers
    extends KeyValueEntity.Companion[SessionWagers, SessionWagersState](
      componentId = ComponentId("session-wagers"),
      stateSerializer = PlayersCodecs.serializer[SessionWagersState]("session-wagers-state")
    ):
  given Serializer[BetSeen]            = PlayersCodecs.serializer[BetSeen]("bet-seen")
  given Serializer[SessionWagersState] = PlayersCodecs.serializer[SessionWagersState]("session-wagers")
  given Serializer[Done]               = PlayersCodecs.serializer[Done]("done")

  def create(context: KeyValueEntityContext) = new SessionWagers(context)

  val bet = command("bet")(_.bet)
  val get = query("get")(_.get)

  def id(sessionId: String): EntityId = EntityId(s"wagers/$sessionId")

  def of(client: ComponentClient, sessionId: String): SessionWagersState =
    client.forKeyValueEntity(id(sessionId)).call(get).invoke()

/** `money.transactions`' bets that name a game session into [[SessionWagers]], from the earliest message the broker holds. */
final class WagersRecorder(client: ComponentClient) extends Consumer[Option[MoneyTransactionsMessage], Nothing]:
  def onMessage(message: Option[MoneyTransactionsMessage]): Effect =
    message.foreach {
      case f: BetPlaced =>
        f.sessionId.filter(_.nonEmpty).foreach(s => client.forKeyValueEntity(SessionWagers.id(s)).call(SessionWagers.bet)
          .invoke(BetSeen(s, f.playerId, f.currency.code, f.transactionId, f.amountCents.value)): Unit)
      case _ => ()
    }
    effects.done()

object WagersRecorder extends Consumer.Companion[WagersRecorder, Option[MoneyTransactionsMessage], Nothing](
      ComponentId("wagers-recorder"),
      ChangeSource.fromTopic(MoneyTransactions.name, Lenient.of(MoneyTransactions), StartFrom.Earliest,
        TopicOptions(Some(MoneyTransactions.contract), None, false))):
  def create(ctx: ConsumerContext) = new WagersRecorder(ctx.componentClient)
