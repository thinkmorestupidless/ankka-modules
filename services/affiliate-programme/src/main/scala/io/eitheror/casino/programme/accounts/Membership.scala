package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/** How an application came: the public form, an email invitation, an invite link, a manager, or the import. */
final case class Application(appliedAt: Instant, termsVersion: Option[String], source: String, parent: Option[String] = None)

/**
 * An account's standing at one brand (data-model.md *Membership*), id `<affiliate>/<brand>`. `status`
 * is none before the first application.
 */
final case class MembershipState(
    affiliate: String = "",
    brand: String = "",
    status: Option[String] = None,
    plan: Option[String] = None,
    revShareOverride: Option[Int] = None,
    application: Option[Application] = None,
    suspendedBy: Option[String] = None,
    rejectedReason: Option[String] = None,
    since: Option[Instant] = None
):
  def exists: Boolean = status.isDefined
  def active: Boolean = status.contains(Membership.Active)
  /** Pending or active: an application from the same email is a duplicate (FR-003). */
  def holds: Boolean = status.exists(s => s == Membership.Pending || s == Membership.Active)

final case class Apply(affiliate: String, brand: String, application: Application)
final case class Approve(plan: String, by: String, at: Instant)
final case class Reject(reason: Option[String], by: String, at: Instant)
/** `kind` is who acted: `operator` (a manager) or `ancestor` (an affiliate above in the network). */
final case class Suspend(by: String, kind: String, at: Instant)
final case class Reactivate(by: String, kind: String, at: Instant)
final case class ActivateInvited(affiliate: String, brand: String, plan: String, application: Application, by: String)
final case class SetPlan(plan: String, by: String, at: Instant)
final case class SetRevShareOverride(bp: Option[Int], by: String, at: Instant)
/** A membership carried from the original (FR-039), its status as it stood. */
final case class ImportMembership(state: MembershipState)

object Membership extends EventSourcedEntity.Companion[Membership, MembershipState, Membership.Event](
      componentId = ComponentId("membership"),
      stateSerializer = MembershipCodecs.state,
      eventSerializer = MembershipCodecs.event
    ):
  val Pending   = "pending"
  val Active    = "active"
  val Suspended = "suspended"
  val Rejected  = "rejected"

  val Operator = "operator"
  val Ancestor = "ancestor"

  sealed trait Event:
    def at: Instant
  final case class Applied(affiliate: String, brand: String, application: Application) extends Event:
    def at: Instant = application.appliedAt
  final case class Approved(plan: String, by: String, at: Instant) extends Event
  final case class ApplicationRejected(reason: Option[String], by: String, at: Instant) extends Event
  final case class MembershipSuspended(by: String, kind: String, at: Instant) extends Event
  final case class MembershipReactivated(by: String, kind: String, at: Instant) extends Event
  final case class ActivatedInvited(affiliate: String, brand: String, plan: String, application: Application, by: String) extends Event:
    def at: Instant = application.appliedAt
  final case class PlanSet(plan: String, by: String, at: Instant) extends Event
  final case class RevShareOverrideSet(bp: Option[Int], by: String, at: Instant) extends Event
  /** No notice is sent and no link made for it: the affiliate already had both in the original. */
  final case class Imported(state: MembershipState) extends Event:
    def at: Instant = state.since.orElse(state.application.map(_.appliedAt)).getOrElse(Instant.EPOCH)

  def next(s: MembershipState, e: Event): MembershipState = e match
    case Applied(aff, brand, app) =>
      s.copy(affiliate = aff, brand = brand, status = Some(Pending), application = Some(app), rejectedReason = None, since = Some(app.appliedAt))
    case Approved(plan, _, at)       => s.copy(status = Some(Active), plan = Some(plan), since = Some(at))
    case ApplicationRejected(reason, _, at)     => s.copy(status = Some(Rejected), rejectedReason = reason, since = Some(at))
    case MembershipSuspended(_, kind, at)      => s.copy(status = Some(Suspended), suspendedBy = Some(kind), since = Some(at))
    case MembershipReactivated(_, _, at)       => s.copy(status = Some(Active), suspendedBy = None, since = Some(at))
    case ActivatedInvited(aff, brand, plan, app, _) =>
      s.copy(affiliate = aff, brand = brand, status = Some(Active), plan = Some(plan), application = Some(app), since = Some(app.appliedAt))
    case PlanSet(plan, _, _)         => s.copy(plan = Some(plan))
    case RevShareOverrideSet(bp, _, _) => s.copy(revShareOverride = bp)
    case Imported(state)             => state

  /** The original's refusal (`S/OperatorRoutes.scala:1392`), statuses as the programme spells them. */
  def mustBe(from: String, current: Option[String]): String = s"must be $from, currently ${current.getOrElse("none")}"

  def entityId(affiliate: String, brand: String): EntityId = EntityId(s"$affiliate/$brand")

  import MembershipCodecs.given

  def create(context: EventSourcedEntityContext) = new Membership(context)

  val applyFor        = command("apply")(_.applyFor)
  val approve         = command("approve")(_.approve)
  val reject          = command("reject")(_.reject)
  val suspend         = command("suspend")(_.suspend)
  val reactivate      = command("reactivate")(_.reactivate)
  val activateInvited = command("activate-invited")(_.activateInvited)
  val setPlan         = command("set-plan")(_.setPlan)
  val setRevShareOverride = command("set-revshare-override")(_.setRevShareOverride)
  val importMembership = command("import")(_.importMembership)
  val get             = query("get")(_.get)

