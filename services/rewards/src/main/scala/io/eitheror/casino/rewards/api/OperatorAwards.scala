package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.eligibility.Deposit
import io.eitheror.casino.rewards.offers.{Offer, OfferType, WinningsDestination}
import io.eitheror.casino.rewards.player.{Claim, ClaimOffer, Flows, Outcome, PlayerRewards}
import io.eitheror.casino.rewards.api.FreeRoundsGrants.{How, Result}
import io.eitheror.casino.rewards.reward.{Opening, Source}
import io.eitheror.casino.rewards.steps.{Awards, WalletAnswer}

import Answers.*

/**
 * An operator's award to a player (FR-017; `BR:3177-3565`; contracts/routes.md `POST /reward-grants/{playerId}`) in its
 * three shapes: an offer, with an optional deposit to size it; a free bonus with no offer; free rounds with no offer.
 * Each is the operator's override (rules 1 to 4, FR-006) on `PlayerRewards`, then the wallet's award under the reward's
 * id, waited for up to the request bound; the reward names the operator, so the award is audited (`rewards.awarded`).
 * A decline, or the wallet's refusal, is `409 {status: "ineligible", reason}`; no answer `503 service_unavailable`.
 */
object OperatorAwards:
  private def refuse(code: String, fields: (String, Any)*): Reply = Answers.Grants.refuse(code, fields*)

  /** A free bonus's offer id, `manual:<uuid>`, and its type (FR-017). */
  val ManualType     = FreeRoundsGrants.ManualType
  val FreeBonusName  = "Free Bonus"
  val FreeRoundsName = "Free Spins"
  /** A free bonus's wagering window (FR-017). */
  val FreeBonusWindowDays = 30

  def award(ctx: Ctx, playerId: String, b: JsonNode, by: String): Reply =
    b.text("offerId") match
      case Some(offerId)                                  => offer(ctx, playerId, offerId, b, by)
      case None if b.text("type").contains("FREE_SPINS") => freeRounds(ctx, playerId, b, by)
      case None                                           => freeBonus(ctx, playerId, b, by)

  /** The player's wallet currency as last seen. */
  private def playerCurrency(ctx: Ctx, playerId: String): Option[Currency] =
    ctx.playerState(playerId).currency.flatMap(c => Currency.from(c).toOption)

  /**
   * An offer of the brand's, active or not, as the original's (`BR:3198`): in the offer's currency, else the player's;
   * the deposit, when given, sizes a match (none is the offer's fixed amount, FR-007). Free rounds go to the grant path.
   */
  private def offer(ctx: Ctx, playerId: String, offerId: String, b: JsonNode, by: String): Reply =
    ctx.offers.offer(offerId) match
      case None => refuse("offer_not_found")
      case Some(o) if o.freeRounds =>
        rounds(FreeRoundsGrants.give(FreeRoundsGrants.env(ctx), playerId, o, How.Claim(Source.OperatorManual, operator = true,
          by = Some(by))))
      case Some(o) =>
        Answers.amount(b, "depositAmount") match
          case Left(refused) => refused
          case Right(deposit) =>
            o.currency.orElse(playerCurrency(ctx, playerId)) match
              case None => refuse("player_currency_unresolved")
              case Some(currency) =>
                claim(ctx, playerId, o, currency, deposit.map(Deposit(_, currency, 0)), by)(Opening.of(o, playerId, _, Source.OperatorManual,
                  by = Some(by)))

  /**
   * A free bonus (`BR:3486-3565`): `bonusAmount` (minor units) and `wager` (the multiplier) required, the maximum
   * convertible the amount unless given, in the body's currency (else the player's), a 30-day wagering window, offer
   * `manual:<uuid>` of type `MANUAL`.
   */
  private def freeBonus(ctx: Ctx, playerId: String, b: JsonNode, by: String): Reply =
    Answers.amounts(b, "bonusAmount", "maxConvertibleAmount") match
      case Left(refused) => refused
      case Right(amounts) =>
        (amounts.get("bonusAmount"), multiplier(b, "wager")) match
          case (Some(amount), Some(wager)) =>
            b.text("currency").map(c => Currency.from(c).toOption).getOrElse(playerCurrency(ctx, playerId)) match
              case None => refuse("player_currency_unresolved")
              case Some(currency) =>
                val now = ctx.now()
                val manual = Offer(id = FreeRoundsGrants.manualOfferId(), name = FreeBonusName, offerType = OfferType.NoDeposit,
                  maxBonusValue = Some(amount), maxConvertibleAmount = amounts.get("maxConvertibleAmount"), wageringMultiplier = wager,
                  wageringWindowDays = Some(FreeBonusWindowDays), currency = Some(currency), createdAt = now, updatedAt = now)
                claim(ctx, playerId, manual, currency, None, by)(d =>
                  Opening.of(manual, playerId, d, Source.OperatorManual, by = Some(by)).copy(offerType = ManualType))
          case _ => refuse("bonusAmount and wager are required")

  /** A whole multiplier, zero or more; anything else is none. */
  private def multiplier(b: JsonNode, field: String): Option[Long] =
    Option(b.get(field)).filter(n => n.isIntegralNumber && n.canConvertToLong && n.asLong >= 0).map(_.asLong)

  /**
   * Free rounds with no offer (`BR:3363-3480`): FR-002's free-rounds checks in the original's order, then the grant path
   * with an offer made of the body's fields under `manual:<uuid>`.
   */
  private def freeRounds(ctx: Ctx, playerId: String, b: JsonNode, by: String): Reply =
    Answers.amounts(b, "spinValue", "maxConvertibleAmount") match
      case Left(refused) => refused
      case Right(amounts) =>
        val gameId      = b.text("gameId")
        val spinCount   = b.long("spinCount").filter(_ > 0).map(_.toInt)
        val spinValue   = amounts.get("spinValue").filter(_ > 0)
        val destination = b.text("winningsDestination").flatMap(WinningsDestination.from)
        val maxConv     = amounts.get("maxConvertibleAmount")
        if gameId.isEmpty then refuse("game_id_required")
        else if spinCount.isEmpty then refuse("spin_count_required")
        else if spinValue.isEmpty then refuse("spin_value_required")
        else if destination.contains(WinningsDestination.BonusBalance) && !maxConv.exists(_ > 0) then
          refuse("max_convertible_amount_required")
        else
          val now = ctx.now()
          val manual = Offer(id = FreeRoundsGrants.manualOfferId(), name = FreeRoundsName, offerType = OfferType.FreeSpins,
            gameId = gameId, provider = b.text("provider"), spinCount = spinCount, spinValue = spinValue,
            maxConvertibleAmount = maxConv, wageringMultiplier = multiplier(b, "wageringMultiplier").getOrElse(0L),
            winningsDestination = destination, freeSpinsExpiryDays = b.long("freeSpinsExpiryDays").map(_.toInt),
            wageringWindowDays = b.long("wageringWindowDays").map(_.toInt), createdAt = now, updatedAt = now)
          rounds(FreeRoundsGrants.give(FreeRoundsGrants.env(ctx), playerId, manual, How.Manual(by)))

  /** The override's claim on `PlayerRewards`, then the award; the reward is opened as `opening` says. */
  private def claim(ctx: Ctx, playerId: String, offer: Offer, currency: Currency, deposit: Option[Deposit], by: String)(
      opening: io.eitheror.casino.rewards.eligibility.Decision => Opening): Reply =
    val now    = ctx.now()
    val open   = Flows.walletOpen(ctx.wallet, playerId, currency)
    val answer = ctx.player(playerId).call(PlayerRewards.claim)
      .invoke(ClaimOffer(offer, operator = true, deposit, Source.OperatorManual, None, None, open, currency, now))
    answer.outcome match
      case Outcome.Declined(_, reason) => ineligible(reason)
      case Outcome.Claimed(c)          => awarded(ctx, c, opening(c.decision), now)
      case other =>
        org.slf4j.LoggerFactory.getLogger(getClass).warn(s"operator award by $by for $playerId: unexpected $other")
        unavailable

  private def awarded(ctx: Ctx, c: Claim, opening: Opening, now: java.time.Instant): Reply =
    StepCalls.run(ctx, Awards.startOpening(ctx.components, opening, c, now)) match
      case Some(WalletAnswer.Awarded(a)) =>
        ok(Json.obj("status" -> "granted", "bonusAmount" -> c.decision.amount, "wageringRequired" -> a.wageringRequirement))
      case Some(WalletAnswer.Refused(_, code, _)) => ineligible(code)
      case _                                      => unavailable

  /**
   * The grant path's answer in the operator's shape: `{status: "granted", grantId, type, providerStatus}`; a decline
   * 409 `{status: "ineligible", reason}`; the rest as the grant answers it (502 `provider_rejected` naming the grant).
   */
  def rounds(r: Result): Reply = r match
    case Result.Granted(id, status) => ok(FreeRoundsGrants.granted(id, status))
    case Result.Declined(reason)    => ineligible(reason)
    case other                      => FreeRoundsGrants.refusal(other, Answers.Grants).getOrElse(unavailable)
