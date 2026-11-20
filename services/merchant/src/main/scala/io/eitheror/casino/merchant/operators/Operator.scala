package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.merchant.MerchantCodecs
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant
import scala.concurrent.duration.*

/**
 * An operator of the payments backoffice (FR-027, FR-028), event sourced under the realm's user id:
 * what this service told the realm (the role, the level, its ancestors) and what the realm's sync read
 * back (enabled). The email and names are `Personal` values under the operator's subject (research R12).
 */
final case class OperatorState(
    id: String,
    email: Option[Personal[String]] = None,
    role: String = "",
    scopeLevel: String = "",
    scopeId: String = "",
    ancestors: Vector[String] = Vector.empty,
    firstName: Option[Personal[String]] = None,
    lastName: Option[Personal[String]] = None,
    pending: Boolean = true,
    enabled: Boolean = true,
    removed: Boolean = false,
    createdAt: Option[Instant] = None,
    createdBy: String = "",
    // spec 030 H6: the realm roles last put in force for each brand (spec 032: read by nothing new)
    brandRoles: Map[String, Set[String]] = Map.empty,
    // spec 032: what each brand last gave, by the brand's names without the brand, and how far it got
    givings: Map[String, Giving] = Map.empty
):
  def exists: Boolean = createdAt.isDefined && !removed

/** How far a brand's giving got: asked and not yet in the realm, in force, or refused by the realm. */
enum GivingState:
  case Pending
  case InForce
  case Failed(reason: String)

/** A brand's giving (spec 032): the roles it asked for, the brand's own count of givings, and when it last changed. */
final case class Giving(roles: Set[String], version: Long, state: GivingState, at: Instant)

enum OperatorEvent:
  case Created(email: Personal[String], role: String, scopeLevel: String, scopeId: String, ancestors: Vector[String],
      firstName: Option[Personal[String]], lastName: Option[Personal[String]], actor: Actor, at: Instant)
  case Invited(actor: Actor, at: Instant)
  case Accepted(at: Instant)
  case ScopeChanged(scopeLevel: String, scopeId: String, ancestors: Vector[String], actor: Actor, at: Instant)
  case EnabledMirrored(enabled: Boolean, at: Instant)
  case Removed(actor: Actor, at: Instant)
  /** A brand's `players` set the realm roles it gives this operator (spec 030 H6; recorded no more, replayed as a giving in force). */
  case BrandRolesSet(brand: String, before: Set[String], after: Set[String], at: Instant)
  /** A brand asked for these roles, by its names without the brand (spec 032 H6); the applier brings the realm to them. */
  case BrandRolesAsked(brand: String, roles: Set[String], version: Long, at: Instant)
  /** The realm holds what `version` of the brand's giving asked. */
  case BrandRolesApplied(brand: String, version: Long, at: Instant)
  /** The realm refused `version` of the brand's giving; it is not tried again. */
  case BrandRolesFailed(brand: String, version: Long, reason: String, at: Instant)
  /** H6 refused a giving before recording it (FR-014: every refusal is an audit record); it changes nothing. */
  case BrandRolesRejected(brand: String, caller: String, roles: Set[String], code: String, at: Instant)

final case class CreateOperator(email: Personal[String], role: String, scopeLevel: String, scopeId: String, ancestors: Vector[String],
    firstName: Option[Personal[String]], lastName: Option[Personal[String]], actor: Actor, at: Instant)
final case class OperatorChange(actor: Actor, at: Instant)
final case class ChangeScope(scopeLevel: String, scopeId: String, ancestors: Vector[String], actor: Actor, at: Instant)
final case class MirrorEnabled(enabled: Boolean, at: Instant)
final case class AskBrandRoles(brand: String, roles: Set[String], at: Instant)
final case class BrandRolesDone(brand: String, version: Long, at: Instant)
final case class BrandRolesRefused(brand: String, version: Long, reason: String, at: Instant)
final case class RejectBrandRoles(brand: String, caller: String, roles: Set[String], code: String, at: Instant)

object OperatorCodecs:
  given Serializer[OperatorState]  = MerchantCodecs.serializer[OperatorState]("operator-state")
  given Serializer[OperatorEvent]  = MerchantCodecs.serializer[OperatorEvent]("operator-event")
  given Serializer[CreateOperator] = MerchantCodecs.serializer[CreateOperator]("create-operator")
  given Serializer[OperatorChange] = MerchantCodecs.serializer[OperatorChange]("operator-change")
  given Serializer[ChangeScope]    = MerchantCodecs.serializer[ChangeScope]("change-scope")
  given Serializer[MirrorEnabled]  = MerchantCodecs.serializer[MirrorEnabled]("mirror-enabled")
  given Serializer[AskBrandRoles]  = MerchantCodecs.serializer[AskBrandRoles]("ask-brand-roles")
  given Serializer[BrandRolesDone] = MerchantCodecs.serializer[BrandRolesDone]("brand-roles-done")
  given Serializer[BrandRolesRefused] = MerchantCodecs.serializer[BrandRolesRefused]("brand-roles-refused")
  given Serializer[Giving]         = MerchantCodecs.serializer[Giving]("giving")
  given Serializer[RejectBrandRoles] = MerchantCodecs.serializer[RejectBrandRoles]("reject-brand-roles")
  given Serializer[Instant]        = MerchantCodecs.serializer[Instant]("instant")

