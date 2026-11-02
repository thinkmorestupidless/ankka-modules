package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.player.PlayerRewardsState
import io.eitheror.casino.rewards.steps.{CancelRounds, FreeRounds, FreeRoundsFlow}

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
