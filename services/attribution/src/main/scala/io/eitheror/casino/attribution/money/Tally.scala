package io.eitheror.casino.attribution.money

import io.eitheror.casino.attribution.money.RealMoney.Leg

import java.time.Instant

/**
 * One player's real-money stake and win for one currency on one reporting day (data-model.md *DayTally*;
 * research R10), pure. `DayTally`, the entity, records what this decides. A leg after the close is an
 * adjustment, never a changed daily figure.
 */
object Tally:
  val MaxSeen = 2000

  final case class Round(stake: Long = 0, win: Long = 0, emittedStake: Long = 0, emittedWin: Long = 0)

  final case class State(
      player: String = "",
      currency: String = "",
      day: String = "",
      stake: Long = 0,
      win: Long = 0,
      seen: Vector[String] = Vector.empty,
      rounds: Map[String, Round] = Map.empty,
      closed: Option[Instant] = None,
      adjustments: Int = 0
  ):
    def ggr: Long = stake - win

  /** A round's movement since its last `ggr-round` fact; `first` when no fact was made for the round yet. */
  final case class RoundDelta(stake: Long, win: Long, first: Boolean)

  sealed trait Event:
    def player: String
    def currency: String
    def day: String

  /** A leg on an open day; a settling leg carries the round's movement since its last fact. */
  final case class LegRecorded(player: String, currency: String, day: String, kind: String, roundId: String, dedupeId: String,
      amount: Long, at: Instant, round: Option[RoundDelta]) extends Event

  /** A leg on a closed day: `ggr` is what it changes the day's GGR by (FR-016). */
  final case class Adjusted(player: String, currency: String, day: String, kind: String, roundId: String, dedupeId: String,
      ggr: Long, at: Instant) extends Event

  final case class Closed(player: String, currency: String, day: String, at: Instant, stake: Long, win: Long) extends Event:
    def moved: Boolean = stake != 0 || win != 0

  /** A leg's command: the tally's identity, since the first leg makes it. */
  final case class LegCmd(player: String, currency: String, day: String, kind: String, roundId: String, dedupeId: String,
      amount: Long, at: Instant)

  def leg(state: State, cmd: LegCmd): Vector[Event] =
    if state.seen.contains(cmd.dedupeId) then Vector.empty
    else if state.closed.isDefined then
      val delta = cmd.kind match
        case Leg.Stake    => cmd.amount
        case Leg.Win      => -cmd.amount
        case Leg.Rollback => -cmd.amount
        case _            => 0L
      if delta == 0 then Vector.empty
      else Vector(Adjusted(cmd.player, cmd.currency, cmd.day, cmd.kind, cmd.roundId, cmd.dedupeId, delta, cmd.at))
    else
      val round = moved(state.rounds.getOrElse(cmd.roundId, Round()), cmd.kind, cmd.amount)
      val delta = Option.when(Leg.settling.contains(cmd.kind))(
        RoundDelta(round.stake - round.emittedStake, round.win - round.emittedWin, round.emittedStake == 0 && round.emittedWin == 0))
      Vector(LegRecorded(cmd.player, cmd.currency, cmd.day, cmd.kind, cmd.roundId, cmd.dedupeId, cmd.amount, cmd.at, delta))

  def close(state: State, at: Instant): Vector[Event] =
    if state.closed.isDefined then Vector.empty
    else Vector(Closed(state.player, state.currency, state.day, at, state.stake, state.win))

  private def moved(round: Round, kind: String, amount: Long): Round = kind match
    case Leg.Stake    => round.copy(stake = round.stake + amount)
    case Leg.Win      => round.copy(win = round.win + amount)
    case Leg.Rollback => round.copy(stake = round.stake - amount)
    case _            => round

  def apply(state: State, event: Event): State =
    val named = state.copy(player = event.player, currency = event.currency, day = event.day)
    event match
      case e: LegRecorded =>
        val round  = moved(named.rounds.getOrElse(e.roundId, Round()), e.kind, e.amount)
        val marked = e.round.fold(round)(_ => round.copy(emittedStake = round.stake, emittedWin = round.win))
        val totals = e.kind match
          case Leg.Stake    => named.copy(stake = named.stake + e.amount)
          case Leg.Win      => named.copy(win = named.win + e.amount)
          case Leg.Rollback => named.copy(stake = named.stake - e.amount)
          case _            => named
        totals.copy(seen = (named.seen :+ e.dedupeId).takeRight(MaxSeen), rounds = named.rounds.updated(e.roundId, marked))
      case e: Adjusted => named.copy(seen = (named.seen :+ e.dedupeId).takeRight(MaxSeen), adjustments = named.adjustments + 1)
      case e: Closed   => named.copy(closed = Some(e.at))
