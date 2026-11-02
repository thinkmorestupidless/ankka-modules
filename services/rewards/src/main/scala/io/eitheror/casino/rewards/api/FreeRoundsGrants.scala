package io.eitheror.casino.rewards.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.clients.{Called, GameProvider, Wallet}
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.{Offer, SpinCheck}
import io.eitheror.casino.rewards.player.{ClaimOffer, Flows, Outcome as PlayerOutcome, PlayerRewards, PlayerRewardsState}
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.steps.{FreeRounds, FreeRoundsFlow, FreeRoundsRequest}

import java.time.{Duration, Instant}
import java.util.UUID

import Answers.*

/**
 * The grant of free rounds (FR-023; research R8), shared by every path that gives them: the operator's award with an
 * offer or without one (`POST /reward-grants/{playerId}`), and a campaign's award (`POST /reward-campaigns/{playerId}/award`).
 * In the original's order (`BR:3050-3125`, `BR:500-560`): the spin value for the player's currency, the evaluation
 * (an award with an offer), G1's game check, the spin-value check (refused only on a known mismatch), then the reward
 * opened `ACTIVE` and `FreeRounds` started, the answer given once the provider answered the create. The evaluation is
 * made first without reserving anything, so a provider check refused leaves no claim behind; the claim then reserves
 * the reward, deciding again on the record as it stands.
 */
