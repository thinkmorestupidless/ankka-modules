package io.eitheror.casino.rewards.reward

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.reward.RewardEvent.*

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One reward (FR-009, FR-010; research R3), keyed by the reward id: the original's ten states moved through
 * [[Transitions]], the clocks, the rule snapshot, and the free-rounds tally. It calls nothing: the wallet's and the
 * provider's answers are delivered to it (`WalletStep`, `FreeRounds`, the consumers), and its events are the fact
 * source and the clocks' (`Clocks`). A move the table does not list is refused `INVALID_TRANSITION` naming both
 * states; a move to the state it is in writes nothing, so every delivery may repeat. A reward not opened is
 * `CLAIM_NOT_FOUND`.
 */
final class Reward(context: EventSourcedEntityContext) extends EventSourcedEntity[RewardState, RewardEvent]:
  import RewardCodes.*
  import RewardStatus.*

  def emptyState: RewardState = RewardState(rewardId = context.entityId)

  def applyEvent(event: RewardEvent): RewardState = Reward.applied(currentState, event)

  private def answer(s: RewardState) = RewardAnswer(s)

  private def write(events: RewardEvent*): Effect[RewardAnswer] = effects.persistAll(events).thenReply(answer)
  private def writePaying(payment: Option[Payment], events: RewardEvent*): Effect[RewardAnswer] =
    effects.persistAll(events).thenReply(s => RewardAnswer(s, payment = payment))
  private def same: Effect[RewardAnswer] = effects.reply(answer(currentState))
  private def skip(why: String, payment: Option[Payment] = None): Effect[RewardAnswer] =
    effects.reply(RewardAnswer(currentState, payment = payment, skipped = Some(why)))
  private def refuse(code: String, message: String): Effect[RewardAnswer] =
    effects.reply(RewardAnswer(currentState, refusal = Some(Refused(code, message))))

  private def opened(f: RewardState => Effect[RewardAnswer]): Effect[RewardAnswer] =
    if currentState.opened then f(currentState) else refuse(NotFound, "no such reward")

  /** The move to `to` by the table: a repeat writes nothing, a move it does not list is refused naming both. */
  private def moving(to: RewardStatus)(events: RewardState => Seq[RewardEvent]): Effect[RewardAnswer] = opened { s =>
    Transitions.move(s.status, to) match
      case Left(e)           => refuse(e.code, e.message)
      case Right(Move.Stay)  => same
      case Right(Move.To(_)) => write(events(s)*)
  }

  def open(c: Open): Effect[RewardAnswer] =
    if currentState.opened then same
    else
      val targeted = Targeted(context.entityId, c.opening, c.at)
      c.initial match
        case Available => write(targeted)
        case Pending   => write(targeted, Selected(c.at, Reward.pendingDeadline(c.opening.snapshot, c.at)))
        case Active =>
          write(targeted, Activated(c.award, Reward.activeDeadline(c.opening.snapshot, c.opening.freeRounds.isDefined, c.at),
            c.wageringExpiresAt, c.at))
        case other => refuse(InvalidOpen, s"a reward opens AVAILABLE, PENDING or ACTIVE, not ${other.wire}")

  def select(c: Select): Effect[RewardAnswer] =
    moving(Pending)(s => Seq(Selected(c.at, Reward.pendingDeadline(s.snapshot, c.at))))

  def deselect(c: Deselect): Effect[RewardAnswer] = moving(Available)(_ => Seq(Deselected(c.at)))

  def activate(c: Activate): Effect[RewardAnswer] =
    moving(Active)(s => Seq(Activated(c.award, Reward.activeDeadline(s.snapshot, s.isFreeRounds, c.at), None, c.at)))

  /** The wallet refused the award (FR-013): recorded with its code, the state as it was. Once active it is stale. */
  def refuseAward(c: RefuseAward): Effect[RewardAnswer] = opened { s =>
    if s.status.isTerminal || s.isOpen || s.lastDecline.contains(c.code) then same else write(AwardRefused(c.code, c.at))
  }

  def convert(c: Convert): Effect[RewardAnswer] =
    moving(ConvertedPendingCap)(s => Seq(Converted(c.at, Reward.capDeadline(s.snapshot, c.at))))

  def forfeit(c: Forfeit): Effect[RewardAnswer] = moving(Forfeited)(_ => Seq(RewardEvent.Forfeited(c.reason, c.amount, c.at)))

  def close(c: Close): Effect[RewardAnswer] =
    moving(Closed)(s => Seq(RewardEvent.Closed(c.reason, s.status == ConvertedPendingCap, c.at)))

  /** A deadline the reward no longer holds is a stale timer's, and does nothing (research R10). */
  def expire(c: Expire): Effect[RewardAnswer] = opened { s =>
    if c.deadline.exists(d => !s.expiresAt.contains(d) && !s.wageringExpiresAt.contains(d)) then skip("stale_deadline")
    else moving(Expired)(s => Seq(RewardEvent.Expired(c.reason, s.status, c.at)))
  }

  def cancel(c: Cancel): Effect[RewardAnswer] = moving(Cancelled)(_ => Seq(RewardEvent.Cancelled(c.reason, c.clawback, c.at)))

  def revoke(c: Revoke): Effect[RewardAnswer] =
    moving(Revoked)(_ => Seq(RewardEvent.Revoked(c.mode, c.by, c.reason, c.clawback, c.at)))

  /** An open reward made primary (FR-016); a switch step already recorded writes nothing. */
  def switch(c: Switch): Effect[RewardAnswer] = opened { s =>
    if c.step <= s.switches then same
    else if !s.isOpen then refuse(NoActiveBonus, s"a ${s.status.wire} reward is not open")
    else write(Switched(c.step, c.priority, c.at))
  }

  def providerStatus(c: ProviderStatus): Effect[RewardAnswer] = opened { s =>
    s.freeRounds match
      case None                                             => refuse(NotFreeRounds, "the reward is not free rounds")
      case Some(f) if f.providerStatus.contains(c.status) => same
      case Some(_)                                          => write(ProviderStatusRecorded(c.status, c.at))
  }

  /**
   * A settled round (FR-024, `Settlement.round`): counted once per id, finalising the reward when it completes the
   * count; after finalisation paid on its own. A repeated late round answers its payment again, so the caller's
   * wallet step, keyed by the round, is started however often the round is delivered.
   */
  def roundSettled(c: RoundSettled): Effect[RewardAnswer] = rounds { (s, f) =>
    if !s.status.isTerminal && s.status != Active then skip(Skip.NotLive.reason)
    else
      Settlement.round(s.status, f.tally, f.terms.destination, c.roundId, c.win) match
        case Left(Skip.AlreadyCounted) =>
          skip(Skip.AlreadyCounted.reason, f.latePaid.get(c.roundId).filter(_ > 0).map(Payment(f.terms.destination, _, Some(c.roundId))))
        case Left(other) => skip(other.reason)
        case Right(Settled.Counted(next, finalisation)) =>
          writePaying(finalisation.flatMap(_.payment),
            (RoundCounted(c.roundId, c.win, next.remaining, c.at) +: finalisation.toSeq.flatMap(Reward.ending(s, _, c.at)))*)
        case Right(Settled.Late(_, payment)) => writePaying(payment, LatePaid(c.roundId, c.win, c.at))
  }

  /** The `rounds-<reward>` timer (FR-025, `Settlement.expire`); once finalised a repeat answers the same payment. */
  def finalise(c: Finalise): Effect[RewardAnswer] = rounds { (s, f) =>
    s.expiresAt.filter(_ => s.status == Active || s.status.isTerminal) match
      case None => skip(Skip.NotLive.reason)
      case Some(expiresAt) =>
        Settlement.expire(s.status, f.tally, f.terms.destination, expiresAt, c.at) match
          case Left(Skip.AlreadyFinalised) => skip(Skip.AlreadyFinalised.reason, s.finalPayment)
          case Left(other)                 => skip(other.reason)
          case Right(fin)                  => writePaying(fin.payment, Reward.ending(s, fin, c.at)*)
  }

  private def rounds(f: (RewardState, FreeRoundsState) => Effect[RewardAnswer]): Effect[RewardAnswer] = opened { s =>
    s.freeRounds.fold(refuse(NotFreeRounds, "the reward is not free rounds"))(f(s, _))
  }

  def get: ReadOnlyEffect[RewardState] = effects.reply(currentState)

