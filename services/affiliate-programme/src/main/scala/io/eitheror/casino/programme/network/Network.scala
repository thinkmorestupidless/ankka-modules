package io.eitheror.casino.programme.network

/**
 * The affiliate network's walk (FR-028): ancestors visited each once, a cycle ending the walk, and the
 * check a parent change makes first.
 */
object Network:

  /** The ancestors of `start`, nearest first, each once; the walk ends at the root or where it would revisit. */
  def ancestors(start: String, parentOf: String => Option[String]): Vector[String] =
    @scala.annotation.tailrec
    def walk(at: String, seen: Set[String], acc: Vector[String]): Vector[String] =
      parentOf(at) match
        case Some(p) if !seen(p) => walk(p, seen + p, acc :+ p)
        case _                   => acc
    walk(start, Set(start), Vector.empty)

  /** Whether making `newParent` the parent of `child` would close a loop: `child` itself or one of its descendants. */
  def wouldCycle(child: String, newParent: String, parentOf: String => Option[String]): Boolean =
    newParent == child || ancestors(newParent, parentOf).contains(child)

  /** The descendants of `root` to `maxDepth` levels, breadth first, each once, with their depth. */
  def descendants(root: String, childrenOf: String => Iterable[String], maxDepth: Int): Vector[(String, Int)] =
    @scala.annotation.tailrec
    def level(frontier: Vector[String], depth: Int, seen: Set[String], acc: Vector[(String, Int)]): Vector[(String, Int)] =
      if frontier.isEmpty || depth > maxDepth then acc
      else
        val next = frontier.flatMap(childrenOf).distinct.filterNot(seen)
        level(next, depth + 1, seen ++ next, acc ++ next.map(_ -> depth))
    level(Vector(root), 1, Set(root), Vector.empty)
