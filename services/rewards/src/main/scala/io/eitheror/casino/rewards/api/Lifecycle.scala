package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.clients.{Called, Wallet}
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.reward.{RewardState, RewardStatus}
import io.eitheror.casino.rewards.steps.{StepRequest, StepState, WalletAnswer, WalletCall, WalletStep}

import Answers.*

/** A wallet step started from a route, and its answer waited for up to the request bound, as the award's (T036). */
object StepCalls:
  /** Starts `r` (a step already started answers its first state) and waits for its answer; none past the bound. */
  def run(ctx: Ctx, r: StepRequest): Option[WalletAnswer] =
    ctx.components.forWorkflow(EntityId(r.id)).call(WalletStep.start).invoke(r): Unit
    val until = System.nanoTime() + ctx.settings.requestBound.toNanos
    def poll(): Option[StepState] =
      val s = ctx.step(r.id)
      if s.phase == StepState.Done then Some(s)
      else if System.nanoTime() > until then None
      else { Thread.sleep(10); poll() }
    poll().flatMap(_.answer)

/**
 * The player's lifecycle routes (FR-016; contracts/routes.md): cancel, switch and history. The open rewards are the
 * wallet's open records whose reward is `ACTIVE`, read live, as the original read the wallet's status; a named reward
 * must be one of them. Every wallet command goes through `WalletStep` (research R5), whose answer moves the reward
 * (`Wiring`); the route answers from the step.
 */
object PlayerLifecycle:
  private def refuse(code: String): Reply = Answers.Rewards.refuse(code)

  /** The player's wallet currency: a named reward's, else the player's as last seen, else their newest reward's. */
  private def currency(ctx: Ctx, playerId: String, named: Option[RewardState]): Option[Currency] =
    named.filter(_.playerId == playerId).map(_.currency)
      .orElse(ctx.playerState(playerId).currency)
      .orElse(RewardsView.byPlayer(ctx.clients.viewClient.forView(RewardsView), playerId, 1).headOption.map(_.currency))
      .filter(_.nonEmpty)
      .flatMap(c => Currency.from(c).toOption)

  /** The player's rewards open at the wallet and `ACTIVE` here, in the wallet's order (the primary first). */
  private def active(ctx: Ctx, playerId: String, currency: Currency): Vector[RewardState] =
    ctx.wallet.restrictedFunds(playerId, currency) match
      case Called.Done(funds) =>
        funds.records.map(r => ctx.rewardState(r.grantRef)).filter(s => s.opened && s.playerId == playerId && s.status == RewardStatus.Active)
      case _ => Vector.empty

  /**
   * `POST /{playerId}/cancel` (`BR:2838-2998`): an explicit empty `grantRef` is `grant_not_found`, never the primary;
   * no open reward (or a wallet that cannot be read, as the original) is `no_active_bonus`; a named reward not among
   * them `grant_not_found`; else the wallet's cancel of the named reward or the primary, which computes the clawback.
   */
  def cancel(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    val sent = Option(b.get("grantRef")).filterNot(_.isNull)
    if sent.exists(n => n.isTextual && n.asText.isEmpty) then refuse("grant_not_found")
    else
      val grantRef = sent.filter(_.isTextual).map(_.asText)
      val named    = grantRef.map(ctx.rewardState)
      currency(ctx, playerId, named).map(c => c -> active(ctx, playerId, c)) match
        case None                             => refuse("no_active_bonus")
        case Some((_, open)) if open.isEmpty  => refuse("no_active_bonus")
        case Some((c, open)) =>
          grantRef.fold(open.headOption)(g => open.find(_.rewardId == g)) match
            case None => refuse("grant_not_found")
            case Some(target) =>
              StepCalls.run(ctx, StepRequest(target.rewardId, playerId, c.code, WalletCall.Cancel())) match
                case Some(WalletAnswer.Cancelled(done)) =>
                  ok(Json.obj("status" -> "canceled", "clawbackAmount" -> done.clawbackAmount,
                    "newRealBalance" -> done.balances.realBalance))
                case Some(WalletAnswer.Refused(_, Wallet.NoActiveBonus, _)) => refuse("no_active_bonus")
                case _                                                     => refuse("cancel_failed")

  /**
   * `POST /{playerId}/switch` (`BR:3005-3065`): the wallet's switch of a reward the player holds, step `switch-<n>`
   * for the reward's next switch; a refusal is `switch_failed`. A reward of another player is refused before any step
   * is started under its id.
   */
  def switch(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    Option(b.get("grantRef")).filter(_.isTextual).map(_.asText) match
      case None => refuse("grantRef_required")
      case Some(g) =>
        val s = ctx.rewardState(g)
        if !s.opened || s.playerId != playerId then refuse("switch_failed")
        else
          StepCalls.run(ctx, StepRequest(g, playerId, s.currency, WalletCall.Switch(s.switches + 1))) match
            case Some(WalletAnswer.Switched(_))   => okTrue
            case Some(WalletAnswer.Refused(_, _, _)) => refuse("switch_failed")
            case _                                => unavailable

  /** `GET /{playerId}/history`: the player's closed rewards, the newest 20 by when they closed. */
  def history(ctx: Ctx, playerId: String): Reply =
    val rows = RewardsView.history(ctx.clients.viewClient.forView(RewardsView), playerId)
    ok(Json.arr(rows.map(r => Json.obj("grantRef" -> r.rewardId, "faceValue" -> r.faceValue, "currency" -> r.currency,
      "status" -> r.status, "grantedAt" -> r.grantedAt.map(_.toString), "closedAt" -> r.closedAt.map(_.toString)))*))
