package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/**
 * Which of the brand's roles carry `roles.change` (FR-008d), kept by one entity so that the rule "the last
 * role that may change roles cannot lose it" has one writer. Every change that takes `roles.change` from a
 * role (a change, a reset, a delete) is claimed here first, and refused here when no role would carry it;
 * a change that gives it is recorded here after the role holds it. Either way a failure part-way leaves
 * this knowing fewer holders than there are, which can only refuse more, never lock the brand out.
 *
 * One race is accepted, and is the one way this can know a holder too many: two changes to the *same* role
 * in opposite directions at once, where the one giving `roles.change` writes, the one taking it away claims
 * and writes, and only then the first records its giving. The role then lacks it while this counts it, so a
 * later change could take it from the last real holder. It needs two operators editing one role within the
 * same moment; closing it means the giving's claim carries the role's version (spec 032).
 *
 * `holders` is `None` until first asked; the first claim brings what the roles held then (`known`, read
 * from the default roles' entities and the brand's roles view).
 */
final case class ChangersState(holders: Option[Set[String]] = None)

/** `role` takes (`carries`) or loses `roles.change`; `known`, the holders as the caller read them, if this knows none yet. */
final case class ClaimChange(role: String, carries: Boolean, known: Set[String])

/** Whether the claim was refused, and the holders as they then stand. */
final case class ChangersAnswer(refused: Boolean, holders: Set[String])

final class RoleChangers(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ChangersState]:
  def emptyState: ChangersState = ChangersState()

  def claim(c: ClaimChange): Effect[ChangersAnswer] =
    val before = currentState.holders.getOrElse(c.known)
    val after  = if c.carries then before + c.role else before - c.role
    if before.nonEmpty && after.isEmpty then effects.reply(ChangersAnswer(refused = true, before))
    else if currentState.holders.contains(after) then effects.reply(ChangersAnswer(refused = false, after))
    else effects.updateState(ChangersState(Some(after))).thenReply(_ => ChangersAnswer(refused = false, after))

  def get: ReadOnlyEffect[ChangersState] = effects.reply(currentState)

object RoleChangers
    extends KeyValueEntity.Companion[RoleChangers, ChangersState](
      componentId = ComponentId("role-changers"),
      stateSerializer = PlayersCodecs.serializer[ChangersState]("role-changers-state")
    ):
  given Serializer[ClaimChange]    = PlayersCodecs.serializer[ClaimChange]("claim-change")
  given Serializer[ChangersAnswer] = PlayersCodecs.serializer[ChangersAnswer]("changers-answer")
  given Serializer[ChangersState]  = PlayersCodecs.serializer[ChangersState]("role-changers")

  def create(context: KeyValueEntityContext) = new RoleChangers(context)

  /** One per brand: a brand is a project, and a project runs one `players`. */
  val id: EntityId = EntityId("brand")

  val claim = command("claim")(_.claim)
  val get   = query("get")(_.get)
