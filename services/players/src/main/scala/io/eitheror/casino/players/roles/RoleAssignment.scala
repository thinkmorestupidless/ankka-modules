package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

import java.time.Instant

/**
 * The roles this brand gave one operator (spec 030 amendment P13), id the operator's subject at the
 * `eitheror` realm. The realm holds the roles; this keeps who gave which and when, so each giving is an
 * audit record naming both operators, and what the brand gave last is known when merchant is asked again.
 */
final case class AssignmentState(roles: Set[String] = Set.empty, by: Option[String] = None, at: Option[Instant] = None)

sealed trait AssignmentEvent
object AssignmentEvent:
  final case class Given(operator: String, before: Set[String], after: Set[String], by: String, at: Instant) extends AssignmentEvent

final case class Give(operator: String, roles: Set[String], by: String, at: Instant)

final class RoleAssignment(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[AssignmentState, AssignmentEvent]:
  import AssignmentEvent.*

  def emptyState: AssignmentState = AssignmentState()

  def applyEvent(event: AssignmentEvent): AssignmentState = event match
    case e: Given => AssignmentState(e.after, Some(e.by), Some(e.at))

  /** Records what merchant set; a repeat of the same roles records nothing. */
  def give(c: Give): Effect[AssignmentState] =
    if currentState.roles == c.roles && currentState.by.isDefined then effects.reply(currentState)
    else effects.persist(Given(c.operator, currentState.roles, c.roles, c.by, c.at)).thenReply(identity)

  def get: ReadOnlyEffect[AssignmentState] = effects.reply(currentState)

object RoleAssignment
    extends EventSourcedEntity.Companion[RoleAssignment, AssignmentState, AssignmentEvent](
      componentId = ComponentId("role-assignment"),
      stateSerializer = PlayersCodecs.serializer[AssignmentState]("role-assignment-state"),
      eventSerializer = PlayersCodecs.serializer[AssignmentEvent]("role-assignment-event")
    ):

  given Serializer[AssignmentState] = PlayersCodecs.serializer[AssignmentState]("role-assignment-reply")
  given Serializer[Give]            = PlayersCodecs.serializer[Give]("give-roles")

  def create(context: EventSourcedEntityContext) = new RoleAssignment(context)

  def id(operator: String): EntityId = EntityId(operator)

  val give = command("give")(_.give)
  val get  = query("get")(_.get)
