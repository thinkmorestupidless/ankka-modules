package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.player.Done
import io.eitheror.casino.players.facts.Lenient

import java.time.Instant

/**
 * One line of a player's activity history (FR-029, FR-034), in the original's terms: the category, the
 * event type, who acted, the amount and currency, and a description. Never a note or a message, and
 * nothing personal. Named by the fact (its topic, record key and offset), so a redelivered fact
 * writes the same line.
 */
final case class ActivityRow(
    playerId: String,
    at: Long,
    category: String,
    eventType: String,
    actor: String,
    amountMinorUnits: Option[Long] = None,
    currency: Option[String] = None,
    description: String = "",
    factId: String = ""
):
  def exists: Boolean = factId.nonEmpty

object ActivityCategory:
  val Transactions = "transactions"
  val Games        = "games"
  val Bonus        = "bonus"
  val Account      = "account"
  val Rg           = "rg"

  val all: Vector[String] = Vector(Transactions, Games, Bonus, Account, Rg)

  /** The original's capitalised names, as its activity history answers them. */
  def wire(category: String): String = if category == Rg then "RG" else category.capitalize

/**
 * Which facts of the five topics are lines of a player's history, and how each reads. The mapping keeps
 * the original's event types where it had one (`auditEventMetaForPlayer`); bonuses are read from
 * `casino.rewards`, not from the wallet's restricted-funds families, so each shows once. Pure: the
 * consumers name the line and `ActivityEntry` keeps it.
 */
object ActivityLines:
  import ActivityCategory.*

  private def line(playerId: String, at: Instant, category: String, eventType: String, actor: String, description: String,
      amount: Option[Long] = None, currency: Option[String] = None) =
    Some(ActivityRow(playerId, at.toEpochMilli, category, eventType, actor, amount, currency, description))

  def money(fact: MoneyTransactionsFact): Option[ActivityRow] =
    def tx(eventType: String, actor: String, description: String, amount: Long) =
      line(fact.playerId, fact.occurredAt, Transactions, eventType, actor, description, Some(amount), Some(fact.currency.code))
    fact match
      case f: DepositConfirmed   => tx("deposit", "player", "Deposit", f.amountCents.value)
      case f: BetPlaced          => tx("bet", "player", "Bet", f.amountCents.value)
      case f: WinCredited        => tx("win", "system", "Win", f.amountCents.value)
      case _: BetRolledBack      => tx("rollback", "system", "Bet rollback", 0L)
      case _: RoundSettledAsLoss => tx("loss", "system", "Round settled as loss", 0L)
      case f: AdminAdjusted      => tx("adjustment", "operator", "Balance adjusted", f.amount.value)
      case _                     => None

  def round(fact: CasinoRoundsFact): Option[ActivityRow] = fact match
    case r: RoundSettled =>
      line(r.playerId, r.occurredAt, Games, "round_settled", "player", s"Round settled: ${r.outcome}", Some(r.netResultCents.value), Some(r.currency.code))

  def reward(fact: CasinoRewardsFact): Option[ActivityRow] =
    def bonus(at: Instant, eventType: String, actor: String, description: String, amount: Option[Long] = None, currency: Option[String] = None) =
      line(fact.playerId, at, Bonus, eventType, actor, description, amount, currency)
    fact match
      case f: BonusGranted     => bonus(f.occurredAt, "bonus_credit", "operator", "Bonus awarded", Some(f.amountCents.value), Some(f.currency.code))
      case f: BonusCancelled   => bonus(f.occurredAt, "bonus_cancel", "operator", "Bonus cancelled", Some(f.amountCents.value), Some(f.currency.code))
      case f: BonusForfeited   => bonus(f.occurredAt, "bonus_forfeited", "system", "Bonus forfeited")
      case f: BonusCompleted   => bonus(f.occurredAt, "bonus_conversion", "system", "Bonus converted")
      case f: GrantExpired     => bonus(f.occurredAt, "bonus_expired", "system", "Bonus expired")
      case f: FreeSpinsGranted => bonus(f.occurredAt, "free_spins_granted", "operator", "Free spins awarded")
      case f: FreeSpinsSettled => bonus(f.occurredAt, "free_spins_settled", "system", "Free spins settled", Some(f.winCents.value), Some(f.currency.code))
      case _                   => None

  def player(fact: CasinoPlayersFact): Option[ActivityRow] =
    def account(eventType: String, actor: String, description: String) =
      line(fact.playerId, fact.occurredAt, Account, eventType, actor, description)
    def rg(eventType: String, description: String) = line(fact.playerId, fact.occurredAt, Rg, eventType, "operator", description)
    fact match
      case f: PlayerSuspended    => account("account_status_changed", f.actor, "Account suspended")
      case f: PlayerReinstated   => account("account_status_changed", f.actor, "Account reinstated")
      case f: PlayerSelfExcluded => account("account_status_changed", f.actor, "Account self-excluded")
      case f: PlayerClosed       => account("account_status_changed", f.actor, "Account closed")
      case f: PlayerReopened     => account("account_status_changed", f.actor, "Account reopened")
      case _: PlayerProfileUpdated => account("profile_updated", "player", "Profile updated")
      case f: PlayerKycVerified  => account("kyc_updated", f.actor, "KYC verified")
      case f: PlayerKycRejected  => account("kyc_updated", f.actor, "KYC rejected")
      case _: RgCaseOpened       => rg("rg_case_opened", "RG case opened")
      case f: RgLimitApplied     => rg("rg_limit_applied", s"Limit applied: ${f.limitType.toLowerCase}")
      case _                     => None

  def session(fact: CasinoSessionsFact): Option[ActivityRow] = fact match
    case f: PlayerSessionStarted => line(f.playerId, f.occurredAt, Account, "player_session_started", "player", "Player session started")
    case f: GameSessionStarted   => line(f.playerId, f.occurredAt, Games, "game_session_started", "player", "Game session started")
    case _                       => None

