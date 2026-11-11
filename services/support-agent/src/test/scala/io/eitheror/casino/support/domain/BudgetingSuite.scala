package io.eitheror.casino.support.domain

import io.eitheror.casino.support.limits.BudgetState
import org.scalacheck.{Gen, Prop}

/** The daily budget's arithmetic (FR-024; R10): each session's tokens counted once, however often they are added. */
class BudgetingSuite extends munit.ScalaCheckSuite:

  test("a session's usage is added as its increase since it was last counted"):
    val b = BudgetState().add("s-1", 300L).add("s-2", 100L).add("s-1", 450L)
    assertEquals((b.tokens, b.counted), (550L, Map("s-1" -> 450L, "s-2" -> 100L)))

  test("adding the same usage again, or less, changes nothing"):
    val b = BudgetState().add("s-1", 300L)
    assertEquals(b.add("s-1", 300L), b)
    assertEquals(b.add("s-1", 120L), b)

  test("spent at the limit, not before"):
    assert(!BudgetState(999L).spent(1000L))
    assert(BudgetState(1000L).spent(1000L))

  property("the total is the sum of each session's highest usage, whatever the order of adds"):
    Prop.forAll(Gen.listOf(Gen.zip(Gen.oneOf("s-1", "s-2", "s-3"), Gen.choose(0L, 10_000L)))) { adds =>
      val b = adds.foldLeft(BudgetState())((s, a) => s.add(a._1, a._2))
      b.tokens == adds.groupBy(_._1).values.map(_.map(_._2).max).sum
    }
