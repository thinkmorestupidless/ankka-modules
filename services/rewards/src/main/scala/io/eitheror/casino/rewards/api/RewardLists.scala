package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.clients.{Called, OpenRecord}
import io.eitheror.casino.rewards.reads.{RewardRow, RewardsView}
import io.eitheror.casino.rewards.reward.{RewardState, RewardStatus}

import java.time.Instant

import Answers.*

/**
 * The operator's reads of the brand's rewards (FR-017; contracts/routes.md `GET /reward-grants`, `GET /{rewardId}`):
 * the brand's list from `RewardsView`, filtered and paged, newest first; a player's every reward, with the wallet's
 * open records for the primary and the wagering; one reward with its free-rounds part. Amounts are minor units
 * (FR-032). No personal value is answered: the player is named by id, and a revoke's reason is the audit's alone.
 */
object RewardLists:
  final case class Query(search: Option[String], status: Option[String], offerId: Option[String], playerId: Option[String],
      page: Option[String], pageSize: Option[String])

  private def at(i: Option[Instant]): Any = i.map(_.toString).orNull

  /** `pageSize` from 1 to 200, 25 when absent (`BR:3888`); outside it, or not a number, is 400 naming it. */
  def pageSize(raw: Option[String]): Option[Int] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None    => Some(RewardsView.DefaultPageSize)
      case Some(t) => t.toIntOption.filter(RewardsView.validPageSize)

  def list(ctx: Ctx, q: Query): Reply =
    pageSize(q.pageSize) match
      case None => Grants.refuse("pageSize", "message" -> s"pageSize must be a whole number from 1 to ${RewardsView.MaxPageSize}")
      case Some(size) =>
        val page   = q.page.flatMap(_.trim.toIntOption).filter(_ >= 1).getOrElse(1)
        val clean  = (o: Option[String]) => o.map(_.trim).filter(_.nonEmpty)
        val filter = RewardsView.Filter(clean(q.search), clean(q.status), clean(q.offerId), clean(q.playerId))
        val view   = ctx.clients.viewClient.forView(RewardsView)
        filter.playerId match
          case Some(p) => ok(ofPlayer(ctx, p, RewardsView.page(view, filter, 1, RewardsView.MaxPageSize)))
          case None =>
            val found = RewardsView.page(view, filter, page, size)
            ok(Json.obj("items" -> Json.arr(found.rows.map(r => item(r, rounds(ctx, r)))*), "total" -> found.total,
              "page" -> found.page, "pageSize" -> found.pageSize))

  /**
   * Every reward of the player, newest first, with `isPrimary` (the wallet's first active record; null for free rounds,
   * or when the wallet cannot be read, as the original's) and the wagering from the reward's open record when it has
   * one. `unmatchedRounds` are the player's rounds naming a reward this service does not know (task T055, US5).
   */
  private def ofPlayer(ctx: Ctx, playerId: String, found: RewardsView.Page): ObjectNode =
    val currency = ctx.playerState(playerId).currency.orElse(found.rows.headOption.map(_.currency)).filter(_.nonEmpty)
      .flatMap(c => Currency.from(c).toOption)
    val records: Option[Vector[OpenRecord]] = currency.flatMap(c =>
      ctx.wallet.restrictedFunds(playerId, c) match
        case Called.Done(funds) => Some(funds.records)
        case _                  => None)
    val primary = records.map(_.find(_.status.equalsIgnoreCase("active")).map(_.grantRef))
    val byGrant = records.getOrElse(Vector.empty).map(r => r.grantRef -> r).toMap
    val items = found.rows.map { r =>
      val o    = item(r, rounds(ctx, r))
      val open = byGrant.get(r.rewardId)
      Json.set(o, "isPrimary", if r.freeRounds.isDefined then null else primary.fold[Any](null)(_.contains(r.rewardId)))
      open.foreach(rec => Json.set(o, "wageringRequirement", rec.wageringRequirement))
      Json.set(o, "wageredAmount", open.fold[Any](null)(_.releaseProgress))
      o
    }
    Json.obj("items" -> Json.arr(items*), "total" -> found.total, "page" -> 1, "pageSize" -> items.size,
      "unmatchedRounds" -> Json.arr())

  /** The reward's own state, read for a free-rounds row only, for its rounds' part. */
  private def rounds(ctx: Ctx, r: RewardRow): Option[RewardState] =
    Option.when(r.freeRounds.isDefined)(ctx.rewardState(r.rewardId)).filter(_.isFreeRounds)

  /** A row as the list answers it; free rounds are valued at their rounds (`BR:3765-3777`). */
  def item(r: RewardRow, s: Option[RewardState]): ObjectNode =
    val spins = s.flatMap(freeSpins)
    Json.obj("rewardId" -> r.rewardId, "playerId" -> r.playerId, "offerId" -> r.offerId, "offerName" -> r.offerName,
      "type" -> r.offerType, "status" -> r.status, "faceValue" -> s.flatMap(roundsValue).getOrElse(r.faceValue),
      "freeSpins" -> spins.orNull, "currency" -> r.currency, "wageringRequirement" -> r.wageringRequirement,
      "wageredAmount" -> null, "grantedAt" -> at(r.grantedAt), "expiresAt" -> at(r.expiresAt), "source" -> r.source)

  /** Free rounds' worth: the rounds at their spin value while they are played, what they won once they are paid. */
  private def roundsValue(s: RewardState): Option[Long] =
    s.freeRounds.map(f => if s.status == RewardStatus.Closed then f.tally.paid.getOrElse(f.tally.accumulated)
      else f.terms.spinValue * f.terms.spinCount)

  /** The free-rounds part (`BR:3758-3778`): the game, the rounds used of the count, the spin value, the winnings. */
  def freeSpins(s: RewardState): Option[ObjectNode] =
    s.freeRounds.map { f =>
      Json.obj("grantId" -> s.rewardId, "gameId" -> f.terms.gameId, "provider" -> f.terms.provider, "spinCount" -> f.terms.spinCount,
        "spinsUsed" -> f.tally.played, "spinValue" -> f.terms.spinValue, "winnings" -> f.tally.accumulated,
        "grantStatus" -> (if s.status == RewardStatus.Active then "active" else Revocations.grantStatus(s.status)),
        "providerStatus" -> f.providerStatus, "faceValue" -> roundsValue(s))
    }

  /** `GET /{rewardId}`: the reward with its free-rounds part; one this service does not hold is `CLAIM_NOT_FOUND`. */
  def one(ctx: Ctx, rewardId: String): Reply =
    val s = ctx.rewardState(rewardId)
    if !s.opened then Grants.refuse("CLAIM_NOT_FOUND")
    else
      ok(Json.obj("rewardId" -> s.rewardId, "playerId" -> s.playerId, "offerId" -> s.offerId, "offerName" -> s.offerName,
        "type" -> s.offerType, "payloadType" -> s.payloadType.wire, "status" -> s.status.wire,
        "faceValue" -> roundsValue(s).getOrElse(s.faceValue), "wageringRequirement" -> s.wageringRequirement,
        "wageringMultiplier" -> s.wageringMultiplier, "maxConvertibleAmount" -> s.maxConvertible, "currency" -> s.currency,
        "priority" -> s.priority, "source" -> s.source.wire, "code" -> s.code, "recordId" -> s.recordId,
        "openedAt" -> s.openedAt.toString, "selectedAt" -> at(s.selectedAt), "grantedAt" -> at(s.grantedAt),
        "expiresAt" -> at(s.expiresAt), "wageringExpiresAt" -> at(s.wageringExpiresAt), "capExpiresAt" -> at(s.capExpiresAt),
        "convertedAt" -> at(s.convertedAt), "terminatedAt" -> at(s.terminatedAt), "terminationReason" -> s.terminationReason.orNull,
        "lastDecline" -> s.lastDecline.orNull, "freeSpins" -> freeSpins(s).orNull))