/** One line, kept under its fact's id; a second record of the same fact changes nothing. */
final class ActivityEntry(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ActivityRow]:
  def emptyState: ActivityRow = ActivityRow("", 0L, "", "", "")

  def record(row: ActivityRow): Effect[Done] =
    if currentState.exists then effects.reply(Done.unchanged) else effects.updateState(row).thenReply(_ => Done(changed = true))

  def erase: Effect[Done] = if currentState.exists then effects.deleteEntity().thenReply(_ => Done(changed = true)) else effects.reply(Done.unchanged)

object ActivityEntry
    extends KeyValueEntity.Companion[ActivityEntry, ActivityRow](
      componentId = ComponentId("activity-entry"),
      stateSerializer = PlayersCodecs.serializer[ActivityRow]("activity-row")
    ):
  given Serializer[ActivityRow] = PlayersCodecs.serializer[ActivityRow]("activity-line")
  given Serializer[Done]        = PlayersCodecs.serializer[Done]("done")

  def create(context: KeyValueEntityContext) = new ActivityEntry(context)

  val record = command("record")(_.record)
  val erase  = command("erase")(_.erase)

  /**
   * A fact's id: its topic, record key and a digest of the fact. ankka hands a topic consumer no offset
   * (its sequence number is always 0), so two facts under one key are told apart by what they say, and a
   * redelivery of the same fact lands on the same line.
   */
  def key(topic: String, subject: String, fact: Any): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(fact.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"$topic/$subject/${java.util.HexFormat.of().formatHex(digest).take(32)}"

  /** Records the line `row` names for the fact the consumer is handling, if it is one. */
  def keep(client: ComponentClient, topic: String, change: ChangeContext, fact: Any, row: Option[ActivityRow]): Unit =
    row.foreach { r =>
      val id = key(topic, change.subject, fact)
      client.forKeyValueEntity(EntityId(id)).call(record).invoke(r.copy(factId = id)): Unit
    }

/**
 * The five consumers that turn facts into lines (research R11: a keyed view reads entities only, and a
 * consumer reads one source). Each reads from the earliest message the broker holds, as the view did.
 */
private[reads] object FromTopic:
  def apply[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, Lenient.of(topic), StartFrom.Earliest, TopicOptions(Some(topic.contract), None, false))

final class MoneyActivity(client: ComponentClient) extends Consumer[Option[MoneyTransactionsMessage], Nothing]:
  // a non-player account's message (spec 022's account_adjusted) is no player's activity
  def onMessage(message: Option[MoneyTransactionsMessage]): Effect =
    val fact = message.collect { case f: MoneyTransactionsFact => f }
    ActivityEntry.keep(client, MoneyTransactions.name, messageContext, fact, fact.flatMap(ActivityLines.money))
    effects.done()

object MoneyActivity extends Consumer.Companion[MoneyActivity, Option[MoneyTransactionsMessage], Nothing](
      ComponentId("money-activity"), FromTopic(MoneyTransactions)):
  def create(ctx: ConsumerContext) = new MoneyActivity(ctx.componentClient)

/** `casino.rounds`: the history's line and the player's recently played game. */
final class RoundsActivity(client: ComponentClient) extends Consumer[Option[CasinoRoundsFact], Nothing]:
  def onMessage(fact: Option[CasinoRoundsFact]): Effect =
    ActivityEntry.keep(client, CasinoRounds.name, messageContext, fact, fact.flatMap(ActivityLines.round))
    fact.collect { case r: RoundSettled => r }.foreach(r => RecentlyPlayed.touch(client, r.playerId, r.gameId, r.occurredAt))
    effects.done()

object RoundsActivity extends Consumer.Companion[RoundsActivity, Option[CasinoRoundsFact], Nothing](
      ComponentId("rounds-activity"), FromTopic(CasinoRounds)):
  def create(ctx: ConsumerContext) = new RoundsActivity(ctx.componentClient)

final class RewardsActivity(client: ComponentClient) extends Consumer[Option[CasinoRewardsFact], Nothing]:
  def onMessage(fact: Option[CasinoRewardsFact]): Effect =
    ActivityEntry.keep(client, CasinoRewards.name, messageContext, fact, fact.flatMap(ActivityLines.reward))
    effects.done()

object RewardsActivity extends Consumer.Companion[RewardsActivity, Option[CasinoRewardsFact], Nothing](
      ComponentId("rewards-activity"), FromTopic(CasinoRewards)):
  def create(ctx: ConsumerContext) = new RewardsActivity(ctx.componentClient)

final class PlayersActivity(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect =
    ActivityEntry.keep(client, CasinoPlayers.name, messageContext, fact, fact.flatMap(ActivityLines.player))
    effects.done()

object PlayersActivity extends Consumer.Companion[PlayersActivity, Option[CasinoPlayersFact], Nothing](
      ComponentId("players-activity"), FromTopic(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new PlayersActivity(ctx.componentClient)

final class SessionsActivity(client: ComponentClient) extends Consumer[Option[CasinoSessionsFact], Nothing]:
  def onMessage(fact: Option[CasinoSessionsFact]): Effect =
    ActivityEntry.keep(client, CasinoSessions.name, messageContext, fact, fact.flatMap(ActivityLines.session))
    effects.done()

object SessionsActivity extends Consumer.Companion[SessionsActivity, Option[CasinoSessionsFact], Nothing](
      ComponentId("sessions-activity"), FromTopic(CasinoSessions)):
  def create(ctx: ConsumerContext) = new SessionsActivity(ctx.componentClient)

/** The lines, one row per fact, as the activity routes ask them. */
final class ActivityViewRows extends View[ActivityRow, ActivityRow]:
  def onChange(state: ActivityRow): Effect = if state.exists then effects.updateRow(state) else effects.ignore()
  override def onDelete: Effect = effects.deleteRow()

object ActivityView extends View.Companion[ActivityViewRows, ActivityRow, ActivityRow](
      componentId = ComponentId("activity"),
      source = ChangeSource.stateOf(ActivityEntry),
      rowSerializer = PlayersCodecs.serializer[ActivityRow]("activity-row")
    ):
  def create(ctx: ViewComponentContext) = new ActivityViewRows

  /** A player's history, newest first, in one category or every one (`''`). */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player AND (:category = '' OR payload::jsonb->>'category' = :category) " +
      "ORDER BY (payload::jsonb->>'at')::bigint DESC, row_key ASC OFFSET CAST(:offset AS int)")

  def ofPlayer(views: ViewClient, playerId: String, category: Option[String], offset: Int, limit: Int): Vector[ActivityRow] =
    views.forView(ActivityView).ask(byPlayer, limit, "player" -> playerId, "category" -> category.getOrElse(""),
      "offset" -> offset.max(0).toString)
