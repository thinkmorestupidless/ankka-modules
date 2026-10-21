package io.eitheror.casino.merchant.routing

/**
 * A routing tree as the original keeps it (`RoutingTreeState`, `RoutingNode`, `RoutingBranch`,
 * `RoutingConditionSpec`): from a root node, a node either names terminals or branches on conditions
 * to the next node (FR-012). The wire shape is the original's, so a backoffice tree is unchanged.
 */
final case class Condition(conditionType: String, operator: String, value: String)
final case class Branch(conditions: Vector[Condition], nextNodeId: String)
final case class Node(nodeId: String, branches: Option[Vector[Branch]], terminals: Option[Vector[String]])
final case class Tree(rootNodeId: String, nodes: Map[String, Node])

/**
 * What a deposit is routed on (FR-012): its currency, its amount in minor units, the player's country
 * and customer id, and the player's captured deposits at the sub-brand. A `Long` where the original
 * had a 32-bit `amountCents` (research R11).
 */
final case class RoutingContext(currency: String, amount: Long, country: String, customerId: String, depositCount: Long)

/** The levels a tree is kept at, as the original's API spells them (`RoutingTreeLevel.asApiString`). */
enum Level(val api: String):
  case Holding  extends Level("holding")
  case Company  extends Level("company")
  case SubBrand extends Level("subBrand")

object Level:
  def from(s: String): Option[Level] = values.find(_.api == s)

/** What a walk decides (`RoutingTreeEvaluator.EvalResult`). */
enum Outcome:
  case Match(terminals: Vector[String])
  case NoMatch
  case NoTree
