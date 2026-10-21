package io.eitheror.casino.merchant.terminals

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant

/**
 * A terminal (FR-007; the original's `GatewayIntegration`): a way deposits or withdrawals go through a
 * gateway's scheme, with its properties. Secret properties are in the store under `terminal/<id>`;
 * the entity records which keys are set, never their values. Created `INACTIVE`; taking deposits and
 * not withdrawals unless the request said otherwise (ADR 0038). Deleted is final: a deleted terminal
 * cannot be created again under its id (`TerminalDeleteRoutesSpec`).
 */
final case class TerminalState(
    id: String,
    subBrandId: String = "",
    gateway: String = "",
    scheme: String = "",
    name: String = "",
    regions: Vector[String] = Vector.empty,
    properties: Map[String, String] = Map.empty,
    secretKeys: Vector[String] = Vector.empty,
    status: String = "",
    supportsDeposit: Boolean = true,
    supportsWithdrawal: Boolean = false,
    requiresPayoutDestination: Boolean = false,
    createdAt: Option[Instant] = None,
    deleted: Boolean = false
):
  def exists: Boolean = createdAt.isDefined && !deleted
  def active: Boolean = exists && status == TerminalState.Active

  /** Every property key that is set, secret or not, as a listing shows them. */
  def propertyKeys: Vector[String] = (properties.collect { case (k, v) if v.trim.nonEmpty => k }.toVector ++ secretKeys).distinct.sorted

object TerminalState:
  val Active   = "ACTIVE"
  val Inactive = "INACTIVE"

final case class Capability(supportsDeposit: Boolean, supportsWithdrawal: Boolean, requiresPayoutDestination: Boolean)

enum TerminalEvent:
  case Created(subBrandId: String, gateway: String, scheme: String, name: String, regions: Vector[String], properties: Map[String, String],
      secretKeys: Vector[String], capability: Capability, actor: Actor, at: Instant)
  case Activated(actor: Actor, at: Instant)
  case Disabled(actor: Actor, at: Instant)
  /** The properties now set; the secret ones by name only (SC-007). */
  case PropertiesChanged(properties: Map[String, String], secretKeys: Vector[String], actor: Actor, at: Instant)
  case CapabilityChanged(capability: Capability, actor: Actor, at: Instant)
  case Deleted(actor: Actor, at: Instant)

final case class CreateTerminal(subBrandId: String, gateway: String, scheme: String, name: String, regions: Vector[String],
    properties: Map[String, String], secretKeys: Vector[String], capability: Capability, actor: Actor, at: Instant)
final case class TerminalChange(actor: Actor, at: Instant)
final case class SetProperties(properties: Map[String, String], secretKeys: Vector[String], actor: Actor, at: Instant)
final case class SetCapability(capability: Capability, actor: Actor, at: Instant)

object TerminalCodecs:
  given Serializer[TerminalState]  = MerchantCodecs.serializer[TerminalState]("terminal-state")
  given Serializer[TerminalEvent]  = MerchantCodecs.serializer[TerminalEvent]("terminal-event")
  given Serializer[CreateTerminal] = MerchantCodecs.serializer[CreateTerminal]("create-terminal")
  given Serializer[TerminalChange] = MerchantCodecs.serializer[TerminalChange]("terminal-change")
  given Serializer[SetProperties]  = MerchantCodecs.serializer[SetProperties]("set-properties")
  given Serializer[SetCapability]  = MerchantCodecs.serializer[SetCapability]("set-capability")

import TerminalCodecs.given

