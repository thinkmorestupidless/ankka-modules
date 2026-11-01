package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.{BonusStatus, Called, Wallet}
import io.eitheror.casino.rewards.reward.{Revoke, Reward, RewardCodes, RewardState, RewardStatus, RevokeMode}
import io.eitheror.casino.rewards.steps.{StepRequest, WalletCall, Wiring}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/reward-grants` (contracts/routes.md): the brand's rewards for its operators. The ACL is
 * the brand's operators; every route but the list checks the three admin roles in the handler (`forbidden`).
 */
final class RewardAdminEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/reward-grants"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private def admin(f: => Reply): Reply = if principal.roles.exists(Answers.AdminRoles) then f else forbidden
  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  postBody("/{rewardId}/revoke") { (id: String, text: String) =>
    admin(body(text)(Revocations.revoke(ctx, id, _, principal.subject)))
  }

/**
 * An operator's revoke (FR-017; `BR:769-1130`), in the original's order: the mode, then the reward (one this service
 * does not hold, another brand's among them, is `CLAIM_NOT_FOUND`), then free rounds or funds. Funds must be `ACTIVE`
 * (the original read the record's own status at the wallet, so a pending cap is `NO_ACTIVE_BONUS`); `forfeit` is the
 * wallet's forfeit, nothing clawed back; `reclaim` its cancel with `requireBaseline`, refused `MISSING_BASELINE`.
 * Free rounds are cancelled at the provider first: only no answer refuses (`PROVIDER_UNAVAILABLE`); once played out
 * they are `FREE_SPINS_FINISHED`. The reward is revoked once the wallet or the provider answered, with the reason.
 */
object Revocations:
  private def refuse(code: String, fields: (String, Any)*): Reply = Answers.Grants.refuse(code, fields*)

  def revoke(ctx: Ctx, rewardId: String, b: JsonNode, by: String): Reply =
    b.text("mode").flatMap(RevokeMode.from) match
      case None => refuse("INVALID_MODE")
      case Some(mode) =>
        val s = ctx.rewardState(rewardId)
        val reason = b.text("reason").filter(_.trim.nonEmpty).map(Personal(_))
        if !s.opened then refuse(RewardCodes.NotFound)
        else if s.isFreeRounds then freeRounds(ctx, s, mode, by, reason)
        else funds(ctx, s, mode, by, reason)

  private def funds(ctx: Ctx, s: RewardState, mode: RevokeMode, by: String, reason: Option[Personal[String]]): Reply =
    if s.status != RewardStatus.Active then refuse(Wallet.NoActiveBonus)
    else
      StepCalls.run(ctx, StepRequest(s.rewardId, s.playerId, s.currency, WalletCall.Revoke(mode))) match
        case None => unavailable
        case Some(answer) =>
          Wiring.revoked(mode, by, reason, answer, ctx.now()) match
            case Left(code @ (Wallet.NoActiveBonus | Wallet.MissingBaseline)) => refuse(code)
            case Left("GRANT_NOT_OPEN")                                       => refuse(Wallet.NoActiveBonus)
            case Left(_)                                                      => unavailable
            case Right((command, realBalance)) =>
              recorded(ctx, s.rewardId, command) {
                ok(Json.obj("status" -> "revoked", "mode" -> mode.wire, "clawbackApplied" -> command.clawback,
                  "newRealBalance" -> realBalance))
              }

  private def freeRounds(ctx: Ctx, s: RewardState, mode: RevokeMode, by: String, reason: Option[Personal[String]]): Reply =
    if s.status != RewardStatus.Active then refuse("FREE_SPINS_FINISHED", "grantStatus" -> grantStatus(s.status))
    else
      ctx.gameProvider.cancel(s.rewardId, s.playerId) match
        case Called.Unreachable(_) | Called.Done(BonusStatus.Error) => refuse("PROVIDER_UNAVAILABLE")
        case _ =>
          // Whatever else the provider answered, the spins are taken away here (`BR:883-893`); nothing was paid.
          recorded(ctx, s.rewardId, Revoke(mode, by, reason, 0L, ctx.now())) {
            ok(Json.obj("status" -> "revoked", "mode" -> mode.wire, "clawbackApplied" -> 0L, "freeSpins" -> true))
          }

  /** The revoke recorded on the reward; a reward that moved meanwhile is `NO_ACTIVE_BONUS`, as the original's. */
  private def recorded(ctx: Ctx, rewardId: String, command: Revoke)(answer: => Reply): Reply =
    ctx.reward(rewardId).call(Reward.revoke).invoke(command).refusal.fold(answer)(_ => refuse(Wallet.NoActiveBonus))

  /** The original grant's status for rounds no longer live (`BR:850-866`). */
  def grantStatus(status: RewardStatus): String = status match
    case RewardStatus.Closed    => "used"
    case RewardStatus.Expired   => "expired"
    case RewardStatus.Cancelled => "provider_failed"
    case RewardStatus.Revoked   => "revoked"
    case other                  => other.wire.toLowerCase
