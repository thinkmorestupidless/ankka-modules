package io.eitheror.casino.programme.players

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/** Who the player is attributed to at the brand (FR-010). */
final case class Binding(affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    boundAt: Instant)

/** A deposit kept for the totals: its amount and when it occurred. */
final case class Kept(amount: Long, at: Instant)

/** Deposits in one currency: count, sum and the first three, as the original lists them. */
final case class DepositTotals(count: Int = 0, sum: Long = 0, first: Option[Kept] = None, second: Option[Kept] = None,
    third: Option[Kept] = None):
  def add(amount: Long, at: Instant): DepositTotals =
    val k = Some(Kept(amount, at))
    count match
      case 0 => copy(1, sum + amount, first = k)
      case 1 => copy(2, sum + amount, second = k)
      case 2 => copy(3, sum + amount, third = k)
      case n => copy(n + 1, sum + amount)

final case class Guard(factId: String, at: Instant)
final case class Fraud(bonusCosts: Int = 0, chargebacks: Int = 0)
final case class EarlyDay(reportingDay: String, currency: String, ggr: Long)
final case class Disagreement(factId: String, affiliate: String, link: String)
final case class ClosedAs(category: String, at: Instant)

/**
 * One player at one brand (data-model.md *PlayerAttribution*), id `<brand>/<pseudonym>`: the binding,
 * the lifetime first-deposit guard, totals, fraud counters, and nothing that names the player.
 */
final case class PlayerState(
    brand: String = "",
    pseudonym: String = "",
    binding: Option[Binding] = None,
    country: Option[String] = None,
    clickId: Option[String] = None,
    guard: Option[Guard] = None,
    deposits: Map[String, DepositTotals] = Map.empty,
    withdrawals: Map[String, Long] = Map.empty,
    completedWithdrawals: Map[String, Long] = Map.empty,
    fraud: Fraud = Fraud(),
    beforeBinding: Vector[EarlyDay] = Vector.empty,
    seen: Vector[String] = Vector.empty,
    disagreements: Vector[Disagreement] = Vector.empty,
    closed: Option[ClosedAs] = None
):
  def affiliate: Option[String] = binding.map(_.affiliate)

/**
 * What the entity recorded of a fact. `FirstDeposit(true)` is the one that pays CPA; `Ngr` an NGR input
 * as the ledger's type (`ggr`, `bonus-cost`, `chargeback`).
 */
enum Outcome:
  case Bound
  case Duplicate
  case Unattributed(reason: String)
  case FirstDeposit(qualifies: Boolean)
  case Deposit
  case Ngr(kind: String, amount: Long)
  case BeforeBinding
  case WithdrawalTotal
  case Ignored(reason: String)
  case Closed

/**
 * A fact to apply, with what the caller read for it: the brand's GGR source on the fact's day, and the
 * minimum deposit of a plan that pays CPA (`None` when the bound affiliate's plan pays none).
 */
final case class ApplyFact(fact: PlayerFact, ggrSource: String, minimumDeposit: Option[Long], imported: Boolean = false)

