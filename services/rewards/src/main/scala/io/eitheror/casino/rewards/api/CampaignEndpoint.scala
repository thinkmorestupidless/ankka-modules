package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.eligibility.{Deposit, Evaluator}
import io.eitheror.casino.rewards.offers.{Offer, OfferBookState, PayloadType}
import io.eitheror.casino.rewards.player.{ClaimOffer, Flows, Openings, Outcome, PlayerRewards, PlayerRewardsState, Target}
import io.eitheror.casino.rewards.api.FreeRoundsGrants.{How, Result}
import io.eitheror.casino.rewards.reward.{Open, Reward, RewardStatus, Source}
import io.eitheror.casino.rewards.steps.{Awards, WalletAnswer}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/reward-campaigns` (contracts/routes.md; FR-022): the brand's `engagement` targets an
 * offer at a player and awards one on a campaign's behalf, as the original's Customer.io webhook did (`CW:147-358`,
 * `CW:747-805`). Every refusal is 422 `{error}`, but `ELIGIBILITY_CHECK_UNAVAILABLE` (503, for the campaign to retry)
 * and the provider's rejection (502).
 */
final class CampaignEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/reward-campaigns"):
  val acl: Acl = Routes.serviceOf("engagement")

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  postBody("/{playerId}/target") { (p: String, text: String) => body(text)(Campaigns.target(ctx, p, _)) }

  postBody("/{playerId}/award") { (p: String, text: String) => body(text)(Campaigns.award(ctx, p, _)) }

/** The campaign routes' work, apart from the endpoint so a suite can read it. */
object Campaigns:
  private def refuse(code: String, fields: (String, Any)*): Reply = Answers.Campaigns.refuse(code, fields*)

  /**
   * A provisional reward's currency when neither the offer nor the player names one: the award resizes it in the
   * currency it is awarded in, as the selection's euro does (`Selection.walletCurrency`).
   */
  private val ProvisionalCurrency = Currency.unsafe("EUR")

  val AlreadyProcessed: Reply = ok(Json.obj("ok" -> true, "alreadyProcessed" -> true))

  /** Rounds are granted, never targeted (`CW:759-771`): a free-rounds offer, or a deposit match with rounds. */
  def targetable(o: Offer): Boolean =
    !(o.payload == PayloadType.FreeSpins || o.payload == PayloadType.DepositMatchWithFreeSpins)

  /**
   * `POST /{playerId}/target`: the brand's active offer holding the role made visible to the player, source
   * `customerio_webhook`; a repeat while a reward of it is available or pending changes nothing. The reward is opened
   * `AVAILABLE` at the offer's fixed amount, in the offer's currency, else the player's as last seen; it is resized at
   * its award. The campaign's `context` is not kept.
   */
  def target(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    b.text("role").flatMap(ctx.offers.byRole) match
      case None                          => refuse("role_not_found")
      case Some(o) if !targetable(o)     => refuse("free_spins_offer_not_targetable")
      case Some(offer) =>
        val now    = ctx.now()
        val answer = ctx.player(playerId).call(PlayerRewards.target).invoke(Target(offer, Source.CustomerioWebhook, at = now))
        answer.outcome match
          case Outcome.Visible(id, _) =>
            // `Open` is a no-op on a reward already opened, so a target that failed half way is completed by its repeat.
            val currency = offer.currency.orElse(playerCurrency(answer.state)).getOrElse(ProvisionalCurrency)
            val opening  = Openings.provisional(offer, playerId, currency, Source.CustomerioWebhook, None, None)
            ctx.reward(id).call(Reward.open).invoke(Open(opening, RewardStatus.Available, now)): Unit
            okTrue
          case other => refuse("target_failed", "message" -> other.toString)

  /** The player's wallet currency as last seen (a deposit's, or the registration's). */
  def playerCurrency(s: PlayerRewardsState): Option[Currency] = s.currency.flatMap(c => Currency.from(c).toOption)

  /**
   * The offer the award names, else, when it is missing or inactive and a role is given, the brand's active offer of
   * the role, once (`CW:296-340`); else `offer_not_found` or `offer_not_active`.
   */
  def resolve(book: OfferBookState, offerId: String, role: Option[String]): Either[String, Offer] =
    book.offer(offerId) match
      case Some(o) if o.active => Right(o)
      case found =>
        val stale = if found.isEmpty then "offer_not_found" else "offer_not_active"
        role.flatMap(book.byRole).toRight(stale)

  /**
   * `POST /{playerId}/award` (FR-022): the offer resolved, then the player's claim, evaluated as a campaign's
   * (standard mode, the code taken as verified), on `PlayerRewards`. A claim the player already holds of the offer is
   * `{ok: true, alreadyProcessed: true}`; a decline its reason alone; a deposit match is sized from the player's last
   * confirmed deposit as `money.transactions` reported it, never from the caller. The award is the wallet's, waited
   * for up to the request bound: awarded is `{ok: true, bonusAmount}` (the amount awarded), the wallet's refusal or
   * no answer `grant_failed`, as the original's. Free rounds go to the grant path
   * (`FreeRoundsGrants.give`, T054), the code taken as verified; a claim lost to a repeat in flight is `alreadyProcessed`.
   */
  def award(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    resolve(ctx.offers, b.textOr("offerId", ""), b.text("role")) match
      case Left(code) => refuse(code)
      case Right(offer) =>
        val now   = ctx.now()
        val state = ctx.playerState(playerId)
        if offer.freeRounds then
          if Evaluator.alreadyClaimed(state.snapshot, offer.id) then AlreadyProcessed
          else freeRounds(FreeRoundsGrants.give(FreeRoundsGrants.env(ctx), playerId, offer,
            How.Claim(Source.CustomerioWebhook, codeVerified = true)))
        else
          val sent = b.text("currency").flatMap(c => Currency.from(c).toOption)
          offer.currency.orElse(sent).orElse(playerCurrency(state)) match
            case None => refuse("player_currency_unresolved")
            case Some(currency) =>
              val deposit = Option.unless(offer.noDepositRequired)(state.lastDeposit).flatten.map(d =>
                Deposit(d.amount, Currency.from(d.currency).getOrElse(currency), d.ordinal))
              val open   = Flows.walletOpen(ctx.wallet, playerId, currency)
              val answer = ctx.player(playerId).call(PlayerRewards.claim)
                .invoke(ClaimOffer(offer, operator = false, deposit, Source.CustomerioWebhook, None, None, open, currency, now,
                  codeVerified = true))
              answer.outcome match
                case Outcome.Refused(PlayerRewards.AlreadyClaimed) => AlreadyProcessed
                case Outcome.Declined(_, reason)                   => refuse(reason)
                case Outcome.Claimed(claim) =>
                  StepCalls.run(ctx, Awards.start(ctx.components, offer, playerId, claim, now)) match
                    case Some(WalletAnswer.Awarded(_)) => ok(Json.obj("ok" -> true, "bonusAmount" -> claim.decision.amount))
                    case _                            => refuse("grant_failed")
                case other => refuse("grant_failed", "message" -> other.toString)

  /**
   * The grant path's answer in the award route's shape: `{ok, grantId, providerStatus}`; a decline 422 `{error}`
   * (`ELIGIBILITY_CHECK_UNAVAILABLE` 503), the claim already held `alreadyProcessed`; the rest as the grant answers it.
   */
  def freeRounds(r: Result): Reply = r match
    case Result.Granted(id, status)                     => ok(Json.obj("ok" -> true, "grantId" -> id, "providerStatus" -> status))
    case Result.Declined(PlayerRewards.AlreadyClaimed)  => AlreadyProcessed
    case Result.Declined(reason)                        => refuse(reason)
    case other                                          => FreeRoundsGrants.refusal(other, Answers.Campaigns).getOrElse(unavailable)