import OperatorCodecs.given

final class Operator(context: EventSourcedEntityContext) extends EventSourcedEntity[OperatorState, OperatorEvent]:
  def emptyState: OperatorState = OperatorState(context.entityId)

  def applyEvent(event: OperatorEvent): OperatorState = event match
    case OperatorEvent.Created(email, role, level, id, ancestors, first, last, actor, at) =>
      OperatorState(context.entityId, Some(email), role, level, id, ancestors, first, last, pending = true, enabled = true, removed = false,
        Some(at), actor.id)
    case OperatorEvent.Invited(_, _)                       => currentState
    case OperatorEvent.Accepted(_)                         => currentState.copy(pending = false)
    case OperatorEvent.ScopeChanged(level, id, anc, _, _)  => currentState.copy(scopeLevel = level, scopeId = id, ancestors = anc)
    case OperatorEvent.EnabledMirrored(enabled, _)         => currentState.copy(enabled = enabled)
    case OperatorEvent.Removed(_, _)                       => currentState.copy(removed = true)
    case OperatorEvent.BrandRolesSet(brand, _, after, at)  =>
      // 030's record, replayed: the roles it set were in force, a giving of version 0
      currentState.copy(
        brandRoles = if after.isEmpty then currentState.brandRoles - brand else currentState.brandRoles.updated(brand, after),
        givings = currentState.givings.updated(brand, Giving(after - s"brand_$brand", 0, GivingState.InForce, at)))
    case OperatorEvent.BrandRolesAsked(brand, roles, version, at) =>
      currentState.copy(givings = currentState.givings.updated(brand, Giving(roles, version, GivingState.Pending, at)))
    case OperatorEvent.BrandRolesApplied(brand, version, at) =>
      current(brand, version).fold(currentState)(g =>
        currentState.copy(givings = currentState.givings.updated(brand, g.copy(state = GivingState.InForce, at = at)),
          brandRoles = currentState.brandRoles.updated(brand, g.roles.map(io.eitheror.casino.contract.access.BrandRoles.nameIn(brand, _)) + s"brand_$brand")))
    case OperatorEvent.BrandRolesFailed(brand, version, reason, at) =>
      current(brand, version).fold(currentState)(g =>
        currentState.copy(givings = currentState.givings.updated(brand, g.copy(state = GivingState.Failed(reason), at = at))))
    case OperatorEvent.BrandRolesRejected(_, _, _, _, _) => currentState

  /** The brand's giving when `version` is still its latest; a stale outcome changes nothing. */
  private def current(brand: String, version: Long): Option[Giving] = currentState.givings.get(brand).filter(_.version == version)

  private def live[R](f: OperatorState => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState) else effects.error("OPERATOR_NOT_FOUND", ErrorCode.NotFound)

  def create(cmd: CreateOperator): Effect[OperatorState] =
    if currentState.createdAt.isDefined then effects.reply(currentState)
    else effects.persist(OperatorEvent.Created(cmd.email, cmd.role, cmd.scopeLevel, cmd.scopeId, cmd.ancestors, cmd.firstName, cmd.lastName,
      cmd.actor, cmd.at), OperatorEvent.Invited(cmd.actor, cmd.at)).thenReplyState

  def invited(cmd: OperatorChange): Effect[OperatorState] = live(_ => effects.persist(OperatorEvent.Invited(cmd.actor, cmd.at)).thenReplyState)

  def accept(at: Instant): Effect[OperatorState] =
    live(s => if !s.pending then effects.reply(s) else effects.persist(OperatorEvent.Accepted(at)).thenReplyState)

  def changeScope(cmd: ChangeScope): Effect[OperatorState] =
    live(_ => effects.persist(OperatorEvent.ScopeChanged(cmd.scopeLevel, cmd.scopeId, cmd.ancestors, cmd.actor, cmd.at)).thenReplyState)

  /** The realm's enabled flag, mirrored: the same value again records nothing. */
  def mirrorEnabled(cmd: MirrorEnabled): Effect[OperatorState] =
    live(s => if s.enabled == cmd.enabled then effects.reply(s) else effects.persist(OperatorEvent.EnabledMirrored(cmd.enabled, cmd.at)).thenReplyState)

  def remove(cmd: OperatorChange): Effect[OperatorState] = live(_ => effects.persist(OperatorEvent.Removed(cmd.actor, cmd.at)).thenReplyState)

  /**
   * Records what `brand` gives this operator (spec 032 H6), as the brand's next giving; the applier puts
   * it in force. Every giving is recorded, the same roles again too, so a role the realm holds for the
   * brand that no giving asked for is taken away. It is not refused on an operator this service did not
   * create: a brand's operators may have been made in the realm by other means.
   */
  def askBrandRoles(cmd: AskBrandRoles): Effect[Giving] =
    val version = currentState.givings.get(cmd.brand).fold(1L)(_.version + 1)
    effects.persist(OperatorEvent.BrandRolesAsked(cmd.brand, cmd.roles, version, cmd.at))
      .thenReply(_.givings(cmd.brand))

  /** The realm holds `version` of the brand's giving; a later giving, or a second report, records nothing. */
  def brandRolesApplied(cmd: BrandRolesDone): Effect[OperatorState] =
    if current(cmd.brand, cmd.version).exists(_.state == GivingState.Pending) then
      effects.persist(OperatorEvent.BrandRolesApplied(cmd.brand, cmd.version, cmd.at)).thenReplyState
    else effects.reply(currentState)

  /** The realm refused `version` of the brand's giving; a later giving, or a second report, records nothing. */
  def brandRolesFailed(cmd: BrandRolesRefused): Effect[OperatorState] =
    if current(cmd.brand, cmd.version).exists(_.state == GivingState.Pending) then
      effects.persist(OperatorEvent.BrandRolesFailed(cmd.brand, cmd.version, cmd.reason, cmd.at)).thenReplyState
    else effects.reply(currentState)

  /** A giving H6 refused, recorded for the audit; nothing else changes. */
  def rejectBrandRoles(cmd: RejectBrandRoles): Effect[OperatorState] =
    effects.persist(OperatorEvent.BrandRolesRejected(cmd.brand, cmd.caller, cmd.roles, cmd.code, cmd.at)).thenReplyState

  def get: ReadOnlyEffect[OperatorState] = effects.reply(currentState)

