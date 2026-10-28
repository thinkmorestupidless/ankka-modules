package io.eitheror.casino.attribution.record

import io.eitheror.casino.attribution.{Binding, MoneyEvent, Touch}

import java.time.{Duration, Instant}

/**
 * The attribution record's rules (data-model.md *AttributionRecord*; research R8), pure: what each command
 * decides given the record, and how each event changes it. `AttributionRecord`, the entity, records what
 * this decides. Every event carries what its fact needs, so a publisher over the events never reads the
 * record.
 */
object Record:
  val MaxTouches  = 100
  val MaxPending  = 200
  val PendingLife = Duration.ofHours(48)
  val MaxEmitted  = 1000

  final case class Refusal(tag: String, reason: String, at: Instant)
  final case class Closure(category: String, at: Instant)

  final case class State(
      registeredAt: Option[Instant] = None,
      country: Option[String] = None,
      binding: Option[Binding] = None,
      refused: Option[Refusal] = None,
      touches: Vector[Touch] = Vector.empty,
      firstDepositAt: Option[Instant] = None,
      firstDeposit: Option[String] = None,
      pending: Vector[MoneyEvent] = Vector.empty,
      emitted: Vector[String] = Vector.empty,
      disputes: Set[String] = Set.empty,
      closed: Option[Closure] = None,
      erased: Option[Instant] = None
  ):
    def bound: Boolean = binding.isDefined
    def seen(dedupeId: String): Boolean = emitted.contains(dedupeId) || pending.exists(_.dedupeId == dedupeId)

  /** The categories a `player-closed` fact names (FR-027). */
  object Category:
    val Closed       = "closed"
    val SelfExcluded = "self-excluded"

  // ── events ──────────────────────────────────────────────────────────────

  sealed trait Event

  /** The player's registration; the bonus code window counts from `at`. */
  final case class Registered(at: Instant, country: Option[String]) extends Event

  /**
   * The binding, set once (FR-007), with the pending money at or after it (emitted at the binding) and
   * which of them, if any, was the player's first deposit.
   */
  final case class Bound(binding: Binding, country: Option[String], pending: Vector[MoneyEvent], firstDeposit: Option[String])
      extends Event

  /** A tag refused at registration, kept with its reason (FR-005); the player is organic. */
  final case class TagRefused(tag: String, reason: String, at: Instant) extends Event

  final case class TouchRecorded(touch: Touch) extends Event

  /** Money told of while unbound (research R8); `first` when it set the first deposit. */
  final case class PendingRecorded(event: MoneyEvent, first: Boolean) extends Event

  final case class DepositRecorded(event: MoneyEvent, first: Boolean, binding: Binding) extends Event
  final case class ChargebackRecorded(event: MoneyEvent, binding: Binding) extends Event
  final case class WithdrawalRecorded(event: MoneyEvent, binding: Binding) extends Event
  final case class BonusCostRecorded(event: MoneyEvent, binding: Binding) extends Event

  /** The player closed for the protocol, once (FR-027): `binding` none for an organic player. */
  final case class Closed(category: String, at: Instant, binding: Option[Binding]) extends Event

  final case class Erased(at: Instant) extends Event

  // ── commands ────────────────────────────────────────────────────────────

  /**
   * `player_registered`, as `PlayersFacts` resolved it: the binding the tag or the code made (with the
   * pseudonym), or the refused tag, and the touches beside it.
   */
  final case class Register(at: Instant, country: Option[String], binding: Option[Binding], refused: Option[Refusal],
      touches: Vector[Touch] = Vector.empty)

  /** Why `bind` did nothing. */
  val AlreadyBound = "already_bound"

  /**
   * The events a registration records. A repeat (the fact's player id is the record's id) changes no
   * binding: a redelivered fact records nothing, and a tag it carries that the record has not seen is a
   * later touch (FR-009).
   */
  def register(state: State, cmd: Register): Vector[Event] =
    if state.registeredAt.isDefined then
      val presented = cmd.binding.map(b => Touch(Touch.Tag, b.link, cmd.at, Touches.Later))
        .filterNot(t => state.binding.exists(_.link == t.value))
        .orElse(cmd.refused.map(r => Touch(Touch.Tag, r.tag, cmd.at, r.reason)).filterNot(t => state.refused.exists(_.tag == t.value)))
      presented.filterNot(t => state.touches.exists(o => o.kind == t.kind && o.value == t.value)).map(TouchRecorded(_)).toVector
    else
      val registered = Registered(cmd.at, cmd.country)
      val binding = cmd.binding match
        case Some(b) if !state.bound => Vector(bound(state, b, cmd.country))
        case Some(b)                 => Vector(TouchRecorded(Touch(Touch.Tag, b.link, cmd.at, AlreadyBound)))
        case None                    => Vector.empty
      val refused = cmd.refused.map(r => TagRefused(r.tag, r.reason, r.at)).toVector
      registered +: (binding ++ refused ++ cmd.touches.map(TouchRecorded(_)))

  /** A binding after the registration (a later code, research R7); refused when the record is bound. */
  def bind(state: State, binding: Binding): Either[String, Vector[Event]] =
    if state.bound then Left(AlreadyBound) else Right(Vector(bound(state, binding, state.country)))

  private def bound(state: State, binding: Binding, country: Option[String]): Bound =
    val kept = state.pending.filter(e => !e.at.isBefore(binding.boundAt))
    Bound(binding, country, kept, state.firstDeposit.filter(id => kept.exists(_.dedupeId == id)))

  def touch(touch: Touch): Vector[Event] = Vector(TouchRecorded(touch))

  /**
   * A movement of the player's money: recorded once per dedupe id; pending while unbound; a deposit sets
   * the first deposit whether or not the record is bound (FR-015); a second chargeback for a payment
   * already disputed under the same reference is nothing.
   */
  def money(state: State, event: MoneyEvent): Vector[Event] =
    if state.seen(event.dedupeId) then Vector.empty
    else if event.kind == MoneyEvent.Chargeback && state.disputes.contains(event.reference) then Vector.empty
    else
      val first = event.kind == MoneyEvent.Deposit && state.firstDepositAt.isEmpty
      state.binding match
        case None => Vector(PendingRecorded(event, first))
        case Some(b) =>
          event.kind match
            case MoneyEvent.Deposit    => Vector(DepositRecorded(event, first, b))
            case MoneyEvent.Chargeback => Vector(ChargebackRecorded(event, b))
            case MoneyEvent.BonusCost  => Vector(BonusCostRecorded(event, b))
            case _                     => Vector(WithdrawalRecorded(event, b))

  def close(state: State, category: String, at: Instant): Vector[Event] =
    if state.closed.isDefined then Vector.empty else Vector(Closed(category, at, state.binding))

  /** Erasure (FR-028): the record keeps its binding; a `player-closed` is recorded when none was. */
  def erase(state: State, at: Instant): Vector[Event] =
    if state.erased.isDefined then Vector.empty
    else Erased(at) +: close(state, Category.Closed, at)

  // ── state ───────────────────────────────────────────────────────────────

  def apply(state: State, event: Event): State = event match
    case Registered(at, country) => state.copy(registeredAt = Some(at), country = country)
    case Bound(binding, _, kept, _) =>
      state.copy(binding = Some(binding), pending = Vector.empty, emitted = remember(state.emitted, kept.map(_.dedupeId)))
    case TagRefused(tag, reason, at) => state.copy(refused = Some(Refusal(tag, reason, at)))
    case TouchRecorded(t)            => state.copy(touches = (state.touches :+ t).takeRight(MaxTouches))
    case PendingRecorded(e, first) =>
      val fresh = (state.pending :+ e).filter(p => !p.at.isBefore(e.at.minus(PendingLife))).takeRight(MaxPending)
      deposit(state.copy(pending = fresh), e, first)
    case DepositRecorded(e, first, _) => deposit(state.copy(emitted = remember(state.emitted, Vector(e.dedupeId))), e, first)
    case ChargebackRecorded(e, _) =>
      state.copy(emitted = remember(state.emitted, Vector(e.dedupeId)), disputes = state.disputes + e.reference)
    case WithdrawalRecorded(e, _) => state.copy(emitted = remember(state.emitted, Vector(e.dedupeId)))
    case BonusCostRecorded(e, _)  => state.copy(emitted = remember(state.emitted, Vector(e.dedupeId)))
    case Closed(category, at, _)  => state.copy(closed = Some(Closure(category, at)))
    case Erased(at)               => state.copy(erased = Some(at))

  private def deposit(state: State, e: MoneyEvent, first: Boolean): State =
    if first then state.copy(firstDepositAt = Some(e.at), firstDeposit = Some(e.dedupeId)) else state

  private def remember(emitted: Vector[String], ids: Vector[String]): Vector[String] = (emitted ++ ids).takeRight(MaxEmitted)
