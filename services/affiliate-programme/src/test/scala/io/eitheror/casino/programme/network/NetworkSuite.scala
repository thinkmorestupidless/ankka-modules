package io.eitheror.casino.programme.network

class NetworkSuite extends munit.FunSuite:

  private def parents(pairs: (String, String)*): String => Option[String] = pairs.toMap.get
  private def children(pairs: (String, String)*): String => Vector[String] =
    p => pairs.collect { case (c, `p`) => c }.toVector

  test("the ancestors of a sub, nearest first, to the root") {
    assertEquals(Network.ancestors("c", parents("c" -> "b", "b" -> "a")), Vector("b", "a"))
    assertEquals(Network.ancestors("a", parents("c" -> "b", "b" -> "a")), Vector.empty)
  }

  test("a cycle ends the walk, each ancestor visited once") {
    assertEquals(Network.ancestors("c", parents("c" -> "b", "b" -> "a", "a" -> "c")), Vector("b", "a"))
    assertEquals(Network.ancestors("a", parents("a" -> "a")), Vector.empty)
  }

  test("a parent change that would make a loop is found; one that would not is not") {
    val p = parents("c" -> "b", "b" -> "a")
    assert(Network.wouldCycle("a", "c", p))
    assert(Network.wouldCycle("a", "a", p))
    assert(!Network.wouldCycle("c", "a", p))
    assert(!Network.wouldCycle("x", "c", p))
  }

  test("descendants to a depth, each once") {
    val c = children("b" -> "a", "c" -> "a", "d" -> "b", "e" -> "d", "a" -> "e")
    assertEquals(Network.descendants("a", c, 2), Vector("b" -> 1, "c" -> 1, "d" -> 2))
    assertEquals(Network.descendants("a", c, 6).map(_._1), Vector("b", "c", "d", "e"))
  }