object PlayerAttribution extends EventSourcedEntity.Companion[PlayerAttribution, PlayerState, PlayerAttribution.Event](
      componentId = ComponentId("player-attribution"),
      stateSerializer = PlayerCodecs.state,
      eventSerializer = PlayerCodecs.event
    ):
  import PlayerCodecs.given

  /**
   * A fact applied, the binding it was applied to (none for an unattributed one), and what it recorded.
   * `imported`: a fact the original recorded, carried by the import (FR-039); the ledger already holds
   * what it wrote, so it writes nothing more and queues no postback.
   */
  final case class Applied(fact: PlayerFact, binding: Option[Binding], outcome: Outcome, imported: Boolean = false)
  type Event = Applied

  val MaxSeen = 1000

  /** What a fact records, from the state before it; pure, so the event alone replays it. */
  def decide(s: PlayerState, cmd: ApplyFact): Outcome =
    val f = cmd.fact
    import PlayerFact.*
    if s.seen.contains(f.factId) then Outcome.Duplicate
    else if f.kind == Registration then if s.binding.isDefined then Outcome.Duplicate else Outcome.Bound
    else if s.binding.isEmpty then Outcome.Unattributed("no_attribution")
    else
      f.kind match
        case FirstDeposit | Deposit =>
          val qualifies = s.guard.isEmpty && cmd.minimumDeposit.exists(f.amount >= _)
          if f.kind == FirstDeposit || qualifies then Outcome.FirstDeposit(qualifies) else Outcome.Deposit
        case k if ggrKinds(k) =>
          if f.beforeBinding then Outcome.BeforeBinding
          else if folds(k, cmd.ggrSource) then Outcome.Ngr("ggr", f.amount)
          else Outcome.Ignored("other_source")
        case BonusCost                            => Outcome.Ngr("bonus-cost", f.amount)
        case Chargeback                           => Outcome.Ngr("chargeback", f.amount)
        case WithdrawalCompleted                  => Outcome.WithdrawalTotal
        case WithdrawalReversed                   =>
          if f.reference.exists(s.completedWithdrawals.contains) then Outcome.WithdrawalTotal else Outcome.Ignored("no_completed_withdrawal")
        case WithdrawalRequested | WithdrawalFailed => Outcome.Ignored("no_total_changes")
        case PlayerClosed                         => Outcome.Closed
        case other                                => Outcome.Ignored(s"unknown_kind:$other")

  def next(s: PlayerState, e: Applied): PlayerState =
    val f = e.fact
    if e.outcome == Outcome.Duplicate then s
    else
      val seen = (s.seen :+ f.factId).takeRight(MaxSeen)
      val base = s.copy(brand = f.brand, pseudonym = f.pseudonym, seen = seen,
        disagreements = e.binding.filter(b => f.kind != PlayerFact.Registration && (b.affiliate != f.affiliate || b.link != f.link))
          .fold(s.disagreements)(_ => s.disagreements :+ Disagreement(f.factId, f.affiliate, f.link)))
      val currency = f.currency.getOrElse("")
      e.outcome match
        case Outcome.Bound =>
          base.copy(binding = e.binding, country = f.country, clickId = f.clickId)
        case Outcome.FirstDeposit(q) =>
          // A brand emits a `first-deposit` and a `deposit` for the same deposit (spec 012): the `deposit` counts it.
          val counted = if f.kind == PlayerFact.Deposit then base.deposits.updated(currency, base.deposits.getOrElse(currency, DepositTotals()).add(f.amount, f.occurredAt)) else base.deposits
          base.copy(deposits = counted, guard = if q then Some(Guard(f.factId, f.occurredAt)) else base.guard)
        case Outcome.Deposit =>
          base.copy(deposits = base.deposits.updated(currency, base.deposits.getOrElse(currency, DepositTotals()).add(f.amount, f.occurredAt)))
        case Outcome.Ngr("bonus-cost", _) => base.copy(fraud = base.fraud.copy(bonusCosts = base.fraud.bonusCosts + 1))
        case Outcome.Ngr("chargeback", _) => base.copy(fraud = base.fraud.copy(chargebacks = base.fraud.chargebacks + 1))
        case Outcome.BeforeBinding =>
          base.copy(beforeBinding = base.beforeBinding :+ EarlyDay(f.reportingDay.getOrElse(""), currency, f.amount))
        case Outcome.WithdrawalTotal if f.kind == PlayerFact.WithdrawalCompleted =>
          base.copy(
            withdrawals = base.withdrawals.updated(currency, base.withdrawals.getOrElse(currency, 0L) + f.amount),
            completedWithdrawals = f.reference.fold(base.completedWithdrawals)(r => base.completedWithdrawals.updated(r, f.amount))
          )
        case Outcome.WithdrawalTotal =>
          val total = math.max(0L, base.withdrawals.getOrElse(currency, 0L) - f.amount)
          base.copy(withdrawals = base.withdrawals.updated(currency, total),
            completedWithdrawals = f.reference.fold(base.completedWithdrawals)(base.completedWithdrawals - _))
        case Outcome.Closed => base.copy(country = None, clickId = None, closed = Some(ClosedAs(f.category.getOrElse(""), f.occurredAt)))
        case _              => base

  def entityId(brand: String, pseudonym: String): EntityId = EntityId(s"$brand/$pseudonym")

  override def snapshotEvery: Option[Int] = Some(100)

  def create(context: EventSourcedEntityContext) = new PlayerAttribution(context)

  val applyFact = command("apply")(_.applyFact)
  val get       = query("get")(_.get)

object PlayerCodecs:
  val state: Serializer[PlayerState]             = ProgrammeCodecs.serializer[PlayerState]("player")
  val event: Serializer[PlayerAttribution.Event] = ProgrammeCodecs.serializer[PlayerAttribution.Applied]("player-applied")
  given Serializer[ApplyFact]                    = ProgrammeCodecs.serializer[ApplyFact]("apply-fact")
  given Serializer[Outcome]                      = ProgrammeCodecs.serializer[Outcome]("outcome")

/**
 * Records each fact once against the player's binding (FR-010 to FR-014, FR-038). A fact naming another
 * affiliate or link than the binding is applied to the binding and kept as a disagreement.
 */
final class PlayerAttribution(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[PlayerState, PlayerAttribution.Event]:
  def emptyState: PlayerState = PlayerState()

  def applyEvent(event: PlayerAttribution.Event): PlayerState = PlayerAttribution.next(currentState, event)

  def applyFact(cmd: ApplyFact): Effect[Outcome] =
    val s = currentState
    PlayerAttribution.decide(s, cmd) match
      case Outcome.Duplicate => effects.reply(Outcome.Duplicate)
      case o =>
        val f       = cmd.fact
        val binding = if o == Outcome.Bound then Some(Binding(f.affiliate, f.link, f.campaign, f.subIds, f.clickId, f.occurredAt)) else s.binding
        effects.persist(PlayerAttribution.Applied(f, binding, o, cmd.imported)).thenReply(_ => o)

  def get: ReadOnlyEffect[PlayerState] = effects.reply(currentState)
