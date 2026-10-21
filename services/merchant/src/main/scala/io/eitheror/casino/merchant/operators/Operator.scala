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
    createdBy: String = ""
):
  def exists: Boolean = createdAt.isDefined && !removed

enum OperatorEvent:
  case Created(email: Personal[String], role: String, scopeLevel: String, scopeId: String, ancestors: Vector[String],
      firstName: Option[Personal[String]], lastName: Option[Personal[String]], actor: Actor, at: Instant)
  case Invited(actor: Actor, at: Instant)
  case Accepted(at: Instant)
  case ScopeChanged(scopeLevel: String, scopeId: String, ancestors: Vector[String], actor: Actor, at: Instant)
  case EnabledMirrored(enabled: Boolean, at: Instant)
  case Removed(actor: Actor, at: Instant)

final case class CreateOperator(email: Personal[String], role: String, scopeLevel: String, scopeId: String, ancestors: Vector[String],
    firstName: Option[Personal[String]], lastName: Option[Personal[String]], actor: Actor, at: Instant)
final case class OperatorChange(actor: Actor, at: Instant)
final case class ChangeScope(scopeLevel: String, scopeId: String, ancestors: Vector[String], actor: Actor, at: Instant)
final case class MirrorEnabled(enabled: Boolean, at: Instant)

object OperatorCodecs:
  given Serializer[OperatorState]  = MerchantCodecs.serializer[OperatorState]("operator-state")
  given Serializer[OperatorEvent]  = MerchantCodecs.serializer[OperatorEvent]("operator-event")
  given Serializer[CreateOperator] = MerchantCodecs.serializer[CreateOperator]("create-operator")
  given Serializer[OperatorChange] = MerchantCodecs.serializer[OperatorChange]("operator-change")
  given Serializer[ChangeScope]    = MerchantCodecs.serializer[ChangeScope]("change-scope")
  given Serializer[MirrorEnabled]  = MerchantCodecs.serializer[MirrorEnabled]("mirror-enabled")
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
