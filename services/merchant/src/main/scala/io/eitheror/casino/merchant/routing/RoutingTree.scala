package io.eitheror.casino.merchant.routing

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant

/** One deployed version of a tree, kept for the history and a rollback (FR-010). */
final case class Version(version: Int, deployedAt: Instant, tree: Tree)

/**
 * The routing tree of one holding, company or sub-brand (FR-010), id `<level>/<entityId>`: a draft,
 * the live version, and every version deployed. Deploying makes the draft the next version and clears
 * it; a rollback, as the original's, copies an earlier version's tree into the draft for the operator
 * to deploy; retiring leaves nothing live, so the level below falls back to the one above
 * (`RoutingTreeRoutesSpec`). A version's number is one above the highest deployed: the original's
 * `liveVersion + 1` numbered a deploy after a retirement 1 again, beside the old 1 (research R6a).
 */
final case class RoutingTreeState(
    id: String,
    draft: Option[Tree] = None,
    liveVersion: Option[Int] = None,
    liveDeployedAt: Option[Instant] = None,
    history: Vector[Version] = Vector.empty
):
  def liveTree: Option[Tree] = liveVersion.flatMap(v => history.find(_.version == v)).map(_.tree)

enum RoutingTreeEvent:
  case DraftSaved(tree: Tree, actor: Actor, at: Instant)
  case Deployed(version: Int, actor: Actor, at: Instant)
  case RolledBack(version: Int, actor: Actor, at: Instant)
  case Retired(actor: Actor, at: Instant)

final case class SaveDraft(tree: Tree, actor: Actor, at: Instant)
final case class TreeChange(actor: Actor, at: Instant)
final case class Rollback(version: Int, actor: Actor, at: Instant)

object RoutingTreeCodecs:
  given Serializer[RoutingTreeState] = MerchantCodecs.serializer[RoutingTreeState]("routing-tree-state")
  given Serializer[RoutingTreeEvent] = MerchantCodecs.serializer[RoutingTreeEvent]("routing-tree-event")
  given Serializer[SaveDraft]        = MerchantCodecs.serializer[SaveDraft]("save-draft")
  given Serializer[TreeChange]       = MerchantCodecs.serializer[TreeChange]("tree-change")
  given Serializer[Rollback]         = MerchantCodecs.serializer[Rollback]("rollback")
  given Serializer[Version]          = MerchantCodecs.serializer[Version]("version")

import RoutingTreeCodecs.given

final class RoutingTree(context: EventSourcedEntityContext) extends EventSourcedEntity[RoutingTreeState, RoutingTreeEvent]:

  def emptyState: RoutingTreeState = RoutingTreeState(context.entityId)

  def applyEvent(event: RoutingTreeEvent): RoutingTreeState = RoutingTree.fold(currentState, event)

  def saveDraft(cmd: SaveDraft): Effect[RoutingTreeState] =
    if cmd.tree.rootNodeId.isBlank then effects.error("a tree names its root node", ErrorCode.BadRequest)
    else effects.persist(RoutingTreeEvent.DraftSaved(cmd.tree, cmd.actor, cmd.at)).thenReplyState

  /** The draft becomes the next version, live; no draft is `NO_DRAFT`. */
  def deploy(cmd: TreeChange): Effect[Version] =
    currentState.draft match
      case None => effects.error("NO_DRAFT", ErrorCode.BadRequest)
      case Some(_) =>
        val version = currentState.history.map(_.version).maxOption.getOrElse(0) + 1
        effects.persist(RoutingTreeEvent.Deployed(version, cmd.actor, cmd.at))
          .thenReply(s => s.history.find(_.version == version).get)

  /** The version's tree becomes the draft; `VERSION_NOT_FOUND` for one never deployed. */
  def rollback(cmd: Rollback): Effect[RoutingTreeState] =
    currentState.history.find(_.version == cmd.version) match
      case None    => effects.error("VERSION_NOT_FOUND", ErrorCode.NotFound)
      case Some(_) => effects.persist(RoutingTreeEvent.RolledBack(cmd.version, cmd.actor, cmd.at)).thenReplyState

  /** Nothing is live afterwards; with nothing live it is a no-op, not an error. */
  def retire(cmd: TreeChange): Effect[RoutingTreeState] =
    if currentState.liveVersion.isEmpty then effects.reply(currentState)
    else effects.persist(RoutingTreeEvent.Retired(cmd.actor, cmd.at)).thenReplyState

  def get: ReadOnlyEffect[RoutingTreeState] = effects.reply(currentState)

object RoutingTree extends EventSourcedEntity.Companion[RoutingTree, RoutingTreeState, RoutingTreeEvent](
      componentId = ComponentId("routing-tree"),
      stateSerializer = RoutingTreeCodecs.given_Serializer_RoutingTreeState,
      eventSerializer = RoutingTreeCodecs.given_Serializer_RoutingTreeEvent
    ):
  def create(context: EventSourcedEntityContext) = new RoutingTree(context)

  def id(level: Level, entityId: String): EntityId = EntityId(s"${level.api}/$entityId")

  val saveDraft = command("save-draft")(_.saveDraft)
  val deploy    = command("deploy")(_.deploy)
  val rollback  = command("rollback")(_.rollback)
  val retire    = command("retire")(_.retire)
  val get       = query("get")(_.get)

  def fold(s: RoutingTreeState, event: RoutingTreeEvent): RoutingTreeState = event match
    case RoutingTreeEvent.DraftSaved(tree, _, _) => s.copy(draft = Some(tree))
    case RoutingTreeEvent.Deployed(version, _, at) =>
      s.copy(draft = None, liveVersion = Some(version), liveDeployedAt = Some(at), history = s.history :+ Version(version, at, s.draft.get))
    case RoutingTreeEvent.RolledBack(version, _, _) => s.copy(draft = s.history.find(_.version == version).map(_.tree))
    case RoutingTreeEvent.Retired(_, _)              => s.copy(liveVersion = None, liveDeployedAt = None)
