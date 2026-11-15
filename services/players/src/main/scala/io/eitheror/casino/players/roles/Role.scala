package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.access.OperatorPermissions
import io.eitheror.casino.players.PlayersCodecs

import java.time.Instant

/**
 * One of the brand's operator roles (spec 030 data-model.md *Role*, amendment P12): a bag of permissions
 * the brand's administrators change while the brand runs. Its id is the role's name. A default role
 * (the role matrix's) is reset to its default bag and never deleted; a role the brand made is deleted,
 * and its name may be used again. Whether a change would leave no role able to change roles is decided
 * by the caller against every role ([[RoleRules]]), since it is not one role's to know.
 */
final case class RoleState(
    name: String = "",
    description: String = "",
    permissions: Set[String] = Set.empty,
    default: Boolean = false,
    deleted: Boolean = false
):
  def exists: Boolean = name.nonEmpty && !deleted

sealed trait RoleEvent
object RoleEvent:
  final case class Defined(name: String, description: String, permissions: Set[String], default: Boolean, by: String, at: Instant) extends RoleEvent
  final case class PermissionsChanged(description: String, before: Set[String], after: Set[String], by: String, at: Instant) extends RoleEvent
  final case class Reset(before: Set[String], after: Set[String], by: String, at: Instant) extends RoleEvent
  final case class Deleted(before: Set[String], by: String, at: Instant) extends RoleEvent

/** The role made or changed to carry `permissions`, with `description`; `by` is the operator, `seed` the service. */
final case class SetRole(name: String, description: String, permissions: Set[String], by: String, at: Instant)
final case class RoleAct(by: String, at: Instant)

/** Whether the call changed the role, a refusal code, and the role as it then stands. */
final case class RoleAnswer(changed: Boolean, state: RoleState, refusal: Option[String] = None)

final class Role(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[RoleState, RoleEvent]:
  import RoleEvent.*

  def emptyState: RoleState = RoleState()

  def applyEvent(event: RoleEvent): RoleState = event match
    case e: Defined            => RoleState(e.name, e.description, e.permissions, e.default)
    case e: PermissionsChanged => currentState.copy(description = e.description, permissions = e.after)
    case e: Reset              => currentState.copy(permissions = e.after)
    case _: Deleted            => currentState.copy(deleted = true, permissions = Set.empty)

  private def answer(refusal: Option[String] = None) = effects.reply(RoleAnswer(false, currentState, refusal))

  /**
   * The default role seeded with its default bag, once: a role that already exists, or a default role
   * an earlier start seeded, is left as it stands.
   */
  def seed(c: SetRole): Effect[RoleAnswer] =
    if currentState.name.nonEmpty then answer()
    else effects.persist(Defined(c.name, c.description, c.permissions, default = true, c.by, c.at)).thenReply(RoleAnswer(true, _))

  /** Makes the role, or changes what it carries and says it is for. The permissions are checked by the caller. */
  def set(c: SetRole): Effect[RoleAnswer] =
    if !currentState.exists then
      val default = OperatorPermissions.DefaultRoles.contains(c.name)
      effects.persist(Defined(c.name, c.description, c.permissions, default, c.by, c.at)).thenReply(RoleAnswer(true, _))
    else if currentState.permissions == c.permissions && currentState.description == c.description then answer()
    else effects.persist(PermissionsChanged(c.description, currentState.permissions, c.permissions, c.by, c.at)).thenReply(RoleAnswer(true, _))

  /** A default role back to the role matrix's bag. */
  def reset(c: RoleAct): Effect[RoleAnswer] =
    if !currentState.exists then answer(Some(RoleRules.NoSuchRole))
    else
      OperatorPermissions.DefaultRoles.get(currentState.name) match
        case None                                        => answer(Some(RoleRules.NotDefault))
        case Some(bag) if bag == currentState.permissions => answer()
        case Some(bag) => effects.persist(Reset(currentState.permissions, bag, c.by, c.at)).thenReply(RoleAnswer(true, _))

  /** A role the brand made, deleted; a default role is refused. */
  def delete(c: RoleAct): Effect[RoleAnswer] =
    if !currentState.exists then answer(Some(RoleRules.NoSuchRole))
    else if currentState.default then answer(Some(RoleRules.DefaultRole))
    else effects.persist(Deleted(currentState.permissions, c.by, c.at)).thenReply(RoleAnswer(true, _))

  def get: ReadOnlyEffect[RoleState] = effects.reply(currentState)

object Role
    extends EventSourcedEntity.Companion[Role, RoleState, RoleEvent](
      componentId = ComponentId("role"),
      stateSerializer = PlayersCodecs.serializer[RoleState]("role-state"),
      eventSerializer = PlayersCodecs.serializer[RoleEvent]("role-event")
    ):

  given Serializer[RoleState]  = PlayersCodecs.serializer[RoleState]("role-reply")
  given Serializer[SetRole]    = PlayersCodecs.serializer[SetRole]("set-role")
  given Serializer[RoleAct]    = PlayersCodecs.serializer[RoleAct]("role-act")
  given Serializer[RoleAnswer] = PlayersCodecs.serializer[RoleAnswer]("role-answer")

  def create(context: EventSourcedEntityContext) = new Role(context)

  def id(name: String): EntityId = EntityId(name)

  val seed   = command("seed")(_.seed)
  val set    = command("set")(_.set)
  val reset  = command("reset")(_.reset)
  val delete = command("delete")(_.delete)
  val get    = query("get")(_.get)
