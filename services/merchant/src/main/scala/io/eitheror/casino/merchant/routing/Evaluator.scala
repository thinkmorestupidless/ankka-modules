package io.eitheror.casino.merchant.routing

/**
 * The walk of a routing tree (FR-012), ported from the original's `RoutingTreeEvaluator`: from the
 * root, a node with terminals is the match; otherwise the first branch whose conditions all hold (or
 * that has none) leads to the next node; a node with neither, no branch holding, or a node visited
 * twice is no match; a tree with no nodes is no tree.
 *
 * `AMOUNT` and `DEPOSIT_COUNT` compare as whole numbers by `LT LTE GT GTE EQ NEQ`; a value that is not a
 * number reads as zero, as the original's `toIntOption.getOrElse(0)` did. `CURRENCY`, `COUNTRY` and
 * `CUSTOMER_ID` compare by `EQ NEQ IN` ignoring case; `IN` splits its value on commas. The original's
 * `IN` compared case-sensitively; FR-012 says ignoring case, and the spec stands (named in research).
 */
object Evaluator:

  def evaluate(tree: Tree, ctx: RoutingContext): Outcome =
    if tree.nodes.isEmpty then Outcome.NoTree
    else walk(tree.rootNodeId, tree.nodes, ctx, Set.empty).fold(Outcome.NoMatch)(Outcome.Match(_))

  private def walk(nodeId: String, nodes: Map[String, Node], ctx: RoutingContext, visited: Set[String]): Option[Vector[String]] =
    if visited.contains(nodeId) then None
    else
      nodes.get(nodeId).flatMap { node =>
        node.terminals match
          case Some(ts) if ts.nonEmpty => Some(ts)
          case _ =>
            node.branches.getOrElse(Vector.empty)
              .find(b => b.conditions.forall(holds(_, ctx)))
              .flatMap(b => walk(b.nextNodeId, nodes, ctx, visited + nodeId))
      }

  def holds(c: Condition, ctx: RoutingContext): Boolean =
    c.conditionType.toUpperCase match
      case "AMOUNT" | "DEPOSIT_COUNT" =>
        val actual = if c.conditionType.equalsIgnoreCase("AMOUNT") then ctx.amount else ctx.depositCount
        val spec   = c.value.trim.toLongOption.getOrElse(0L)
        c.operator.toUpperCase match
          case "LT"  => actual < spec
          case "LTE" => actual <= spec
          case "GT"  => actual > spec
          case "GTE" => actual >= spec
          case "EQ"  => actual == spec
          case "NEQ" => actual != spec
          case _     => false
      case kind =>
        val field = kind match
          case "CURRENCY"    => Some(ctx.currency)
          case "COUNTRY"     => Some(ctx.country)
          case "CUSTOMER_ID" => Some(ctx.customerId)
          case _             => None
        field.exists { fv =>
          c.operator.toUpperCase match
            case "EQ"  => fv.equalsIgnoreCase(c.value.trim)
            case "NEQ" => !fv.equalsIgnoreCase(c.value.trim)
            case "IN"  => c.value.split(',').map(_.trim).exists(_.equalsIgnoreCase(fv))
            case _     => false
        }
