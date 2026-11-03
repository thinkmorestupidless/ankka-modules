package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.{Amounts as MoneyAmounts, Currency}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.clients.{Called, GameProvider}
import io.eitheror.casino.rewards.player.{PlayerRewards, PlayerRewardsState}
import io.eitheror.casino.rewards.reward.RewardStatus
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.steps.{CancelRounds, FreeRounds, FreeRoundsFlow}

import java.time.Instant

import Answers.*

/** An operator's revoke of free rounds asks `FreeRounds` to cancel them at the provider (research R8's `cancel`). */
object FreeRoundsCancel:
  /**
   * The provider's answer to the cancel, waited for up to the request bound: its status or code, or
   * `FreeRoundsFlow.Unavailable`; none past the bound. A cancel already answered answers the same.
   */
  def run(ctx: Ctx, rewardId: String, playerId: String): Option[String] =
    val flow = ctx.components.forWorkflow(EntityId(rewardId))
    flow.call(FreeRounds.cancel).invoke(CancelRounds(rewardId, playerId)): Unit
    val until = System.nanoTime() + ctx.settings.requestBound.toNanos
    def poll(): Option[String] =
      val s = flow.call(FreeRounds.status).invoke()
      if s.phase == FreeRoundsFlow.Cancelled && s.cancelled.isDefined then s.cancelled
      else if System.nanoTime() > until then None
      else { Thread.sleep(10); poll() }
    poll()

/**
 * The rounds that named free rounds this service does not hold, as the operator's list of a player's rewards shows them
 * (`GET /reward-grants?playerId=`, `unmatchedRounds`; the edge case "a round for a reward this service does not know").
 */
object UnmatchedRounds:
  def json(s: PlayerRewardsState): JsonNode =
    Json.arr(s.unmatchedRounds.map(r => Json.obj("roundId" -> r.roundId, "grantRef" -> r.grantRef, "winCents" -> r.winCents,
      "at" -> r.at.toString))*)

/** The free-rounds reads (contracts/routes.md; FR-022b, FR-026), apart from their endpoints so a suite can read them. */
object FreeRoundsReads:
  private def view(ctx: Ctx) = ctx.clients.viewClient.forView(RewardsView)

  /** `GET /rewards/{playerId}/free-rounds`: the player's active free rounds the provider has not failed, oldest first. */
  def list(ctx: Ctx, playerId: String, currency: Option[String]): Reply =
    val rows = RewardsView.liveFreeRounds(view(ctx), playerId, currency.map(_.trim.toUpperCase).filter(_.nonEmpty))
    ok(Json.arr(rows.flatMap(r => r.freeRounds.map(f => Json.obj("id" -> r.rewardId, "gameId" -> f.gameId,
      "gameName" -> Option(f.gameName).filter(_.nonEmpty), "spinsRemaining" -> f.spinsRemaining,
      "expiresAt" -> r.expiresAt.map(_.toString), "providerStatus" -> Option(f.providerStatus).filter(_.nonEmpty))))*))

  /**
   * `GET /rewards/{playerId}/free-rounds/eligible` (FR-022b): `game-provider`'s read at a launch naming no free rounds,
   * the player's oldest live free rounds on the game, or `no_eligible_grant`.
   */
  def eligible(ctx: Ctx, playerId: String, provider: Option[String], gameCode: Option[String]): Reply =
    gameCode.map(_.trim).filter(_.nonEmpty)
      .flatMap(g => eligibleNow(ctx, playerId, g, provider.map(_.trim).filter(_.nonEmpty)))
      .fold(Answers.Rewards.refuse("no_eligible_grant"))(id => ok(Json.obj("grantId" -> id)))

  /**
   * Read from the player's record, which holds a reward from its claim, and each reward, not from the view, which
   * trails them: a launch just after a grant would otherwise open a session without it, and the provider would play
   * its rounds as ordinary ones (T071). Only rewards that can be live are read: one being claimed, or held neither
   * ended nor unclaimed (an offer made visible and never claimed is no free rounds yet).
   */
  private def eligibleNow(ctx: Ctx, playerId: String, gameId: String, provider: Option[String]): Option[String] =
    val held = ctx.player(playerId).call(PlayerRewards.get).invoke().rewards
    held.collect { case (id, h) if h.claiming || !(h.status.isTerminal || h.unclaimed) => ctx.rewardState(id) }.toVector
      .filter(s => s.opened && s.playerId == playerId && s.status == RewardStatus.Active)
      .flatMap(s => s.freeRounds.map(s -> _))
      .filter((_, f) => f.terms.gameId == gameId && provider.forall(_ == f.terms.provider) &&
        !f.providerStatus.exists(RewardsView.FailedProviderStatuses.contains))
      .sortBy((s, _) => (s.grantedAt.getOrElse(Instant.MAX), s.rewardId))
      .headOption.map(_._1.rewardId)

  /**
   * `GET /offers/free-rounds/spin-values` (FR-026; `BR:2969-3020`): the provider's values for the game in the currency,
   * in minor units (FR-032), distinct and sorted, with the source; a brand with no free rounds set up is an empty list,
   * `not_configured`; a provider that does not answer is 502 `provider_unavailable`.
   */
  def spinValues(ctx: Ctx, gameId: Option[String], currency: Option[String]): Reply =
    val game = gameId.map(_.trim).filter(_.nonEmpty)
    val cur  = currency.map(_.trim.toUpperCase).filter(_.nonEmpty)
    (game, cur.flatMap(c => Currency.from(c).toOption)) match
      case (None, _) => refusal(400, "invalid_query", "field" -> "gameId", "message" -> "'gameId' is required")
      case (_, None) => refusal(400, "invalid_query", "field" -> "currency", "message" -> "'currency' is required, a currency code")
      case (Some(g), Some(c)) =>
        def answer(values: Vector[Long], source: String) =
          ok(Json.obj("gameId" -> g, "currency" -> c.code, "values" -> values, "source" -> source))
        ctx.gameProvider.spinValues(Vector(g), c) match
          case Called.Done(v) if !v.configured => answer(Vector.empty, "not_configured")
          case Called.Done(v) =>
            answer(v.values.flatMap(x => MoneyAmounts.parse(x.trim, c).toOption.map(_.value)).distinct.sorted, GameProvider.St8)
          case _ => Answers.Offers.refuse("provider_unavailable")
