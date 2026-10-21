package io.eitheror.casino.merchant.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{NullNode, ObjectNode}
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.routing.*

import scala.jdk.CollectionConverters.*

/** Routing trees on the wire, in the original's shape (`RoutingTreeState`, `RoutingNode`, `RoutingBranch`). */
object Codec:
  def tree(node: Option[JsonNode]): Option[Tree] =
    node.filter(_.isObject).flatMap { t =>
      val nodes = Option(t.get("nodes")).filter(_.isObject).map(_.properties.asScala.toVector.map { e =>
        val n = e.getValue
        val branches = Option(n.get("branches")).filter(_.isArray).map(_.elements.asScala.toVector.map { b =>
          val cs = Option(b.get("conditions")).filter(_.isArray).map(_.elements.asScala.toVector.map(c =>
            Condition(c.textOr("conditionType", c.textOr("type", "")), c.textOr("operator", ""), c.textOr("value", "")))).getOrElse(Vector.empty)
          Branch(cs, b.textOr("nextNodeId", ""))
        })
        val terminals = Option(n.get("terminals")).filter(_.isArray).map(_.elements.asScala.toVector.map(_.asText))
        e.getKey -> Node(n.textOr("nodeId", e.getKey), branches, terminals)
      }.toMap).getOrElse(Map.empty)
      t.text("rootNodeId").map(root => Tree(root, nodes))
    }

  def json(t: Tree): ObjectNode =
    val nodes = Json.obj()
    t.nodes.toVector.sortBy(_._1).foreach { (id, n) =>
      val o = Json.obj("nodeId" -> n.nodeId)
      n.branches match
        case None => o.set("branches", NullNode.instance): Unit
        case Some(bs) =>
          o.set("branches", Json.arr(bs.map(b => Json.obj("conditions" -> Json.arr(b.conditions.map(c =>
            Json.obj("conditionType" -> c.conditionType, "operator" -> c.operator, "value" -> c.value))*), "nextNodeId" -> b.nextNodeId))*)): Unit
      n.terminals match
        case None     => o.set("terminals", NullNode.instance): Unit
        case Some(ts) => Json.set(o, "terminals", ts)
      nodes.set(id, o): Unit
    }
    Json.obj("rootNodeId" -> t.rootNodeId, "nodes" -> nodes)
