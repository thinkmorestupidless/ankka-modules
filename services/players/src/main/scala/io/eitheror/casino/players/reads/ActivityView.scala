package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.facts.Lenient

import java.time.Instant

/**
 * One line of a player's activity history (FR-029, FR-034), in the original's terms: the category, the
 * event type, who acted, the amount and currency, and a description. Never a note or a message, and
 * nothing personal. Keyed by the fact (its topic, record key and offset), so a redelivered fact
 * writes the same row.
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
)

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
 * Every fact of the five topics that is a line of a player's history (research R11: a 0.13 keyed view
 * takes several topic sources, so this is one view, not one per topic). The mapping keeps the
 * original's event types where it had one (`auditEventMetaForPlayer`); bonuses are read from
 * `casino.rewards`, not from the wallet's restricted-funds families, so each shows once.
 */
final class ActivityViewRows extends KeyedView[ActivityRow]:
  import ActivityCategory.*

  private def line(change: Change, topic: String, playerId: String, at: Instant, category: String, eventType: String,
      actor: String, description: String, amount: Option[Long] = None, currency: Option[String] = None): Effect =
    val id = ActivityView.key(topic, change.subject, change.sequenceNumber)
    effects.updateRow(id, ActivityRow(playerId, at.toEpochMilli, category, eventType, actor, amount, currency, description, id))

  def onMoney(fact: Option[MoneyTransactionsFact], change: Change): Effect =
    val topic = MoneyTransactions.name
    def tx(f: MoneyTransactionsFact, eventType: String, actor: String, description: String, amount: Long) =
      line(change, topic, f.playerId, f.occurredAt, Transactions, eventType, actor, description, Some(amount), Some(f.currency.code))
    fact match
      case Some(f: DepositConfirmed)   => tx(f, "deposit", "player", "Deposit", f.amountCents.value)
      case Some(f: BetPlaced)          => tx(f, "bet", "player", "Bet", f.amountCents.value)
      case Some(f: WinCredited)        => tx(f, "win", "system", "Win", f.amountCents.value)
      case Some(f: BetRolledBack)      => tx(f, "rollback", "system", "Bet rollback", 0L)
      case Some(f: RoundSettledAsLoss) => tx(f, "loss", "system", "Round settled as loss", 0L)
      case Some(f: AdminAdjusted)      => tx(f, "adjustment", "operator", "Balance adjusted", f.amount.value)
      case _                           => effects.ignore()

  def onRound(fact: Option[CasinoRoundsFact], change: Change): Effect = fact match
    case Some(r: RoundSettled) =>
      line(change, CasinoRounds.name, r.playerId, r.occurredAt, Games, "round_settled", "player", s"Round settled: ${r.outcome}",
        Some(r.netResultCents.value), Some(r.currency.code))
    case _ => effects.ignore()

  def onReward(fact: Option[CasinoRewardsFact], change: Change): Effect =
    val topic = CasinoRewards.name
    def bonus(f: CasinoRewardsFact, at: Instant, eventType: String, actor: String, description: String,
        amount: Option[Long] = None, currency: Option[String] = None) =
      line(change, topic, f.playerId, at, Bonus, eventType, actor, description, amount, currency)
    fact match
      case Some(f: BonusGranted)     => bonus(f, f.occurredAt, "bonus_credit", "operator", "Bonus awarded", Some(f.amountCents.value), Some(f.currency.code))
      case Some(f: BonusCancelled)   => bonus(f, f.occurredAt, "bonus_cancel", "operator", "Bonus cancelled", Some(f.amountCents.value), Some(f.currency.code))
      case Some(f: BonusForfeited)   => bonus(f, f.occurredAt, "bonus_forfeited", "system", "Bonus forfeited")
      case Some(f: BonusCompleted)   => bonus(f, f.occurredAt, "bonus_conversion", "system", "Bonus converted")
      case Some(f: GrantExpired)     => bonus(f, f.occurredAt, "bonus_expired", "system", "Bonus expired")
      case Some(f: FreeSpinsGranted) => bonus(f, f.occurredAt, "free_spins_granted", "operator", "Free spins awarded")
      case Some(f: FreeSpinsSettled) => bonus(f, f.occurredAt, "free_spins_settled", "system", "Free spins settled", Some(f.winCents.value), Some(f.currency.code))
      case _                         => effects.ignore()

  def onPlayer(fact: Option[CasinoPlayersFact], change: Change): Effect =
    val topic = CasinoPlayers.name
    def account(f: CasinoPlayersFact, eventType: String, actor: String, description: String) =
      line(change, topic, f.playerId, f.occurredAt, Account, eventType, actor, description)
    def rg(f: CasinoPlayersFact, eventType: String, description: String) =
      line(change, topic, f.playerId, f.occurredAt, Rg, eventType, "operator", description)
    fact match
      case Some(f: PlayerSuspended)      => account(f, "account_status_changed", f.actor, "Account suspended")
      case Some(f: PlayerReinstated)     => account(f, "account_status_changed", f.actor, "Account reinstated")
      case Some(f: PlayerSelfExcluded)   => account(f, "account_status_changed", f.actor, "Account self-excluded")
      case Some(f: PlayerClosed)         => account(f, "account_status_changed", f.actor, "Account closed")
      case Some(f: PlayerReopened)       => account(f, "account_status_changed", f.actor, "Account reopened")
      case Some(f: PlayerProfileUpdated) => account(f, "profile_updated", "player", "Profile updated")
      case Some(f: PlayerKycVerified)    => account(f, "kyc_updated", f.actor, "KYC verified")
      case Some(f: PlayerKycRejected)    => account(f, "kyc_updated", f.actor, "KYC rejected")
      case Some(f: RgCaseOpened)         => rg(f, "rg_case_opened", "RG case opened")
      case Some(f: RgLimitApplied)       => rg(f, "rg_limit_applied", s"Limit applied: ${f.limitType.toLowerCase}")
      case _                             => effects.ignore()

  def onSession(fact: Option[CasinoSessionsFact], change: Change): Effect =
    val topic = CasinoSessions.name
    fact match
      case Some(f: PlayerSessionStarted) =>
        line(change, topic, f.playerId, f.occurredAt, Account, "player_session_started", "player", "Player session started")
      case Some(f: GameSessionStarted) =>
        line(change, topic, f.playerId, f.occurredAt, Games, "game_session_started", "player", "Game session started")
      case _ => effects.ignore()

