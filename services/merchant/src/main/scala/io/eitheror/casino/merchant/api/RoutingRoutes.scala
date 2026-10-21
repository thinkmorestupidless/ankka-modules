package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.operators.Target
import io.eitheror.casino.merchant.routing.*

/**
 * The routing rules (FR-010, FR-011; the original's `RoutingTreeRoutes`): a tree per holding, company
 * or sub-brand, named by `level` and `entityId`, each scope-checked against the level's entity.
 */
final class RoutingRoutes(ctx: Ctx):

  private def target(level: Level, id: String): Option[Target] = level match
    case Level.Holding  => Option.when(ctx.holding(id).exists)(Target(Some(id)))
    case Level.Company  => Some(ctx.company(id)).filter(_.exists).map(c => Target(c.parentId, Some(id)))
    case Level.SubBrand => Some(ctx.subBrand(id)).filter(_.exists).map(ctx.subBrandTarget)

  private def at(op: Operator, level: Option[String], entityId: Option[String])(f: (Level, String) => Respond[Bytes]): Respond[Bytes] =
    guarded {
      (level.flatMap(Level.from), entityId.filter(_.nonEmpty)) match
        case (None, _) => refuse(400, "INVALID_LEVEL")
        case (_, None) => refuse(400, "INVALID_REQUEST", "entityId is required")
        case (Some(l), Some(id)) =>
          target(l, id) match
            case None                       => refuse(404, s"${l.api.toUpperCase}_NOT_FOUND")
            case Some(t) if !op.permits(t)  => scopeViolation
            case Some(_)                    => f(l, id)
    }

  private def tree(l: Level, id: String) = ctx.components.forEventSourcedEntity(RoutingTree.id(l, id))

  /** Draft, live, history; at the sub-brand level with nothing live of its own, the tree it falls back to and where from. */
  def state(op: Operator, level: Option[String], entityId: Option[String]): Respond[Bytes] =
    at(op, level, entityId) { (l, id) =>
      val s = tree(l, id).call(RoutingTree.get).invoke()
      val o = Json.obj("liveVersion" -> s.liveVersion, "liveDeployedAt" -> s.liveDeployedAt.map(_.toString),
        "history" -> Json.arr(s.history.map(v => Json.obj("version" -> v.version, "deployedAt" -> v.deployedAt.toString))*))
      s.draft.fold(o.putNull("draft"))(d => o.set("draft", Codec.json(d))): Unit
      s.liveTree.foreach(t => o.set("liveTree", Codec.json(t)): Unit)
      if l == Level.SubBrand && s.liveTree.isEmpty then
        Resolution.resolve(ctx.components, id).foreach { r =>
          o.set("liveTree", Codec.json(r.tree))
          o.set("resolvedFrom", Json.obj("level" -> r.from.level.api, "entityId" -> r.from.entityId, "name" -> r.from.name)): Unit
        }
      ok(o)
    }

  def saveDraft(op: Operator, text: String): Respond[Bytes] =
    body(text).fold(identity, b =>
      at(op, b.get("level").pipe(Option(_)).map(_.asText), Option(b.get("entityId")).map(_.asText)) { (l, id) =>
        Codec.tree(Option(b.get("tree"))) match
          case None => refuse(400, "INVALID_REQUEST", "the body carries no tree")
          case Some(t) =>
            tree(l, id).call(RoutingTree.saveDraft).invoke(SaveDraft(t, op.actor, ctx.now())): Unit
            accepted
      })

  def deploy(op: Operator, level: Option[String], entityId: Option[String]): Respond[Bytes] =
    at(op, level, entityId) { (l, id) =>
      val v = tree(l, id).call(RoutingTree.deploy).invoke(TreeChange(op.actor, ctx.now()))
      ok(Json.obj("version" -> v.version, "deployedAt" -> v.deployedAt.toString))
    }

  def rollback(op: Operator, version: String, level: Option[String], entityId: Option[String]): Respond[Bytes] =
    version.toIntOption.fold(refuse(400, "INVALID_REQUEST", "the version is a number"))(v =>
      at(op, level, entityId) { (l, id) =>
        tree(l, id).call(RoutingTree.rollback).invoke(Rollback(v, op.actor, ctx.now())): Unit
        noContent
      })

  def retire(op: Operator, level: Option[String], entityId: Option[String]): Respond[Bytes] =
    at(op, level, entityId) { (l, id) =>
      tree(l, id).call(RoutingTree.retire).invoke(TreeChange(op.actor, ctx.now())): Unit
      noContent
    }

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)
