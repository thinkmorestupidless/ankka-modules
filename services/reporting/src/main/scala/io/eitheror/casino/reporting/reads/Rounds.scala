package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, ProviderFigures, RoundCheck, RoundCheckState, RoundChecks, WalletFigures}

/**
 * A round as the provider and the wallet each saw it (data-model.md `Rounds`), with its status (`matched`, `differs`,
 * `provider_only`, `wallet_only`) as a column, so the day-end lists the unmatched without reading every round. The
 * game, its session and its settlement time are columns too, for the rankings' game names and the dispute report.
 */
final case class RoundRow(
    generation: Int,
    player: String = "",
    currency: String = "",
    roundId: String = "",
    provider: Option[ProviderFigures] = None,
    wallet: Option[WalletFigures] = None,
    day: String = "",
    status: String = "",
    game: Option[String] = None,
    session: Option[String] = None
)

object RoundRow:
  given Serializer[RoundRow] = ReportingCodecs.row[RoundRow]("round-row")

  def of(generation: Int, s: RoundCheckState): RoundRow =
    RoundRow(generation, s.player, s.currency, s.roundId, s.provider, s.wallet, s.day, s.status, s.provider.map(_.game),
      s.provider.map(_.session))

/** A key value entity's state is delivered at least once; the row is the state, so a repeat writes the same row. */
final class RoundRows extends View[RoundCheckState, RoundRow]:
  def onChange(state: RoundCheckState): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore())(g => effects.updateRow(RoundRow.of(g, state)))

object Rounds extends View.Companion[RoundRows, RoundCheckState, RoundRow](
      componentId = ComponentId("rounds"),
      source = ChangeSource.stateOf(RoundCheck),
      rowSerializer = RoundRow.given_Serializer_RoundRow
    ):
  /** The rounds the provider settled under one game session (the dispute report, FR-016). */
  val byGameSession = query("by-game-session")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'session' = :session ORDER BY payload::jsonb->'provider'->>'at', row_key")

  /** A reporting day's rounds. */
  val byDay = query("by-day")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'day' = :day ORDER BY row_key")

  /** A reporting day's rounds whose two sides differ or that one side lacks (FR-018's differences). */
  val unmatchedOn = query("unmatched-on")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'day' = :day AND payload::jsonb->>'status' <> '${RoundChecks.Matched}' ORDER BY row_key")

  /** The last round the provider settled of a game, which names it (FR-014's rankings). */
  val latestForGame = query("latest-for-game")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'game' = :game ORDER BY (payload::jsonb->'provider'->>'at')::timestamptz DESC, row_key LIMIT 1")

  /**
   * A player's rounds the wallet settled and the provider's record lacks, by the wallet's first leg from `from`
   * (inclusive) to `to` (exclusive): the dispute report includes them, unmatched (FR-017).
   */
  val walletOnlyOf = query("wallet-only-of")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'player' = :player AND payload::jsonb->>'status' = '${RoundChecks.WalletOnly}' AND (payload::jsonb->'wallet'->>'at')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->'wallet'->>'at')::timestamptz < (:to)::timestamptz ORDER BY (payload::jsonb->'wallet'->>'at')::timestamptz, row_key")

  def create(ctx: ViewComponentContext) = new RoundRows

  private val Every = 10_000_000

  /** A reporting day's rounds, by the round's day (research R7). */
  def ofDay(views: ViewClient, generation: Int, day: String): Vector[RoundRow] =
    views.forView(Rounds).ask(byDay, Every, "generation" -> generation.toString, "day" -> day)

  def ofGameSession(views: ViewClient, generation: Int, session: String): Vector[RoundRow] =
    views.forView(Rounds).ask(byGameSession, Every, "generation" -> generation.toString, "session" -> session)

  def walletOnly(views: ViewClient, generation: Int, player: String, from: java.time.Instant, to: java.time.Instant): Vector[RoundRow] =
    views.forView(Rounds).ask(walletOnlyOf, Every, "generation" -> generation.toString, "player" -> player, "from" -> from.toString,
      "to" -> to.toString)

  def latest(views: ViewClient, generation: Int, game: String): Option[RoundRow] =
    views.forView(Rounds).ask(latestForGame, 1, "generation" -> generation.toString, "game" -> game).headOption
