package io.eitheror.casino.rewards.api

import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, Principal, RequestContext}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.clients.Called
import io.eitheror.casino.rewards.reads.RewardsView

import Answers.*

/**
 * `GET /rewards/{playerId}/grants` (FR-022a; contracts/routes.md): every reward of the player, newest first, with its
 * state, offer name, wagering requirement, release progress and expiry, served to the player (their own) and to
 * this project's `support-agent` by its identity (spec 017 FR-009 `get_active_bonuses`, which listed every bonus
 * of the player whatever its state). The wagering is the wallet's open record's when the reward has one, else the
 * reward's own requirement with no progress.
 */
object PlayerGrants:
  /** The service that reads a player's grants by its identity (research R7). */
  val SupportAgent = "support-agent"

  /** This project's `support-agent`, as `Routes.serviceOf` admits it: another brand's runs its own and is not let in. */
  def supportAgent(ctx: Ctx, c: Caller): Boolean = c match
    case Caller.Service(project, SupportAgent) => project == ctx.ownProject
    case _                                     => false

  /**
   * The endpoint's player ACL, and `Routes.serviceOf("support-agent")` beside it: this project's `support-agent` is
   * admitted by its identity with no token; every other caller is the player's ACL's to decide.
   */
  def acl(ctx: Ctx): Acl =
    Routes.player(ctx.slug, ctx.authenticate()) match
      case Acl.Authenticate(player) =>
        Acl.Authenticate { (context: RequestContext) =>
          if supportAgent(ctx, context.caller) then AuthDecision.Allow(Principal(s"service:${ctx.ownProject}/$SupportAgent"))
          else player(context)
        }
      case other => other

  def list(ctx: Ctx, playerId: String): Reply =
    val rows     = RewardsView.byPlayer(ctx.clients.viewClient.forView(RewardsView), playerId)
    val currency = ctx.playerState(playerId).currency.orElse(rows.headOption.map(_.currency)).filter(_.nonEmpty)
      .flatMap(c => Currency.from(c).toOption)
    val open = currency.map(c => ctx.wallet.restrictedFunds(playerId, c)).collect { case Called.Done(f) => f.records }
      .getOrElse(Vector.empty).map(r => r.grantRef -> r).toMap
    ok(Json.arr(rows.map { r =>
      val record = open.get(r.rewardId)
      Json.obj("rewardId" -> r.rewardId, "offerName" -> r.offerName, "status" -> r.status,
        "wageringRequirement" -> record.fold(r.wageringRequirement)(_.wageringRequirement),
        "releaseProgress" -> record.fold(0L)(_.releaseProgress), "expiresAt" -> r.expiresAt.map(_.toString).orNull,
        "currency" -> r.currency)
    }*))