final class Terminal(context: EventSourcedEntityContext) extends EventSourcedEntity[TerminalState, TerminalEvent]:

  def emptyState: TerminalState = TerminalState(context.entityId)

  def applyEvent(event: TerminalEvent): TerminalState = Terminal.fold(currentState, event)

  private def live[R](f: TerminalState => Effect[R]): Effect[R] =
    if currentState.exists then f(currentState) else effects.error("terminal not found", ErrorCode.NotFound)

  def create(cmd: CreateTerminal): Effect[TerminalState] =
    if currentState.deleted then effects.error("a deleted terminal cannot be created again", ErrorCode.Conflict)
    else if currentState.exists then effects.reply(currentState)
    else if cmd.name.trim.isEmpty || cmd.gateway.trim.isEmpty || cmd.scheme.trim.isEmpty then
      effects.error("a terminal needs a gateway, a scheme and a name", ErrorCode.BadRequest)
    else
      effects.persist(TerminalEvent.Created(cmd.subBrandId, cmd.gateway.toUpperCase, cmd.scheme, cmd.name, cmd.regions, cmd.properties,
        cmd.secretKeys.sorted, cmd.capability, cmd.actor, cmd.at)).thenReplyState

  def activate(cmd: TerminalChange): Effect[TerminalState] =
    live(s => if s.active then effects.reply(s) else effects.persist(TerminalEvent.Activated(cmd.actor, cmd.at)).thenReplyState)

  def disable(cmd: TerminalChange): Effect[TerminalState] =
    live(s => if !s.active then effects.reply(s) else effects.persist(TerminalEvent.Disabled(cmd.actor, cmd.at)).thenReplyState)

  def setProperties(cmd: SetProperties): Effect[TerminalState] =
    live(_ => effects.persist(TerminalEvent.PropertiesChanged(cmd.properties, cmd.secretKeys.sorted, cmd.actor, cmd.at)).thenReplyState)

  def setCapability(cmd: SetCapability): Effect[TerminalState] =
    live(_ => effects.persist(TerminalEvent.CapabilityChanged(cmd.capability, cmd.actor, cmd.at)).thenReplyState)

  def delete(cmd: TerminalChange): Effect[TerminalState] =
    live(_ => effects.persist(TerminalEvent.Deleted(cmd.actor, cmd.at)).thenReplyState)

  def get: ReadOnlyEffect[TerminalState] = effects.reply(currentState)

object Terminal extends EventSourcedEntity.Companion[Terminal, TerminalState, TerminalEvent](
      componentId = ComponentId("terminal"),
      stateSerializer = TerminalCodecs.given_Serializer_TerminalState,
      eventSerializer = TerminalCodecs.given_Serializer_TerminalEvent
    ):
  def create(context: EventSourcedEntityContext) = new Terminal(context)

  val create        = command("create")(_.create)
  val activate      = command("activate")(_.activate)
  val disable       = command("disable")(_.disable)
  val setProperties = command("set-properties")(_.setProperties)
  val setCapability = command("set-capability")(_.setCapability)
  val delete        = command("delete")(_.delete)
  val get           = query("get")(_.get)

  def fold(s: TerminalState, event: TerminalEvent): TerminalState = event match
    case TerminalEvent.Created(sb, gw, scheme, name, regions, props, secrets, cap, _, at) =>
      s.copy(subBrandId = sb, gateway = gw, scheme = scheme, name = name, regions = regions, properties = props, secretKeys = secrets,
        status = TerminalState.Inactive, supportsDeposit = cap.supportsDeposit, supportsWithdrawal = cap.supportsWithdrawal,
        requiresPayoutDestination = cap.requiresPayoutDestination, createdAt = Some(at))
    case TerminalEvent.Activated(_, _)                     => s.copy(status = TerminalState.Active)
    case TerminalEvent.Disabled(_, _)                      => s.copy(status = TerminalState.Inactive)
    case TerminalEvent.PropertiesChanged(props, secrets, _, _) => s.copy(properties = props, secretKeys = secrets)
    case TerminalEvent.CapabilityChanged(cap, _, _) =>
      s.copy(supportsDeposit = cap.supportsDeposit, supportsWithdrawal = cap.supportsWithdrawal, requiresPayoutDestination = cap.requiresPayoutDestination)
    case TerminalEvent.Deleted(_, _) => s.copy(deleted = true, status = TerminalState.Inactive)
