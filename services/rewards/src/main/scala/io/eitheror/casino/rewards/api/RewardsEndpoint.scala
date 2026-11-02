package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.{Called, OpenRecord}
import io.eitheror.casino.rewards.offers.{Offer, OfferBookState}
import io.eitheror.casino.rewards.player
import io.eitheror.casino.rewards.player.{Flows, HeldReward, Outcome, PlayerRewards, PlayerRewardsState}
import io.eitheror.casino.rewards.reads.RewardsView
import io.eitheror.casino.rewards.steps.{StepRequest, StepState, WalletAnswer}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/rewards` (contracts/routes.md): the player's own rewards, `{playerId}` the token's
 * (`player_id_mismatch` otherwise). A selection is decided on `PlayerRewards`; a claim goes to the wallet through
 * `WalletStep(<reward>/award)`, whose state the route polls up to the request bound, as payouts' request route, and
 * is 503 past it (the award goes on and lands on the reward). A deposit offer's selection is pending until the
 * deposit (`MoneyConsumer`) or its `pending-<reward>` timer (`RewardClocks`).
 */
final class RewardsEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/rewards"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private def mine(playerId: String)(f: => Reply): Reply =
    if !Routes.playerId(principal).contains(playerId) then notYours else f

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  get("/{playerId}/state") { (p: String) => mine(p)(ok(PlayerState.of(ctx, p))) }

  postBody("/{playerId}/select") { (p: String, text: String) => mine(p)(body(text)(Selection.select(ctx, p, _))) }

  post("/{playerId}/deselect") { (p: String) => mine(p)(Selection.deselect(ctx, p)) }

  // The lifecycle (US4): the cancel's body may be empty, which cancels the primary.
  postBody("/{playerId}/cancel") { (p: String, text: String) =>
    mine(p)(body(if text.isBlank then "{}" else text)(PlayerLifecycle.cancel(ctx, p, _)))
  }

  postBody("/{playerId}/switch") { (p: String, text: String) => mine(p)(body(text)(PlayerLifecycle.switch(ctx, p, _))) }

  get("/{playerId}/history") { (p: String) => mine(p)(PlayerLifecycle.history(ctx, p)) }

  postBody("/{playerId}/codes") { (p: String, text: String) => mine(p)(body(text)(Codes.enter(ctx, p, _))) }

  postBody("/{playerId}/codes/preview") { (p: String, text: String) => mine(p)(body(text)(Codes.preview(ctx, _))) }

  // Free rounds (US5): the player's own, and `game-provider`'s read at a launch, by identity (FR-022b).
  get("/{playerId}/free-rounds") { (p: String) => mine(p)(FreeRoundsReads.list(ctx, p, query.optional[String]("currency"))) }

  withAcl(Routes.serviceOf("game-provider")) {
    get("/{playerId}/free-rounds/eligible") { (p: String) =>
      FreeRoundsReads.eligible(ctx, p, query.optional[String]("provider"), query.optional[String]("gameCode"))
    }
  }

  // FR-022a: the player's grants, to the player and to `support-agent` by its identity (US6).
  withAcl(PlayerGrants.acl(ctx)) {
    get("/{playerId}/grants") { (p: String) =>
      if PlayerGrants.supportAgent(caller) then PlayerGrants.list(ctx, p) else mine(p)(PlayerGrants.list(ctx, p))
    }
  }

/** The selection routes' work (FR-011, FR-013). */
object Selection:
  private def refuse(code: String): Reply = Answers.Rewards.refuse(code)

  def select(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    b.text("offerId").filter(_.nonEmpty) match
      case None => refuse("offerId required")
      case Some(offerId) =>
        val now = ctx.now()
        ctx.offers.offer(offerId).filter(_.live(now)) match
          case None        => refuse(PlayerRewards.OfferNotVisible)
          case Some(offer) => selectOffer(ctx, playerId, offer)

  /**
   * The player's selection of a live offer they hold a reward of. A code's entry selects through here too, answering
   * its own shapes: `declined` for a decline or the wallet's refusal, `success` in place of the selection's answer.
   */
  def selectOffer(ctx: Ctx, playerId: String, offer: Offer, declined: String => Reply = refusal(409, _),
      success: Option[Reply] = None): Reply =
    val now   = ctx.now()
    val state = ctx.playerState(playerId)
    val held  = state.unclaimed(offer.id)
    currency(ctx, offer, state, held.map(_._1)) match
      case None if held.isDefined && offer.noDepositRequired => refuse("player_currency_unresolved")
      case resolved =>
        // With no reward to select, the entity refuses before it reads the currency.
        val walletCurrency = resolved.orElse(offer.currency).getOrElse(Currency.unsafe("EUR"))
        val open = if offer.noDepositRequired then Flows.walletOpen(ctx.wallet, playerId, walletCurrency) else 0
        val answer = ctx.player(playerId).call(PlayerRewards.select).invoke(player.Select(offer, open, walletCurrency, now))
        answer.outcome match
          case Outcome.Refused(code)      => refuse(code)
          case Outcome.Declined(_, reason) => declined(reason)
          case o @ Outcome.Selected(id) =>
            Flows.act(ctx.components, playerId, Some(offer), o, now): Unit
            success.getOrElse {
              val expiresAt = ctx.rewardState(id).expiresAt
              ok(Json.obj("ok" -> true, "expiresAt" -> expiresAt.map(_.toString)))
            }
          case o: Outcome.Claimed =>
            Flows.act(ctx.components, playerId, Some(offer), o, now).fold(unavailable)(awarded(ctx, _, declined, success.getOrElse(okTrue)))
          case _ => success.getOrElse(okTrue)

  /** FR-007's wallet currency for the offer and the player as they stand, the euro when none is known. */
  def walletCurrency(ctx: Ctx, offer: Offer, playerId: String): Currency =
    val state = ctx.playerState(playerId)
    currency(ctx, offer, state, state.unclaimed(offer.id).map(_._1)).getOrElse(Currency.unsafe("EUR"))

  /**
   * The player's wallet currency (FR-007): the offer's, else the player's as last seen, else the one the reward was
   * opened with when it was made visible.
   */
  private def currency(ctx: Ctx, offer: Offer, state: PlayerRewardsState, rewardId: Option[String]): Option[Currency] =
    offer.currency
      .orElse(state.currency.flatMap(c => Currency.from(c).toOption))
      .orElse(rewardId.map(ctx.rewardState).map(_.currency).filter(_.nonEmpty).flatMap(c => Currency.from(c).toOption))

  /** The award step's answer, waited for up to the request bound: awarded is `{ok: true}`, the wallet's refusal 409. */
  def awarded(ctx: Ctx, r: StepRequest, refused: String => Reply = refusal(409, _), done: Reply = okTrue): Reply =
    val until = System.nanoTime() + ctx.settings.requestBound.toNanos
    def poll(): Option[StepState] =
      val s = ctx.step(r.id)
      if s.phase == StepState.Done then Some(s)
      else if System.nanoTime() > until then None
      else { Thread.sleep(10); poll() }
    poll().flatMap(_.answer) match
      case Some(WalletAnswer.Awarded(_))           => done
      case Some(WalletAnswer.Refused(_, code, _))  => refused(code)
      case Some(_)                                 => done
      case None                                    => unavailable

  def deselect(ctx: Ctx, playerId: String): Reply =
    val now = ctx.now()
    val answer = ctx.player(playerId).call(PlayerRewards.deselect).invoke(player.Deselect(now))
    Flows.act(ctx.components, playerId, None, answer.outcome, now): Unit
    okTrue

/**
 * `GET /{playerId}/state` (contracts/routes.md): `active` when the wallet holds open records for the player, the
 * primary's fields at the top; else `pending` with the selection and the offers it competes with; else `available`
 * with the offers the player may select, in the brand's order. A wallet that cannot be read degrades to the next.
 */
object PlayerState:
  def of(ctx: Ctx, playerId: String): JsonNode =
    val state = ctx.playerState(playerId)
    val book  = ctx.offers
    val now   = ctx.now()
    val selectable = inBrandOrder(book, state.selectable).filter((_, _, o) => o.live(now))
    active(ctx, playerId, state, book).getOrElse {
      state.pending.flatMap((id, held) => book.offer(held.offerId).map(o => (id, held, o))) match
        case Some((id, held, offer)) =>
          val reward = ctx.rewardState(id)
          Json.obj("state" -> "pending",
            "selected" -> withFields(summary(offer, held), "selectedAt" -> held.selectedAt.map(_.toString),
              "expiresAt" -> reward.expiresAt.map(_.toString)),
            "competingOffers" -> Json.arr(selectable.filterNot(_._1 == id).map((_, h, o) => summary(o, h))*))
        case None =>
          Json.obj("state" -> "available", "visibleOffers" -> Json.arr(selectable.map((_, h, o) => summary(o, h))*))
    }

  /** The selectable rewards with their offers, in the brand's order, an offer the book no longer holds left out. */
  private def inBrandOrder(book: OfferBookState, held: Vector[(String, HeldReward)]): Vector[(String, HeldReward, Offer)] =
    val rank = book.order.zipWithIndex.toMap
    held.flatMap((id, h) => book.offer(h.offerId).map(o => (id, h, o))).sortBy((_, h, o) => (rank.getOrElse(o.id, Int.MaxValue), h.openedAt))

  private def active(ctx: Ctx, playerId: String, state: PlayerRewardsState, book: OfferBookState): Option[JsonNode] =
    walletCurrency(ctx, playerId, state).flatMap { currency =>
      ctx.wallet.restrictedFunds(playerId, currency) match
        case Called.Done(funds) if funds.records.nonEmpty =>
          val bonuses = funds.records.map(record(_, currency.code, book))
          val primary = bonuses.head
          val top     = Json.obj("state" -> "active", "activeBonuses" -> Json.arr(bonuses*))
          primary.fieldNames().forEachRemaining { f =>
            if f != "recordId" && f != "grantRef" then top.set[JsonNode](f, primary.get(f)): Unit
          }
          Some(top)
        case _ => None
    }

  /** The player's wallet currency as last seen, else the currency of the player's newest reward. */
  private def walletCurrency(ctx: Ctx, playerId: String, state: PlayerRewardsState): Option[Currency] =
    state.currency
      .orElse(RewardsView.byPlayer(ctx.clients.viewClient.forView(RewardsView), playerId, 1).headOption.map(_.currency))
      .filter(_.nonEmpty)
      .flatMap(c => Currency.from(c).toOption)

  private def record(r: OpenRecord, currency: String, book: OfferBookState): com.fasterxml.jackson.databind.node.ObjectNode =
    Json.obj("recordId" -> r.recordId, "grantRef" -> r.grantRef, "bonusBalance" -> r.balance, "currency" -> currency,
      "wageringRequirement" -> r.wageringRequirement, "wageredAmount" -> r.releaseProgress, "maxWin" -> r.maxConvertible,
      "offer" -> book.offer(r.bonusRef).map(summary(_, HeldReward(r.bonusRef))))

  /** The offer as the player reads it (contracts/routes.md's offer summary); amounts are minor units. */
  def summary(o: Offer, held: HeldReward): com.fasterxml.jackson.databind.node.ObjectNode =
    Json.obj("offerId" -> o.id, "role" -> o.role, "source" -> held.source.wire, "name" -> o.name, "description" -> o.description,
      "claimWindowDays" -> o.claimWindowDays, "type" -> o.offerType.wire, "bonusPercentage" -> o.bonusPercentage,
      "maxBonusValue" -> o.maxBonusValue, "minDepositAmount" -> o.minDepositAmount, "wageringMultiplier" -> o.wageringMultiplier,
      "wageringWindowDays" -> o.wageringWindowDays, "noDepositRequired" -> o.noDepositRequired)

  private def withFields(o: com.fasterxml.jackson.databind.node.ObjectNode, fields: (String, Any)*) =
    fields.foreach((k, v) => Json.set(o, k, v))
    o
