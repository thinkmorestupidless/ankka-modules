package io.eitheror.casino.merchant.hierarchy

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

import java.time.Instant

/** The statuses of a holding, a company and a sub-brand (FR-001), as the original spells them. */
object OrgStatus:
  val Active    = "Active"
  val Suspended = "Suspended"
  val Archived  = "Archived"
  val all       = Set(Active, Suspended, Archived)

/** Who made a change: an operator's subject, a service, or this service's own seed (FR-034). */
final case class Actor(kind: String, id: String)

object Actor:
  def operator(subject: String): Actor = Actor("operator", subject)
  def service(name: String): Actor     = Actor("service", name)
  val seed: Actor                      = Actor("system", "seed")
  val workflow: Actor                  = Actor("system", "deposit-workflow")

/**
 * A holding or a company (FR-001): a name, a status, and for a company its holding. A parent lists no
 * children; the views do. Created once: the same create again records nothing, so a seed may run any
 * number of times (FR-003); a different create under the same id is a conflict.
 */
final case class OrgState(id: String, name: String = "", status: String = "", parentId: Option[String] = None,
    createdAt: Option[Instant] = None):
  def exists: Boolean = createdAt.isDefined

enum OrgEvent:
  case Created(name: String, parentId: Option[String], actor: Actor, at: Instant)
  case StatusChanged(status: String, actor: Actor, at: Instant)

final case class CreateOrg(name: String, parentId: Option[String], actor: Actor, at: Instant)
final case class ChangeOrgStatus(status: String, actor: Actor, at: Instant)

object OrgCodecs:
  given Serializer[OrgState]        = MerchantCodecs.serializer[OrgState]("org-state")
  given Serializer[OrgEvent]        = MerchantCodecs.serializer[OrgEvent]("org-event")
  given Serializer[CreateOrg]       = MerchantCodecs.serializer[CreateOrg]("create-org")
  given Serializer[ChangeOrgStatus] = MerchantCodecs.serializer[ChangeOrgStatus]("change-org-status")

import OrgCodecs.given

final class OrgEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[OrgState, OrgEvent]:

  def emptyState: OrgState = OrgState(context.entityId)

  def applyEvent(event: OrgEvent): OrgState = event match
    case OrgEvent.Created(name, parent, _, at) => currentState.copy(name = name, status = OrgStatus.Active, parentId = parent, createdAt = Some(at))
    case OrgEvent.StatusChanged(status, _, _)  => currentState.copy(status = status)

  def create(cmd: CreateOrg): Effect[OrgState] =
    val s = currentState
    if !s.exists then
      if cmd.name.trim.isEmpty then effects.error("a name is required", ErrorCode.BadRequest)
      else effects.persist(OrgEvent.Created(cmd.name.trim, cmd.parentId, cmd.actor, cmd.at)).thenReplyState
    else if s.name == cmd.name.trim && s.parentId == cmd.parentId then effects.reply(s)
    else effects.error(s"${context.entityId} exists with other content", ErrorCode.Conflict)

  def changeStatus(cmd: ChangeOrgStatus): Effect[OrgState] =
    if !currentState.exists then effects.error("not found", ErrorCode.NotFound)
    else if !OrgStatus.all(cmd.status) then effects.error(s"'${cmd.status}' is not a status", ErrorCode.BadRequest)
    else if currentState.status == cmd.status then effects.reply(currentState)
    else effects.persist(OrgEvent.StatusChanged(cmd.status, cmd.actor, cmd.at)).thenReplyState

  def get: ReadOnlyEffect[OrgState] = effects.reply(currentState)

/** A holding (FR-001). */
object Holding extends EventSourcedEntity.Companion[OrgEntity, OrgState, OrgEvent](
      componentId = ComponentId("holding"),
      stateSerializer = OrgCodecs.given_Serializer_OrgState,
      eventSerializer = OrgCodecs.given_Serializer_OrgEvent
    ):
  def create(context: EventSourcedEntityContext) = new OrgEntity(context)
  val create       = command("create")(_.create)
  val changeStatus = command("change-status")(_.changeStatus)
  val get          = query("get")(_.get)

/** A company, under a holding (FR-001). */
object Company extends EventSourcedEntity.Companion[OrgEntity, OrgState, OrgEvent](
      componentId = ComponentId("company"),
      stateSerializer = OrgCodecs.given_Serializer_OrgState,
      eventSerializer = OrgCodecs.given_Serializer_OrgEvent
    ):
  def create(context: EventSourcedEntityContext) = new OrgEntity(context)
  val create       = command("create")(_.create)
  val changeStatus = command("change-status")(_.changeStatus)
  val get          = query("get")(_.get)
