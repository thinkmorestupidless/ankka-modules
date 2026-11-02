package io.eitheror.casino.rewards.player

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.eligibility.*
import io.eitheror.casino.rewards.player.PlayerEvent.*
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Instant
import java.util.UUID

/**
 * One player's rewards as this service decides them (FR-004 to FR-012; research R3), keyed by the player id. Each
 * command runs `Evaluator` over the record as it stands and reserves the reward, or declines, in one event, so two
 * claims at once are serialised here and the second sees the first (a reserved claim counts as open). It calls nothing:
 * the offer, the wallet's open records and the currency are the caller's to read, and the caller acts on the answer's
 * `Outcome` (open the reward, move it, start `WalletStep(<reward>/award)`). A repeat writes nothing and answers what the
 * first did, so a caller that failed half way may run again.
 */
final class PlayerRewards(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[PlayerRewardsState, PlayerEvent]:
  import PlayerRewards.*

  def emptyState: PlayerRewardsState = PlayerRewardsState()

  def applyEvent(event: PlayerEvent): PlayerRewardsState = applied(currentState, event)

  private def answer(o: Outcome): Effect[PlayerAnswer] = effects.reply(PlayerAnswer(currentState, o))
  private def write(o: Outcome, events: PlayerEvent*): Effect[PlayerAnswer] =
    effects.persistAll(events).thenReply(s => PlayerAnswer(s, o))

  /** The offer visible to the player; a repeat while a reward of it is available or pending writes nothing (FR-022). */
  def target(c: Target): Effect[PlayerAnswer] =
    currentState.unclaimed(c.offer.id) match
      case Some((id, _)) => answer(Outcome.Visible(id, fresh = false))
      case None =>
        val id = newRewardId()
        write(Outcome.Visible(id, fresh = true), OfferMadeVisible(id, c.offer.id, c.source, c.code, c.tag, c.at))

  /**
   * The player's selection (FR-011; `BR` `/select`). Only an offer the player holds a reward of may be selected
   * (`offer_not_visible`; `ALREADY_CLAIMED` once it is claimed), and only while no other selection is pending: a second
   * is `ACTIVE_BONUS_CONFLICT` (the edge case "two selections at once", decided here, not by the evaluator). A no-deposit offer is evaluated at once, its code
   * already verified when it was made visible and no minimum enforced, and claimed; a deposit offer moves to pending,
   * unevaluated, as the original's: the deposit evaluates it.
   */
  def select(c: Select): Effect[PlayerAnswer] =
    val s = currentState
    s.unclaimed(c.offer.id) match
      case None =>
        val claimed = s.rewards.values.exists(h => h.offerId == c.offer.id && h.claim.isDefined)
        answer(Outcome.Refused(if claimed then AlreadyClaimed else OfferNotVisible))
      case Some((id, _)) if s.otherPending(id).isDefined => answer(Outcome.Declined(Some(id), Decline.ActiveBonusConflict.reason))
      case Some((id, held)) if c.offer.noDepositRequired =>
        val request = Request(Mode.Standard, Anchor.registration(s.registeredAt, c.at), c.walletCurrency, detection = s.detectionOn,
          codeVerifiedAtTargeting = true, enforceMinDeposit = Some(false))
        Evaluator.evaluate(c.offer, s.snapshot, c.walletOpen, request, c.at) match
          case Left(d)         => answer(Outcome.Declined(Some(id), d.reason))
          case Right(decision) =>
            val claim = reserve(id, held, Claim(id, c.offer.id, decision, held.source, held.code, held.tag, None, c.at))
            write(Outcome.Claimed(claim), Claimed(claim))
      case Some((id, held)) if held.status == RewardStatus.Pending => answer(Outcome.Selected(id))
      case Some((id, _))                                           => write(Outcome.Selected(id), RewardSelected(id, c.at))

  /**
   * A code entered (FR-019; `BR` `/enter-code`): recorded with what it came to, in the same event as the reward it makes
   * visible. A code that names no live offer is `invalid_code`. One that does is evaluated as the original's entry is,
   * the code given, no minimum enforced, an affiliate's code verified by the programme's document; a decline (or a
   * selection already pending of another offer, as `select`'s) is `ineligible`. Matched, the offer is made visible
   * (a reward already held of it is kept, with its source), and the caller selects it.
   */
  def enterCode(c: EnterCode): Effect[PlayerAnswer] =
    val s    = currentState
    val code = c.code.trim
    def entered(outcome: String) = CodeEntered(code, outcome, c.at)
    c.found match
      case None => write(Outcome.Refused(InvalidCode), entered(CodeOutcome.Invalid))
      case Some(f) =>
        val held = s.unclaimed(f.offer.id)
        val request = Request(Mode.Standard, Anchor.registration(s.registeredAt, c.at), c.walletCurrency, Some(code),
          detection = s.detectionOn, codeVerifiedAtTargeting = f.source == Source.AffiliateCode, enforceMinDeposit = Some(false))
        val decided =
          if s.otherPending(held.fold("")(_._1)).isDefined then Left(Decline.ActiveBonusConflict.reason)
          else Evaluator.evaluate(f.offer, s.snapshot, c.walletOpen, request, c.at).left.map(_.reason)
        (decided, held) match
          case (Left(reason), _)         => write(Outcome.Declined(held.map(_._1), reason), entered(CodeOutcome.Ineligible))
          case (Right(_), Some((id, _))) => write(Outcome.Visible(id, fresh = false), entered(CodeOutcome.Matched))
          case (Right(_), None) =>
            val id = newRewardId()
            write(Outcome.Visible(id, fresh = true), entered(CodeOutcome.Matched),
              OfferMadeVisible(id, f.offer.id, f.source, Some(code), f.tag, c.at))

  /** Every pending selection back to available (FR-011). */
  def deselect(c: Deselect): Effect[PlayerAnswer] =
    val ids = currentState.rewards.toVector.collect { case (id, h) if h.unclaimed && h.status == RewardStatus.Pending => id }.sorted
    if ids.isEmpty then answer(Outcome.Deselected(Vector.empty)) else write(Outcome.Deselected(ids), RewardsDeselected(ids, c.at))

  /**
   * A confirmed deposit (FR-012; research R4's deposit-confirm path): recorded, then the first pending selection
   * evaluated at its selection, with the wallet's ordinal as the deposit number, the code verified at targeting.
   * Eligible is a claim; `CLAIM_WINDOW_EXPIRED`, or an offer no longer live, expires it; any other decline is recorded
   * and leaves it pending. The deposit itself is never withheld. A deposit reported again under its payment reference
   * writes nothing and answers the claim it made, if any.
   */
  def depositConfirmed(c: DepositConfirmed): Effect[PlayerAnswer] =
    val s = currentState
    val d = c.deposit
    if s.payments.contains(d.paymentRef) then answer(s.claimOf(d.paymentRef).fold(Outcome.Idle)(Outcome.Claimed(_)))
    else
      val recorded = DepositRecorded(d)
      val pending = c.pending.flatMap(p => s.rewards.get(p.rewardId).filter(h => h.unclaimed && h.status == RewardStatus.Pending)
        .map(h => (p, h)))
      pending match
        case None => write(Outcome.Idle, recorded)
        case Some((p, held)) =>
          p.offer.filter(_.live(c.at)) match
            case None => write(Outcome.Expired(p.rewardId), recorded, PendingExpired(p.rewardId, c.at))
            case Some(offer) =>
              val currency = offer.currency.getOrElse(Currency.unsafe(d.currency))
              val request = Request(Mode.Standard, Anchor.Selection(held.selectedAt.getOrElse(c.at)), currency,
                deposit = Some(Deposit(d.amount, currency, d.ordinal)), detection = s.detectionOn, codeVerifiedAtTargeting = true)
              Evaluator.evaluate(offer, s.snapshot, c.walletOpen, request, c.at) match
                case Right(decision) =>
                  val claim = reserve(p.rewardId, held,
                    Claim(p.rewardId, offer.id, decision, held.source, held.code, held.tag, Some(d.paymentRef), c.at))
                  write(Outcome.Claimed(claim), recorded, Claimed(claim))
                case Left(Decline.ClaimWindowExpired) =>
                  write(Outcome.Expired(p.rewardId), recorded, PendingExpired(p.rewardId, c.at))
                case Left(decline) =>
                  write(Outcome.Declined(Some(p.rewardId), decline.reason), recorded,
                    DeclineRecorded(p.rewardId, decline.reason, c.at))

  /**
   * An award of the offer on another path (the award route, an operator's award; tasks T059, T060): the reward the
   * player may select of it, else a new one, evaluated in the path's mode and reserved. A deposit, when given, sizes it.
   */
  def claim(c: ClaimOffer): Effect[PlayerAnswer] =
    val s = currentState
    PlayerRewards.evaluateClaim(s, c) match
      case Left(d) => answer(Outcome.Declined(None, d.reason))
      case Right(decision) =>
        val held = s.unclaimed(c.offer.id)
        val id   = held.fold(newRewardId())(_._1)
        val claim = held.fold(Claim(id, c.offer.id, decision, c.source, c.code, c.tag, None, c.at))((_, h) =>
          reserve(id, h, Claim(id, c.offer.id, decision, c.source, c.code, c.tag, None, c.at)))
        val made  = Option.when(held.isEmpty)(OfferMadeVisible(id, c.offer.id, c.source, c.code, c.tag, c.at)).toSeq
        write(Outcome.Claimed(claim), (made :+ Claimed(claim))*)

  /**
   * The reward's state from its own events (`IndexConsumer`); a report that changes nothing writes nothing, and a
   * report of a reward a later claim replaced is not read.
   */
  def rewardChanged(c: RewardChanged): Effect[PlayerAnswer] =
    val before = currentState.rewards.get(c.rewardId)
    val after  = indexed(before, c.offerId, c.status, c.providerFailed, c.awardRefused, c.at)
    if currentState.retired.contains(c.rewardId) || before.contains(after) then answer(Outcome.Idle)
    else write(Outcome.Idle, RewardIndexed(c.rewardId, c.offerId, c.status, c.providerFailed, c.awardRefused, c.at))

  /**
   * The player's standing from `casino.players` (`PlayersConsumer`, research R9): the registration, the duplicate check
   * and its block, the fraud flag, the tier, and the brand's detection switch the fact carries. Only what changes is
   * written, so a fact delivered twice writes nothing the second time.
   */
  def playerFact(c: PlayerFact): Effect[PlayerAnswer] =
    val events = PlayerRewards.factEvents(currentState, c)
    if events.isEmpty then answer(Outcome.Idle) else write(Outcome.Idle, events*)

  /** A round naming free rounds this service does not hold (`RoundsConsumer`): recorded once per round id. */
  def roundUnmatched(c: RecordUnmatchedRound): Effect[PlayerAnswer] =
    if currentState.unmatchedRounds.exists(_.roundId == c.round.roundId) then answer(Outcome.Idle)
    else write(Outcome.Idle, RoundUnmatched(c.round))

  def get: ReadOnlyEffect[PlayerRewardsState] = effects.reply(currentState)

object PlayerRewards
    extends EventSourcedEntity.Companion[PlayerRewards, PlayerRewardsState, PlayerEvent](
      componentId = ComponentId("player-rewards"),
      stateSerializer = RewardsCodecs.serializer[PlayerRewardsState]("player-rewards-state"),
      eventSerializer = RewardsCodecs.serializer[PlayerEvent]("player-event")
    ):
  given Serializer[PlayerRewardsState] = RewardsCodecs.serializer[PlayerRewardsState]("player-rewards-reply")
  given Serializer[PlayerAnswer]       = RewardsCodecs.serializer[PlayerAnswer]("player-answer")
  given Serializer[Target]             = RewardsCodecs.serializer[Target]("player-target")
  given Serializer[Select]             = RewardsCodecs.serializer[Select]("player-select")
  given Serializer[Deselect]           = RewardsCodecs.serializer[Deselect]("player-deselect")
  given Serializer[DepositConfirmed]   = RewardsCodecs.serializer[DepositConfirmed]("player-deposit-confirmed")
  given Serializer[ClaimOffer]         = RewardsCodecs.serializer[ClaimOffer]("player-claim")
  given Serializer[RewardChanged]      = RewardsCodecs.serializer[RewardChanged]("player-reward-changed")
  given Serializer[PlayerFact]         = RewardsCodecs.serializer[PlayerFact]("player-fact")
  given Serializer[EnterCode]          = RewardsCodecs.serializer[EnterCode]("player-enter-code")
  given Serializer[RecordUnmatchedRound] = RewardsCodecs.serializer[RecordUnmatchedRound]("player-round-unmatched")

  def create(context: EventSourcedEntityContext) = new PlayerRewards(context)

  val target           = command("target")(_.target)
  val select           = command("select")(_.select)
  val deselect         = command("deselect")(_.deselect)
  val depositConfirmed = command("deposit-confirmed")(_.depositConfirmed)
  val claim            = command("claim")(_.claim)
  val rewardChanged    = command("reward-changed")(_.rewardChanged)
  val playerFact       = command("player-fact")(_.playerFact)
  val enterCode        = command("enter-code")(_.enterCode)
  val roundUnmatched   = command("round-unmatched")(_.roundUnmatched)
  val get              = query("get")(_.get)

  val OfferNotVisible = "offer_not_visible"
  val AlreadyClaimed  = Decline.AlreadyClaimed.reason
  /** A code no live offer carries and the programme's document does not hold (FR-019), never the evaluator's `INVALID_CODE`. */
  val InvalidCode     = "invalid_code"

  /**
   * How `claim` evaluates an award on another path, so a caller with checks of its own to make first (the free rounds'
   * provider checks) can answer the decline the claim would, in the original's order, without reserving anything.
   */
  def evaluateClaim(s: PlayerRewardsState, c: ClaimOffer): Either[Decline, Decision] =
    val mode = if c.operator then Mode.OperatorOverride else Mode.Standard
    val request = Request(mode, Anchor.registration(s.registeredAt, c.at), c.walletCurrency, c.code, c.deposit, s.detectionOn)
    Evaluator.evaluate(c.offer, s.snapshot, c.walletOpen, request, c.at)

  /** A reward id: reserved in the event that makes the reward, so a replay holds the same one. */
  private def newRewardId(): String = UUID.randomUUID().toString

  /**
   * The claim on a reward the player holds: under its own id the first time; once a claim of it has been answered
   * without an award (the wallet refused it), under a fresh id replacing it (`Claim.replaces`).
   */
  private def reserve(id: String, held: HeldReward, claim: Claim): Claim =
    if held.claim.isEmpty then claim else claim.copy(rewardId = newRewardId(), replaces = Some(id))

  /** A reward as an index report leaves it: a claim ends once the wallet answered (the reward moved, or refused it). */
  private def indexed(before: Option[HeldReward], offerId: String, status: RewardStatus, providerFailed: Boolean,
      awardRefused: Boolean, at: Instant): HeldReward =
    val h = before.getOrElse(HeldReward(offerId, openedAt = at))
    val answered = awardRefused || !(status == RewardStatus.Available || status == RewardStatus.Pending)
    h.copy(status = status, providerFailed = providerFailed, claiming = h.claiming && !answered,
      selectedAt = if status == RewardStatus.Pending then h.selectedAt else if status == RewardStatus.Available then None else h.selectedAt)

  /** What a player's fact writes on the record as it stands: nothing that is already so. */
  def factEvents(s: PlayerRewardsState, c: PlayerFact): Vector[PlayerEvent] =
    val detection = c.detection.filter(_ != s.detectionOn).map(DetectionChanged(_, c.at)).toVector
    val change: Option[PlayerEvent] = c.change match
      case PlayerChange.Registered(at, currency) =>
        Option.when(!s.registeredAt.contains(at) || (s.currency.isEmpty && currency.isDefined))(RegistrationRecorded(at, currency, c.at))
      case PlayerChange.CheckStarted(registeredAt) => Option.when(s.checkInProgress.isEmpty)(CheckStarted(registeredAt, c.at))
      case PlayerChange.CheckEnded(outcome)        => Option.when(s.checkInProgress.isDefined)(CheckEnded(outcome, c.at))
      case PlayerChange.Blocked                    => Option.when(!s.noDepositBlocked)(NoDepositBlocked(c.at))
      case PlayerChange.BlockCleared               => Option.when(s.noDepositBlocked)(BlockCleared(c.at))
      case PlayerChange.Flag(on)                   => Option.when(s.suspectedDuplicate != on)(FlagChanged(on, c.at))
      case PlayerChange.Tier(name)                 => Option.when(!s.tier.contains(name))(TierChanged(name, c.at))
    detection ++ change

  private def onReward(s: PlayerRewardsState, id: String)(f: HeldReward => HeldReward): PlayerRewardsState =
    s.copy(rewards = s.rewards.updatedWith(id)(_.map(f)))

  def applied(s: PlayerRewardsState, event: PlayerEvent): PlayerRewardsState = event match
    case OfferMadeVisible(id, offerId, source, code, tag, at) =>
      s.copy(rewards = s.rewards.updated(id, HeldReward(offerId, source = source, code = code, tag = tag, openedAt = at)))
    case RewardSelected(id, at) => onReward(s, id)(_.copy(status = RewardStatus.Pending, selectedAt = Some(at)))
    case RewardsDeselected(ids, _) =>
      ids.foldLeft(s)((acc, id) => onReward(acc, id)(_.copy(status = RewardStatus.Available, selectedAt = None)))
    case DepositRecorded(d) =>
      s.copy(lastDeposit = Some(d), currency = Some(d.currency),
        payments = (s.payments :+ d.paymentRef).takeRight(PlayerRewardsState.PaymentsKept))
    case Claimed(claim) =>
      val held = claim.replaces.flatMap(s.rewards.get).orElse(s.rewards.get(claim.rewardId))
        .getOrElse(HeldReward(claim.offerId, openedAt = claim.at))
      val rest = claim.replaces.fold(s.rewards)(s.rewards - _)
      s.copy(rewards = rest.updated(claim.rewardId, held.copy(claim = Some(claim), claiming = true, lastDecline = None)),
        retired = s.retired ++ claim.replaces)
    case PendingExpired(id, _)        => onReward(s, id)(_.copy(status = RewardStatus.Expired))
    case DeclineRecorded(id, why, _)  => onReward(s, id)(_.copy(lastDecline = Some(why)))
    case RewardIndexed(id, offerId, status, failed, refused, at) =>
      s.copy(rewards = s.rewards.updated(id, indexed(s.rewards.get(id), offerId, status, failed, refused, at)))
    case RegistrationRecorded(at, currency, _) => s.copy(registeredAt = Some(at), currency = s.currency.orElse(currency))
    case CheckStarted(registeredAt, at)        => s.copy(checkInProgress = Some(at), registeredAt = s.registeredAt.orElse(Some(registeredAt)))
    case CheckEnded(_, _)                      => s.copy(checkInProgress = None)
    case NoDepositBlocked(_)                   => s.copy(noDepositBlocked = true)
    case BlockCleared(_)                       => s.copy(noDepositBlocked = false)
    case FlagChanged(on, _)                    => s.copy(suspectedDuplicate = on)
    case TierChanged(tier, _)                  => s.copy(tier = Some(tier))
    case DetectionChanged(on, _)               => s.copy(detectionOn = on)
    case CodeEntered(_, _, _)                  => s
    case RoundUnmatched(round)                 =>
      s.copy(unmatchedRounds = (s.unmatchedRounds :+ round).takeRight(PlayerRewardsState.UnmatchedKept))