object MembershipCodecs:
  val state: Serializer[MembershipState] = ProgrammeCodecs.serializer[MembershipState]("membership")
  val event: Serializer[Membership.Event] = ProgrammeCodecs.serializer[Membership.Event]("membership-event")
  given Serializer[Apply]               = ProgrammeCodecs.serializer[Apply]("apply")
  given Serializer[Approve]             = ProgrammeCodecs.serializer[Approve]("approve")
  given Serializer[Reject]              = ProgrammeCodecs.serializer[Reject]("reject")
  given Serializer[Suspend]             = ProgrammeCodecs.serializer[Suspend]("suspend")
  given Serializer[Reactivate]          = ProgrammeCodecs.serializer[Reactivate]("reactivate")
  given Serializer[ActivateInvited]     = ProgrammeCodecs.serializer[ActivateInvited]("activate-invited")
  given Serializer[SetPlan]             = ProgrammeCodecs.serializer[SetPlan]("set-plan")
  given Serializer[SetRevShareOverride] = ProgrammeCodecs.serializer[SetRevShareOverride]("set-revshare-override")
  given Serializer[ImportMembership]    = ProgrammeCodecs.serializer[ImportMembership]("import-membership")

import Membership.*

/**
 * The transitions of FR-002, and nothing else: none → pending and rejected → pending on application,
 * pending → active or rejected, active ↔ suspended; an email invitation makes none → active. Any other is
 * refused naming the status. An operator's suspension stands over an ancestor's reactivation (FR-029).
 */
final class Membership(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[MembershipState, Membership.Event]:
  def emptyState: MembershipState = MembershipState()

  def applyEvent(event: Membership.Event): MembershipState = Membership.next(currentState, event)

  private def from(allowed: Set[String])(persist: => Membership.Event): Effect[MembershipState] =
    currentState.status match
      case Some(s) if allowed(s) => effects.persist(persist).thenReplyState
      case current               => effects.error(mustBe(allowed.toVector.sorted.mkString(" or "), current), ErrorCode.Conflict)

  def applyFor(cmd: Apply): Effect[MembershipState] =
    currentState.status match
      case None | Some(Rejected) => effects.persist(Applied(cmd.affiliate, cmd.brand, cmd.application)).thenReplyState
      case Some(current)         => effects.error(s"already_applied: the membership is $current", ErrorCode.Conflict)

  def approve(cmd: Approve): Effect[MembershipState] = from(Set(Pending))(Approved(cmd.plan, cmd.by, cmd.at))

  def reject(cmd: Reject): Effect[MembershipState] = from(Set(Pending))(ApplicationRejected(cmd.reason, cmd.by, cmd.at))

  /**
   * Active → suspended; and an operator's suspension over an ancestor's, recorded as the operator's, so
   * the ancestor's reactivation is refused whichever suspended first (FR-029).
   */
  def suspend(cmd: Suspend): Effect[MembershipState] =
    if currentState.status.contains(Suspended) && cmd.kind == Operator && currentState.suspendedBy.contains(Ancestor) then
      effects.persist(MembershipSuspended(cmd.by, cmd.kind, cmd.at)).thenReplyState
    else from(Set(Active))(MembershipSuspended(cmd.by, cmd.kind, cmd.at))

  def reactivate(cmd: Reactivate): Effect[MembershipState] =
    if currentState.status.contains(Suspended) && cmd.kind == Ancestor && currentState.suspendedBy.contains(Operator) then
      effects.error("suspended_by_operator: only a manager reactivates this membership", ErrorCode.Conflict)
    else from(Set(Suspended))(MembershipReactivated(cmd.by, cmd.kind, cmd.at))

  /** The email invitation's membership, active at once (FR-027); a membership already held is refused naming it. */
  def activateInvited(cmd: ActivateInvited): Effect[MembershipState] =
    currentState.status match
      case None | Some(Rejected) => effects.persist(ActivatedInvited(cmd.affiliate, cmd.brand, cmd.plan, cmd.application, cmd.by)).thenReplyState
      case Some(current)         => effects.error(s"already_applied: the membership is $current", ErrorCode.Conflict)

  def setPlan(cmd: SetPlan): Effect[MembershipState] =
    if !currentState.exists then effects.error("not_a_member", ErrorCode.Conflict)
    else if currentState.plan.contains(cmd.plan) then effects.reply(currentState)
    else effects.persist(PlanSet(cmd.plan, cmd.by, cmd.at)).thenReplyState

  def setRevShareOverride(cmd: SetRevShareOverride): Effect[MembershipState] =
    if !currentState.exists then effects.error("not_a_member", ErrorCode.Conflict)
    else if cmd.bp.exists(b => b < 0 || b > 10_000) then effects.error("revShareOverride must be a number between 0 and 100", ErrorCode.BadRequest)
    else if currentState.revShareOverride == cmd.bp then effects.reply(currentState)
    else effects.persist(RevShareOverrideSet(cmd.bp, cmd.by, cmd.at)).thenReplyState

  /** Once: a membership already applied for, or imported before, is left as it is. */
  def importMembership(c: ImportMembership): Effect[MembershipState] =
    if currentState.exists then effects.reply(currentState) else effects.persist(Imported(c.state)).thenReplyState

  def get: ReadOnlyEffect[MembershipState] = effects.reply(currentState)