object FreeRoundsGrants:
  /**
   * How the rounds are given: an offer claimed on a path (evaluated), or an operator's rounds with no offer (not).
   * `codeVerified` skips rule 8, as a campaign's award does (it never presented a code).
   */
  enum How:
    case Claim(source: Source, code: Option[String] = None, tag: Option[String] = None, operator: Boolean = false,
        by: Option[String] = None, codeVerified: Boolean = false)
    /** An operator's free rounds with no offer (FR-017): the offer is the operator's terms, `manual:<uuid>`, type `MANUAL`. */
    case Manual(by: String)

  /** What a grant came to; each path answers it in its own shape. */
  enum Result:
    case Granted(rewardId: String, providerStatus: String)
    /** A check of the grant's own: the currency, the game, the spin value; `fields` beside the code. */
    case Refused(code: String, fields: Vector[(String, Any)] = Vector.empty)
    /** The evaluator's decline, its reason as the original answered it. */
    case Declined(reason: String)
    /** The provider refused the create, or did not answer it: the reward is cancelled `provider_failed`. */
    case ProviderRejected(rewardId: String)
    /** The game could not be looked up, or the create was not answered within the request bound (it goes on). */
    case Unavailable

  /** What a grant needs of the service: its components, its neighbours, its clock and its request bound. */
  final case class Env(components: ComponentClient, provider: GameProvider, wallet: Wallet, now: () => Instant, bound: Duration)

  def env(ctx: Ctx): Env = Env(ctx.components, ctx.gameProvider, ctx.wallet, () => ctx.now(), ctx.settings.requestBound)

  val ManualType = "MANUAL"

  /** An operator's award with no offer, free rounds or a free bonus, takes the offer id `manual:<uuid>` (FR-017). */
  def manualOfferId(): String = s"manual:${UUID.randomUUID()}"

  def give(env: Env, playerId: String, offer: Offer, how: How): Result =
    val now   = env.now()
    val state = env.components.forEventSourcedEntity(EntityId(playerId)).call(PlayerRewards.get).invoke()
    val missing = Option.when(!offer.freeRounds)("invalid_payload_type")
      .orElse(Option.when(offer.gameId.forall(_.isBlank))("game_id_required"))
      .orElse(Option.when(offer.spinCount.forall(_ <= 0))("spin_count_required"))
    missing.map(Result.Refused(_)).toLeft(()).flatMap(_ => spin(offer, state)) match
      case Left(refused) => refused
      case Right((currency, spinValue)) =>
        val game = offer.gameId.getOrElse("")
        val claim = how match
          case c: How.Claim =>
            Some(ClaimOffer(offer, c.operator, None, c.source, c.code, c.tag, Flows.walletOpen(env.wallet, playerId, currency), currency, now,
              c.codeVerified))
          case _: How.Manual => None
        val declined = claim.flatMap(c => PlayerRewards.evaluateClaim(state, c).left.toOption).map(d => Result.Declined(d.reason))
        declined.orElse(gameCheck(env.provider, game)).orElse(spinCheck(env.provider, SpinCheck(game, currency, spinValue))) match
          case Some(stop) => stop
          case None       => grant(env, playerId, offer, how, claim, currency, spinValue, now)

  /**
   * The spin value for the player's currency (FR-023): an offer with a value per currency needs the player's currency
   * (`player_currency_unresolved`) and a value for it (`currency_not_configured` naming it); a flat value is in the
   * offer's currency, else the player's, else the euro, as the original's.
   */
  def spin(offer: Offer, state: PlayerRewardsState): Either[Result, (Currency, Long)] =
    val player = state.currency.flatMap(c => Currency.from(c.trim.toUpperCase).toOption)
    if offer.spinConfigurations.nonEmpty then
      player match
        case None => Left(Result.Refused("player_currency_unresolved"))
        case Some(c) =>
          offer.spinConfigurations.get(c).toRight(Result.Refused("currency_not_configured", Vector("playerCurrency" -> c.code))).map(c -> _)
    else
      val currency = offer.currency.orElse(player).getOrElse(Currency.unsafe("EUR"))
      offer.spinValue.toRight(Result.Refused("spin_value_required")).map(currency -> _)

  /** G1 (research R6): the game must take free rounds; a game the catalogue cannot be asked about is unavailable. */
  private def gameCheck(provider: GameProvider, game: String): Option[Result] =
    provider.game(game) match
      case Called.Done(g) if g.freeRoundsEligible   => None
      case Called.Done(_) | Called.Refused(_, _, _) => Some(Result.Refused("game_not_eligible"))
      case Called.Unreachable(_)                    => Some(Result.Unavailable)

  /** Refused only on a known mismatch: not configured, or not answered, goes ahead (`FreeSpinsProvisioner.scala:118-143`). */
  private def spinCheck(provider: GameProvider, check: SpinCheck): Option[Result] =
    provider.spinValues(Vector(check.gameId), check.currency) match
      case Called.Done(values) => io.eitheror.casino.rewards.api.Offers.notOffered(values, check).map(allowed => Result.Refused("spin_value_not_offered", Vector("allowedValues" -> allowed)))
      case _                   => None

  private def grant(env: Env, playerId: String, offer: Offer, how: How, claim: Option[ClaimOffer], currency: Currency, spinValue: Long,
      now: Instant): Result =
    val reserved: Either[Result, (String, Decision, Option[String])] = claim match
      case None =>
        Right((UUID.randomUUID().toString,
          Decision(0L, 0L, offer.maxConvertibleAmount.getOrElse(0L), offer.wageringMultiplier, currency), None))
      case Some(c) =>
        env.components.forEventSourcedEntity(EntityId(playerId)).call(PlayerRewards.claim).invoke(c).outcome match
          case PlayerOutcome.Claimed(cl)          => Right((cl.rewardId, cl.decision, cl.replaces))
          case PlayerOutcome.Declined(_, reason)  => Left(Result.Declined(reason))
          case PlayerOutcome.Refused(code)        => Left(Result.Declined(code))
          case other                              => Left(Result.Declined(other.toString))
    reserved.map { (rewardId, decision, replaces) =>
      // Rounds carry no amount of their own: the face value is none (the original's `face_value` 0); the multiplier and
      // the maximum convertible are what their winnings are awarded with.
      val terms = FreeRoundsTerms.of(offer, spinValue)
      val (source, code, tag, by) = how match
        case c: How.Claim  => (c.source, c.code, c.tag, c.by)
        case m: How.Manual => (Source.OperatorManual, None, None, Some(m.by))
      val opening = Opening.of(offer, playerId, decision.copy(amount = 0L, wageringRequirement = 0L), source, code, tag, terms, by)
      val typed   = if how.isInstanceOf[How.Manual] then opening.copy(offerType = ManualType) else opening
      env.components.forEventSourcedEntity(EntityId(rewardId)).call(Reward.open).invoke(Open(typed, RewardStatus.Active, now)): Unit
      replaces.foreach(id => env.components.forEventSourcedEntity(EntityId(id)).call(Reward.expire).invoke(Expire(ExpireReason.Superseded, now)): Unit)
      val days = Windows.days(offer.freeSpinsExpiryDays, Windows.FreeRoundsDays).toInt
      val request = FreeRoundsRequest(rewardId, playerId, currency.code, offer.gameId.getOrElse(""), offer.spinCount.getOrElse(0), spinValue, days)
      val flow = env.components.forWorkflow(EntityId(rewardId))
      flow.call(FreeRounds.start).invoke(request): Unit
      created(env, rewardId) match
        case None                     => Result.Unavailable
        case Some(s) if s.playable    => Result.Granted(rewardId, s.created.get)
        case Some(_)                  => Result.ProviderRejected(rewardId)
    }.merge

  /** The workflow once the provider answered the create, waited for up to the request bound, as the award's (T036). */
  private def created(env: Env, rewardId: String): Option[FreeRoundsFlow] =
    val until = System.nanoTime() + env.bound.toNanos
    def poll(): Option[FreeRoundsFlow] =
      val s = env.components.forWorkflow(EntityId(rewardId)).call(FreeRounds.status).invoke()
      if s.created.isDefined then Some(s)
      else if System.nanoTime() > until then None
      else { Thread.sleep(10); poll() }
    poll()

  /**
   * A result that is not a grant, as `table` answers it: the grant's own refusals with their fields, the provider's
   * rejection naming the grant (502), no answer 503. A decline is the path's own shape, so it is left to the path.
   */
  def refusal(r: Result, table: Table): Option[Reply] = r match
    case Result.Refused(code, fields)       => Some(table.refuse(code, fields*))
    case Result.ProviderRejected(rewardId)  => Some(table.refuse("provider_rejected", "grantId" -> rewardId))
    case Result.Unavailable                 => Some(unavailable)
    case Result.Granted(_, _) | Result.Declined(_) => None

  /** The operator's answer to rounds granted (contracts/routes.md, `POST /reward-grants/{playerId}`). */
  def granted(rewardId: String, providerStatus: String): JsonNode =
    Json.obj("status" -> "granted", "grantId" -> rewardId, "type" -> "FREE_SPINS", "providerStatus" -> providerStatus)