object Operator extends EventSourcedEntity.Companion[Operator, OperatorState, OperatorEvent](
      componentId = ComponentId("operator"),
      stateSerializer = OperatorCodecs.given_Serializer_OperatorState,
      eventSerializer = OperatorCodecs.given_Serializer_OperatorEvent
    ):
  def create(context: EventSourcedEntityContext) = new Operator(context)
  val create        = command("create")(_.create)
  val invited       = command("invited")(_.invited)
  val accept        = command("accept")(_.accept)
  val changeScope   = command("change-scope")(_.changeScope)
  val mirrorEnabled = command("mirror-enabled")(_.mirrorEnabled)
  val remove        = command("remove")(_.remove)
  val askBrandRoles = command("ask-brand-roles")(_.askBrandRoles)
  val brandRolesApplied = command("brand-roles-applied")(_.brandRolesApplied)
  val brandRolesFailed  = command("brand-roles-failed")(_.brandRolesFailed)
  val rejectBrandRoles  = command("reject-brand-roles")(_.rejectBrandRoles)
  val get           = query("get")(_.get)

/**
 * An invitation's token (FR-027; the original's `OperatorInvitationTokenStore`): key value under the
 * SHA-256 of the token, so the token itself is kept nowhere; valid for 72 hours and used once.
 */
final case class InvitationState(digest: String, operatorId: String = "", email: Option[Personal[String]] = None,
    expiresAt: Option[Instant] = None, used: Boolean = false)

final case class IssueInvitation(operatorId: String, email: Personal[String], at: Instant, validity: FiniteDuration = InvitationToken.Validity)

object InvitationCodecs:
  given Serializer[InvitationState] = MerchantCodecs.serializer[InvitationState]("invitation-state")
  given Serializer[IssueInvitation] = MerchantCodecs.serializer[IssueInvitation]("issue-invitation")

import InvitationCodecs.given
import OperatorCodecs.given_Serializer_Instant

final class InvitationToken(context: KeyValueEntityContext) extends KeyValueEntity[InvitationState]:
  def emptyState: InvitationState = InvitationState(context.entityId)

  def issue(cmd: IssueInvitation): Effect[InvitationState] =
    effects.updateState(InvitationState(context.entityId, cmd.operatorId, Some(cmd.email), Some(cmd.at.plusMillis(cmd.validity.toMillis))))
      .expireAfter(cmd.validity).thenReplyState

  /** The operator it was issued for, once: a used, expired or unknown token answers none. */
  def consume(at: Instant): Effect[Option[String]] =
    val s = currentState
    if s.operatorId.isEmpty || s.used || s.expiresAt.forall(!at.isBefore(_)) then effects.reply(None)
    else effects.updateState(s.copy(used = true)).thenReply(_ => Some(s.operatorId))

  def get: ReadOnlyEffect[InvitationState] = effects.reply(currentState)

object InvitationToken extends KeyValueEntity.Companion[InvitationToken, InvitationState](
      componentId = ComponentId("invitation-token"),
      stateSerializer = InvitationCodecs.given_Serializer_InvitationState
    ):
  val Validity: FiniteDuration = 72.hours
  def create(context: KeyValueEntityContext) = new InvitationToken(context)
  val issue   = command("issue")(_.issue)
  val consume = command("consume")(_.consume)
  val get     = query("get")(_.get)
