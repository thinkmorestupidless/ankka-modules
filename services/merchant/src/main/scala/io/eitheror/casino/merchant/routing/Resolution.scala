package io.eitheror.casino.merchant.routing

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.merchant.hierarchy.{Company, Holding, SubBrand}

/** Where a session's tree came from (FR-011): the level, the entity and its name. */
final case class ResolvedFrom(level: Level, entityId: String, name: String)

/** The tree that governs a sub-brand, and where it came from. */
final case class Resolved(tree: Tree, from: ResolvedFrom)

/**
 * The tree a sub-brand's sessions are routed by (FR-011): its own live tree, else its company's, else
 * its holding's (the original's `resolveRoutingTree`). None when nothing is live at any level.
 */
object Resolution:
  def resolve(components: ComponentClient, subBrandId: String): Option[Resolved] =
    val sb = components.forEventSourcedEntity(EntityId(subBrandId)).call(SubBrand.get).invoke()
    if !sb.exists then None
    else
      def live(level: Level, id: String) =
        if id.isEmpty then None else components.forEventSourcedEntity(RoutingTree.id(level, id)).call(RoutingTree.get).invoke().liveTree
      live(Level.SubBrand, sb.id).map(t => Resolved(t, ResolvedFrom(Level.SubBrand, sb.id, sb.name)))
        .orElse(live(Level.Company, sb.companyId).map { t =>
          Resolved(t, ResolvedFrom(Level.Company, sb.companyId, components.forEventSourcedEntity(EntityId(sb.companyId)).call(Company.get).invoke().name))
        })
        .orElse(live(Level.Holding, sb.holdingId).map { t =>
          Resolved(t, ResolvedFrom(Level.Holding, sb.holdingId, components.forEventSourcedEntity(EntityId(sb.holdingId)).call(Holding.get).invoke().name))
        })
