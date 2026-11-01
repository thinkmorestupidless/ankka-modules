package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.{Amounts as MoneyAmounts, Currency}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.codes.CodeDocumentState
import io.eitheror.casino.rewards.offers.{CodeMatch, Codes as CodeRules, Offer, OfferBookState}
import io.eitheror.casino.rewards.player.{CodeFound, EnterCode, Flows, HeldReward, Openings, Outcome, PlayerRewards}
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import Answers.*

/**
 * `POST /{playerId}/codes` and `POST /{playerId}/codes/preview` (FR-019 to FR-021; contracts/routes.md). A code is
 * matched trimmed and in lower case against the brand's live offers first, then the programme's code document as
 * `codes-<slug>` last fetched it; a document entry naming an offer that is not live matches nothing. An entry is
 * recorded on `PlayerRewards` (`bonus_code_entered` with its outcome); matched and eligible, the offer is made visible
 * and selected as the player's own selection would select it. A preview records nothing, as the original's.
 */
object Codes:
  private def refuse(code: String): Reply = Answers.Rewards.refuse(code)

  private def codeOf(b: JsonNode): Option[String] = b.text("code").map(_.trim).filter(_.nonEmpty)

  /** The programme's copy for matching: as last fetched, fetched now if never; none read is an empty document. */
  private def document(ctx: Ctx): CodeDocumentState = ctx.programme.getOrElse(CodeDocumentState())

  /** The live offer a code names, and how it is given. */
  private def found(code: String, book: OfferBookState, programme: CodeDocumentState, ctx: Ctx): Option[CodeFound] =
    val now = ctx.now()
    CodeRules.find(code, book.offers.values, programme.entries, now).flatMap {
      case CodeMatch.Brand(id)          => book.offer(id).map(CodeFound(_, Source.PlayerCode))
      case CodeMatch.Affiliate(id, tag) => book.offer(id).filter(_.live(now)).map(CodeFound(_, Source.AffiliateCode, tag))
    }

  def enter(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    codeOf(b) match
      case None => refuse("code is required")
      case Some(code) =>
        val now   = ctx.now()
        val named  = found(code, ctx.offers, document(ctx), ctx)
        val currency = named.map(f => Selection.walletCurrency(ctx, f.offer, playerId)).getOrElse(Currency.unsafe("EUR"))
        val open     = named.fold(0)(_ => Flows.walletOpen(ctx.wallet, playerId, currency))
        val answer   = ctx.player(playerId).call(PlayerRewards.enterCode).invoke(EnterCode(code, named, open, currency, now))
        (answer.outcome, named) match
          case (Outcome.Refused(c), _)           => refuse(c)
          case (Outcome.Declined(_, reason), _)  => ineligible(reason)
          case (Outcome.Visible(id, fresh), Some(f)) =>
            if fresh then
              val opening = Openings.provisional(f.offer, playerId, currency, f.source, Some(code), f.tag)
              ctx.reward(id).call(reward.Reward.open).invoke(reward.Open(opening, RewardStatus.Available, now)): Unit
            val held = answer.state.rewards.getOrElse(id, HeldReward(f.offer.id))
            val summary = PlayerState.summary(f.offer, held)
            summary.put("source", "code"): Unit
            Selection.selectOffer(ctx, playerId, f.offer, declined = ineligible, success = Some(ok(summary)))
          case _ => unavailable

  /**
   * The offer's terms for a code, recording nothing (`BR` `/promo-code` with `preview`): `isAffiliate` is the brand
   * code's own flag, or true for the programme's. A code that matches nothing is `200 {valid: false}`.
   */
  def preview(ctx: Ctx, b: JsonNode): Reply =
    codeOf(b) match
      case None => refuse("code is required")
      case Some(code) =>
        found(code, ctx.offers, document(ctx), ctx) match
          case None => codeNotFound
          case Some(f) =>
            val o = f.offer
            val affiliate = f.source == Source.AffiliateCode ||
              o.redemptionCodes.exists(c => CodeRules.normalise(c.code) == CodeRules.normalise(code) && c.affiliate)
            ok(Json.obj("valid" -> true, "offerName" -> o.name, "offerType" -> o.offerType.wire, "preview" -> terms(o),
              "firesImmediately" -> o.minDepositAmount.forall(_ == 0L), "minDepositAmount" -> o.minDepositAmount,
              "isAffiliate" -> affiliate))

  /**
   * The offer's terms in a sentence: `100% match up to 500.00 EUR on your next deposit of at least 20.00 EUR`. The
   * original wrote the stored numbers with a pound sign; here the amounts are rendered in the offer's currency.
   */
  def terms(o: Offer): String =
    val currency = o.currency.getOrElse(Currency.unsafe("EUR"))
    def money(v: Long) = s"${MoneyAmounts.render(v, currency)} ${currency.code}"
    val percent = java.math.BigDecimal.valueOf(o.bonusPercentage).movePointLeft(2).stripTrailingZeros.toPlainString
    val upTo    = o.maxBonusValue.fold("")(m => s" up to ${money(m)}")
    val atLeast = o.minDepositAmount.filter(_ > 0).fold("")(m => s" on your next deposit of at least ${money(m)}")
    s"$percent% match$upTo$atLeast"
