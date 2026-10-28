package io.eitheror.casino.rg.profile

import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/** Who made a change (FR-027): `player`, `operator` with the operator's id, `system` or `third_party`. */
final case class Actor(kind: String, id: Option[String] = None):
  def isOperator: Boolean = kind == Actor.OperatorKind

object Actor:
  val OperatorKind = "operator"
  val Player       = Actor("player")
  val System       = Actor("system")
  val ThirdParty   = Actor("third_party")
  def operator(id: String): Actor = Actor(OperatorKind, Some(id))

final case class Limit(key: LimitKey, value: Long, setAt: Instant, actor: Actor)

/** A staged change; a value of none is a removal (FR-002). */
final case class Pending(key: LimitKey, value: Option[Long], appliesAt: Instant, actor: Actor)

final case class Break(until: Instant, setAt: Instant, caseId: String, actor: Actor)

final case class Exclusion(permanent: Boolean, until: Option[Instant], since: Instant, trigger: String, caseId: String)

final case class ReturnRequest(at: Instant, reinstatesAt: Instant, actor: Actor)

final case class Ack(sessionId: String, at: Instant)

final case class CaseNote(at: Instant, operatorId: String, text: Personal[String])

/** An intervention (FR-026): open until closed, with a reason. Notes and the close's note are personal. */
final case class RgCase(
    caseId: String,
    caseType: String,
    trigger: String,
    status: String,
    openedAt: Instant,
    closesAt: Option[Instant] = None,
    closedAt: Option[Instant] = None,
    closeReason: Option[String] = None,
    closeNote: Option[Personal[String]] = None,
    limitValue: Option[Long] = None,
    period: Option[String] = None,
    permanent: Option[Boolean] = None,
    notes: Vector[CaseNote] = Vector.empty
):
  def open: Boolean = status == RgCase.Open

object RgCase:
  val Open   = "open"
  val Closed = "closed"

  val SelfExclusion = "self_exclusion"
  val CoolOff       = "cool_off"
  val Types: Set[String] =
    Set(SelfExclusion, CoolOff, "deposit_limit", "loss_limit", "session_limit", "reality_check", "account_block")

  val Expired = "expired"
  val Lifted  = "lifted"
  val ClosedReason = "closed"

/** One line of the player's history (FR-027), in the original's `RgHistoryEntry` shape. */
final case class HistoryEntry(
    eventType: String,
    limitType: Option[String],
    previousValue: Option[String],
    newValue: Option[String],
    actor: String,
    occurredAt: Instant,
    description: String
)

final case class ProfileState(
    playerId: String = "",
    limits: Map[String, Limit] = Map.empty,
    pending: Map[String, Pending] = Map.empty,
    break: Option[Break] = None,
    exclusion: Option[Exclusion] = None,
    returnRequest: Option[ReturnRequest] = None,
    playersTold: Option[String] = None,
    realityCheck: Option[Int] = None,
    acknowledged: Option[Ack] = None,
    cases: Vector[RgCase] = Vector.empty,
    history: Vector[HistoryEntry] = Vector.empty
):
  def limit(key: LimitKey): Option[Limit] = limits.get(key.id)

  /** A break stands until its end (the timer closes it within a minute of that, FR-017). */
  def onBreak(now: Instant): Option[Break] = break.filter(_.until.isAfter(now))

  def excluded: Boolean = exclusion.isDefined

  def caseOf(caseId: String): Option[RgCase] = cases.find(_.caseId == caseId)

  /** The money limits of one currency, by type. */
  def moneyLimits(currency: String): Map[LimitType, Long] =
    limits.values.collect { case l if l.key.currency.contains(currency) => l.key.kind -> l.value }.toMap

  def sessionLimit: Option[Long] = limits.get(LimitType.SessionMinutes.name).map(_.value)

object PlayersStatus:
  val SelfExcluded = "self_excluded"
  val Active       = "active"

// ── events ──────────────────────────────────────────────────────────────────

sealed trait ProfileEvent:
  def at: Instant