object ActivityView extends KeyedView.Companion[ActivityViewRows, ActivityRow](
      ComponentId("activity"),
      PlayersCodecs.serializer[ActivityRow]("activity-row")
    ):
  def create(ctx: ViewComponentContext) = new ActivityViewRows

  /** A fact's id: its topic, record key and offset. */
  def key(topic: String, subject: String, offset: Long): String = s"$topic/$subject/$offset"

  private def from[F <: Fact](topic: Topic[F]) =
    ChangeSource.fromTopic(topic.name, Lenient.of(topic), StartFrom.Earliest, TopicOptions(Some(topic.contract), None, false))

  val money    = source(from(MoneyTransactions))(_.onMoney)
  val rounds   = source(from(CasinoRounds))(_.onRound)
  val rewards  = source(from(CasinoRewards))(_.onReward)
  val players  = source(from(CasinoPlayers))(_.onPlayer)
  val sessions = source(from(CasinoSessions))(_.onSession)

  /** A player's history, newest first, in one category or every one (`''`). */
  val byPlayer: DeclaredQuery = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player AND (:category = '' OR payload::jsonb->>'category' = :category) " +
      "ORDER BY (payload::jsonb->>'at')::bigint DESC, row_key ASC OFFSET CAST(:offset AS int)")

  def ofPlayer(views: ViewClient, playerId: String, category: Option[String], offset: Int, limit: Int): Vector[ActivityRow] =
    views.forView(ActivityView).ask(byPlayer, limit, "player" -> playerId, "category" -> category.getOrElse(""),
      "offset" -> offset.max(0).toString)
