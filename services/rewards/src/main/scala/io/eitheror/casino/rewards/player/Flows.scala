package io.eitheror.casino.rewards.player

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.clients.{Called, GameProvider, Wallet}
import io.eitheror.casino.rewards.offers.{Offer, OfferBook}
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.{ExpireReason, Reward, Source}
import io.eitheror.casino.rewards.steps.{Awards, StepRequest}

import java.time.Instant

/**
 * What a `PlayerRewards` answer's caller does with its `Outcome`, shared by the routes and the consumers: the entity
 * decides, the caller moves the reward and starts the award. Each act is a repeatable call (a reward move to the state
 * it is in writes nothing; a step started twice answers its first state), so a caller that fails half way runs again.
 */
object Flows:
  /** The wallet's open records for the evaluator (research R3, `EE:501-512`): active records, 0 when it cannot be read. */
  def walletOpen(wallet: Wallet, playerId: String, currency: Currency): Int =
    wallet.restrictedFunds(playerId, currency) match
      case Called.Done(funds) => funds.records.count(_.status.equalsIgnoreCase("active"))
      case _                  => 0

  def offerBook(client: ComponentClient, slug: String) = client.forEventSourcedEntity(EntityId(slug)).call(OfferBook.get).invoke()

  private def rewardOf(client: ComponentClient, id: String) = client.forEventSourcedEntity(EntityId(id))

  /**
   * Moves the reward as the outcome says, or starts its award: a selection to pending, a deselection back, an expired
   * selection to `EXPIRED` (no wallet command), a claim to the wallet. A decline or a refusal is the caller's answer.
   */
  def act(client: ComponentClient, playerId: String, offer: Option[Offer], outcome: Outcome, at: Instant): Option[StepRequest] =
    outcome match
      case Outcome.Selected(id) =>
        rewardOf(client, id).call(Reward.select).invoke(reward.Select(at)): Unit
        None
      case Outcome.Deselected(ids) =>
        ids.foreach(id => rewardOf(client, id).call(Reward.deselect).invoke(reward.Deselect(at)): Unit)
        None
      case Outcome.Expired(id) =>
        rewardOf(client, id).call(Reward.expire).invoke(reward.Expire(ExpireReason.PendingExpired, at)): Unit
        None
      case Outcome.Claimed(claim) => offer.map(Awards.start(client, _, playerId, claim, at))
      case _                      => None

  /**
   * A confirmed deposit (FR-012): the player's first pending selection and its offer as the book holds it now, the
   * wallet's open records, then the record decides. A decline is recorded on the reward too, which stays as it was; the
   * deposit is the wallet's, never withheld. Evaluated at the deposit's time, so a deposit read late is judged as made.
   */
  def deposit(client: ComponentClient, wallet: Wallet, slug: String, deposit: DepositSeen, playerId: String): PlayerAnswer =
    val player  = client.forEventSourcedEntity(EntityId(playerId))
    val state   = player.call(PlayerRewards.get).invoke()
    val pending = state.pending.map((id, held) => PendingOffer(id, offerBook(client, slug).offer(held.offerId)))
    val offer   = pending.flatMap(_.offer)
    val open    = pending.fold(0)(_ => walletOpen(wallet, playerId, offer.flatMap(_.currency).getOrElse(Currency.unsafe(deposit.currency))))
    val answer  = player.call(PlayerRewards.depositConfirmed).invoke(DepositConfirmed(deposit, pending, open, deposit.at))
    answer.outcome match
      case Outcome.Declined(Some(id), reason) =>
        rewardOf(client, id).call(Reward.refuseAward).invoke(reward.RefuseAward(reason, deposit.at)): Unit
      case other =>
        val claimedOffer = other match
          case Outcome.Claimed(c) => offer.filter(_.id == c.offerId).orElse(offerBook(client, slug).offer(c.offerId))
          case _                  => offer
        act(client, playerId, claimedOffer, other, deposit.at): Unit
    answer

  // ── free rounds granted on another path: the grant path is task T054's (US5) ─────────────

  /**
   * A grant of free rounds asked for (FR-017, FR-022, FR-023): by the campaign's award route (`operator` false, source
   * `customerio_webhook`) or an operator (`operator` true, source `operator_manual`, `by` the operator). `offer` is the
   * brand's offer, or, for an operator's free rounds with no offer (`manual`), one made of the body's fields under
   * `manual:<uuid>`, whose reward is opened with type `MANUAL`.
   */
  final case class FreeRoundsAsk(playerId: String, offer: Offer, operator: Boolean, source: Source, manual: Boolean,
      by: Option[String], at: Instant)

  /** What a free-rounds grant came to, for the route to answer in its own shape (contracts/routes.md). */
  enum FreeRoundsGrant:
    /** Created at the provider (the route answers after `create`): the reward's id is the provider's `grantId`. */
    case Granted(rewardId: String, providerStatus: String)
    /** The evaluator's decline. */
    case Declined(reason: String)
    /** `currency_not_configured` (+ `playerCurrency`), `player_currency_unresolved`, `game_not_eligible`,
      * `spin_value_not_offered` (+ `allowedValues`), `provider_rejected` (+ `grantId`). */
    case Refused(code: String, fields: Vector[(String, Any)] = Vector.empty)
    /** No answer within the request bound, or the grant path not built yet (T054). */
    case Unavailable

  /**
   * THE SEAM FOR TASK T054 (US5): the free-rounds grant path — the spin value for the player's currency, G1's game
   * check, the spin-value check, the evaluation (rules 1 to 4 for an operator), the claim on `PlayerRewards`, the
   * reward opened with its rounds, then `FreeRounds` up to `create`. The campaign's award route and the operator's
   * award (both shapes) call it; until T054 fills it, it answers `Unavailable` (503) and their free-rounds cases are
   * pending naming T054.
   */
  def grantFreeRounds(@scala.annotation.unused client: ComponentClient, @scala.annotation.unused provider: GameProvider,
      @scala.annotation.unused wallet: Wallet, @scala.annotation.unused ask: FreeRoundsAsk): FreeRoundsGrant =
    FreeRoundsGrant.Unavailable