object ProfileEvent:
  final case class LimitSet(playerId: String, key: LimitKey, value: Long, previous: Option[Long], actor: Actor, at: Instant) extends ProfileEvent
  final case class LimitRemoved(key: LimitKey, previous: Long, actor: Actor, at: Instant)                                   extends ProfileEvent
  final case class PendingStaged(playerId: String, key: LimitKey, value: Option[Long], appliesAt: Instant, actor: Actor, at: Instant)
      extends ProfileEvent
  final case class PendingCancelled(key: LimitKey, at: Instant)                                           extends ProfileEvent
  final case class PendingApplied(key: LimitKey, value: Option[Long], previous: Option[Long], at: Instant) extends ProfileEvent
  /** `replaces` when an operator moves a standing break's end: the case stays the one opened. */
  final case class BreakSet(playerId: String, until: Instant, caseId: String, actor: Actor, at: Instant, replaces: Boolean = false)
      extends ProfileEvent
  final case class BreakLifted(operatorId: String, reason: Personal[String], at: Instant) extends ProfileEvent
  final case class BreakEnded(at: Instant)                                                extends ProfileEvent
  final case class Excluded(playerId: String, permanent: Boolean, until: Option[Instant], trigger: String, caseId: String, actor: Actor,
      at: Instant) extends ProfileEvent
  final case class ReturnRequested(reinstatesAt: Instant, actor: Actor, at: Instant) extends ProfileEvent
  final case class Reinstated(at: Instant)                                           extends ProfileEvent
  final case class PlayersAcknowledged(status: String, at: Instant)                  extends ProfileEvent
  final case class RealityCheckSet(interval: Option[Int], previous: Option[Int], at: Instant) extends ProfileEvent
  final case class Acknowledged(sessionId: String, at: Instant)                      extends ProfileEvent
  final case class CaseOpened(playerId: String, rgCase: RgCase, actor: Actor, at: Instant) extends ProfileEvent
  final case class CaseNoted(caseId: String, operatorId: String, text: Personal[String], at: Instant) extends ProfileEvent
  final case class CaseClosed(caseId: String, caseType: String, reason: String, note: Option[Personal[String]], actor: Actor, at: Instant)
      extends ProfileEvent
  final case class ThirdPartyExcluded(caseId: String, result: String, at: Instant) extends ProfileEvent
  final case class Erased(at: Instant)                                             extends ProfileEvent

// ── commands ────────────────────────────────────────────────────────────────

/** `appliesAt` is when a staged change applies (now + 24 h); `minimumSessionMinutes` the brand's (FR-029). */
final case class SetLimit(playerId: String, limitType: String, currency: Option[String], value: Option[String], actor: Actor,
    minimumSessionMinutes: Int, at: Instant, appliesAt: Instant)
final case class RemoveLimit(playerId: String, limitType: String, currency: Option[String], actor: Actor, at: Instant, appliesAt: Instant)
final case class ApplyPending(keyId: String, appliesAt: Instant, at: Instant)
final case class SetBreak(playerId: String, until: Instant, actor: Actor, maxBreakDays: Int, at: Instant)
final case class LiftBreak(operatorId: String, reason: String, at: Instant)
final case class EndBreak(until: Instant, at: Instant)
/** `period`: `permanent`, `P183D` or `183d` (the original's `parseDays`). */
final case class Exclude(playerId: String, period: String, actor: Actor, at: Instant)
final case class RequestReturn(actor: Actor, at: Instant, reinstatesAt: Instant)
final case class Reinstate(reinstatesAt: Instant, at: Instant)
final case class PlayersAck(status: String, at: Instant)
final case class SetRealityCheck(interval: Option[Int], at: Instant)
final case class Acknowledge(sessionId: String, own: Boolean, at: Instant)
final case class OpenCase(playerId: String, caseType: String, note: String, operatorId: String, at: Instant)
final case class NoteCase(caseId: String, text: String, operatorId: String, at: Instant)
final case class CloseCase(caseId: String, reason: String, operatorId: String, at: Instant)
final case class RecordThirdParty(playerId: String, result: String, at: Instant)
final case class Erase(at: Instant)

/** A refusal: its code and the fields the routes name with it (`minimumMinutes`, `endsAt`, …). */
final case class Refusal(code: String, numbers: Map[String, Long] = Map.empty, times: Map[String, Instant] = Map.empty)

/** The profile as it stands after a command, a refusal, and the case a command opened or changed. */
final case class ProfileAnswer(state: ProfileState, refusal: Option[Refusal] = None, caseId: Option[String] = None)
