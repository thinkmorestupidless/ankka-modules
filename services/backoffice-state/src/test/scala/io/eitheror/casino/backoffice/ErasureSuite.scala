package io.eitheror.casino.backoffice

import scala.collection.mutable
import scala.concurrent.duration.*

/** An operator's erasure finds sessions through an eventually consistent view, so it looks again (review Erasure.scala:27). */
class ErasureSuite extends munit.FunSuite:

  /** The view's answers, one per look, and the sessions ended, in order. */
  private def run(looks: Vector[String]*): (Int, Vector[String], Vector[FiniteDuration]) =
    val answers = mutable.Queue(looks*)
    val ended   = mutable.ArrayBuffer.empty[String]
    val slept   = mutable.ArrayBuffer.empty[FiniteDuration]
    val n = Erasure.endAll(() => if answers.isEmpty then Vector.empty else answers.dequeue(), 2.seconds, slept += _) { s =>
      ended += s
      true
    }
    (n, ended.toVector, slept.toVector)

  test("a session the view shows only on the second look, signed in just before the erasure, is ended too") {
    val (n, ended, slept) = run(Vector("a"), Vector("a", "b"), Vector("a", "b"))
    assertEquals(ended, Vector("a", "b"))
    assertEquals(n, 2)
    assertEquals(slept, Vector(2.seconds, 2.seconds))
  }

  test("a view empty at first is looked at again before the erasure answers") {
    val (n, ended, _) = run(Vector.empty, Vector("late"))
    assertEquals((n, ended), (1, Vector("late")))
  }

  test("a look that names nothing new ends the erasure; a session is ended once however often it is named") {
    val (n, ended, slept) = run(Vector("a", "b"), Vector("a", "b"))
    assertEquals((n, ended), (2, Vector("a", "b")))
    assertEquals(slept.size, 1)
  }

  test("it looks at most three times") {
    val (_, ended, slept) = run(Vector("a"), Vector("b"), Vector("c"), Vector("d"))
    assertEquals(ended, Vector("a", "b", "c"))
    assertEquals(slept.size, 2)
  }