object Reward
    extends EventSourcedEntity.Companion[Reward, RewardState, RewardEvent](
      componentId = ComponentId("reward"),
      stateSerializer = RewardsCodecs.serializer[RewardState]("reward-state"),
      eventSerializer = RewardsCodecs.serializer[RewardEvent]("reward-event")
    ):
  given Serializer[RewardState]  = RewardsCodecs.serializer[RewardState]("reward-reply")
  given Serializer[RewardAnswer] = RewardsCodecs.serializer[RewardAnswer]("reward-answer")
  given Serializer[Open]         = RewardsCodecs.serializer[Open]("reward-open")
  given Serializer[Select]       = RewardsCodecs.serializer[Select]("reward-select")
  given Serializer[Deselect]     = RewardsCodecs.serializer[Deselect]("reward-deselect")
  given Serializer[Activate]     = RewardsCodecs.serializer[Activate]("reward-activate")
  given Serializer[RefuseAward]  = RewardsCodecs.serializer[RefuseAward]("reward-refuse-award")
  given Serializer[Convert]      = RewardsCodecs.serializer[Convert]("reward-convert")
  given Serializer[Forfeit]      = RewardsCodecs.serializer[Forfeit]("reward-forfeit")
  given Serializer[Close]        = RewardsCodecs.serializer[Close]("reward-close")
  given Serializer[Expire]       = RewardsCodecs.serializer[Expire]("reward-expire")
  given Serializer[Cancel]       = RewardsCodecs.serializer[Cancel]("reward-cancel")
  given Serializer[Revoke]       = RewardsCodecs.serializer[Revoke]("reward-revoke")
  given Serializer[Switch]       = RewardsCodecs.serializer[Switch]("reward-switch")
  given Serializer[ProviderStatus] = RewardsCodecs.serializer[ProviderStatus]("reward-provider-status")
  given Serializer[RoundSettled] = RewardsCodecs.serializer[RoundSettled]("reward-round-settled")
  given Serializer[Finalise]     = RewardsCodecs.serializer[Finalise]("reward-finalise")

  def create(context: EventSourcedEntityContext) = new Reward(context)

  val open           = command("open")(_.open)
  val select         = command("select")(_.select)
  val deselect       = command("deselect")(_.deselect)
  val activate       = command("activate")(_.activate)
  val refuseAward    = command("refuse-award")(_.refuseAward)
  val convert        = command("convert")(_.convert)
  val forfeit        = command("forfeit")(_.forfeit)
  val close          = command("close")(_.close)
  val expire         = command("expire")(_.expire)
  val cancel         = command("cancel")(_.cancel)
  val revoke         = command("revoke")(_.revoke)
  val switch         = command("switch")(_.switch)
  val providerStatus = command("provider-status")(_.providerStatus)
  val roundSettled   = command("round-settled")(_.roundSettled)
  val finalise       = command("finalise")(_.finalise)
  val get            = query("get")(_.get)

  /** The pending selection's deadline: the offer's claim window days, else 3 (research R10). */
  def pendingDeadline(snapshot: RuleSnapshot, at: Instant): Instant =
    at.plus(Windows.days(snapshot.claimWindowDays, Windows.PendingDays), ChronoUnit.DAYS)

  /** The claim deadline (the wagering window days, else 30) or, for free rounds, their expiry (else 30). */
  def activeDeadline(snapshot: RuleSnapshot, freeRounds: Boolean, at: Instant): Instant =
    if freeRounds then at.plus(Windows.days(snapshot.freeSpinsExpiryDays, Windows.FreeRoundsDays), ChronoUnit.DAYS)
    else at.plus(Windows.days(snapshot.wageringWindowDays, Windows.WageringDays), ChronoUnit.DAYS)

  /** The cap's deadline: conversion plus the wagering window days, else 30 (FR-014). */
  def capDeadline(snapshot: RuleSnapshot, at: Instant): Instant =
    at.plus(Windows.days(snapshot.wageringWindowDays, Windows.WageringDays), ChronoUnit.DAYS)

  /** A finalisation's events: the amount, then `CLOSED` or `EXPIRED` as `Settlement` decided. */
  private[reward] def ending(s: RewardState, f: Finalisation, at: Instant): Seq[RewardEvent] =
    val end =
      if f.status == RewardStatus.Expired then RewardEvent.Expired(ExpireReason.FreeSpinsExpiredUnused, s.status, at)
      else RewardEvent.Closed(CloseReason.FreeSpinsFinalised, afterConversion = false, at)
    Seq(FreeRoundsFinalised(f.amount, at), end)

  private def ended(s: RewardState, status: RewardStatus, reason: String, at: Instant): RewardState =
    s.copy(status = status, terminatedAt = Some(at), terminationReason = Some(reason))

  private def onRounds(s: RewardState)(f: FreeRoundsState => FreeRoundsState): RewardState =
    s.copy(freeRounds = s.freeRounds.map(f))

  /** The only place a reward's state changes: pure, as replay needs. */
  def applied(s: RewardState, event: RewardEvent): RewardState = event match
    case Targeted(id, o, at) =>
      s.copy(opened = true, rewardId = id, playerId = o.playerId, offerId = o.offerId, offerName = o.offerName, offerType = o.offerType,
        status = RewardStatus.Available, payloadType = o.payloadType, faceValue = o.decision.amount,
        wageringRequirement = o.decision.wageringRequirement, wageringMultiplier = o.decision.wageringMultiplier,
        maxConvertible = o.decision.maxConvertible, currency = o.decision.currency.code, priority = o.priority, source = o.source,
        code = o.code, tag = o.tag, snapshot = o.snapshot, openedBy = o.by, openedAt = at,
        freeRounds = o.freeRounds.map(t => FreeRoundsState(t, Tally(t.spinCount))))
    case Selected(at, expiresAt) => s.copy(status = RewardStatus.Pending, selectedAt = Some(at), expiresAt = Some(expiresAt))
    case Deselected(_)           => s.copy(status = RewardStatus.Available, selectedAt = None, expiresAt = None)
    case Activated(award, expiresAt, wagering, at) =>
      s.copy(status = RewardStatus.Active, grantedAt = Some(at), expiresAt = Some(expiresAt), wageringExpiresAt = wagering,
        recordId = award.flatMap(_.recordId).orElse(s.recordId),
        wageringRequirement = award.fold(s.wageringRequirement)(_.wageringRequirement),
        faceValue = award.flatMap(_.amount).getOrElse(s.faceValue),
        wageringMultiplier = award.flatMap(_.wageringMultiplier).getOrElse(s.wageringMultiplier),
        maxConvertible = award.flatMap(_.maxConvertible).getOrElse(s.maxConvertible),
        currency = award.flatMap(_.currency).getOrElse(s.currency))
    case AwardRefused(code, _)        => s.copy(lastDecline = Some(code))
    case Converted(at, cap)           =>
      s.copy(status = RewardStatus.ConvertedPendingCap, convertedAt = Some(at), capExpiresAt = Some(cap), expiresAt = Some(cap))
    case e: RewardEvent.Forfeited     => ended(s, RewardStatus.Forfeited, e.reason.wire, e.at)
    case e: RewardEvent.Closed        => ended(s, RewardStatus.Closed, e.reason.wire, e.at)
    case e: RewardEvent.Expired       => ended(s, RewardStatus.Expired, e.reason.wire, e.at)
    case e: RewardEvent.Cancelled     => ended(s, RewardStatus.Cancelled, e.reason.wire, e.at)
    case e: RewardEvent.Revoked       => ended(s, RewardStatus.Revoked, e.mode.wire, e.at).copy(revoke = Some(Revocation(e.by, e.mode)))
    case Switched(step, priority, _)  => s.copy(switches = step, priority = priority.orElse(s.priority))
    case ProviderStatusRecorded(p, _) => onRounds(s)(_.copy(providerStatus = Some(p)))
    case RoundCounted(id, win, _, _)  => onRounds(s)(f => f.copy(tally = f.tally.counted(id, win)))
    case LatePaid(id, win, _)         => onRounds(s)(f => f.copy(tally = f.tally.late(id), latePaid = f.latePaid + (id -> win)))
    case FreeRoundsFinalised(amount, _) => onRounds(s)(f => f.copy(tally = f.tally.finalise(amount)))
