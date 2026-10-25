package io.eitheror.casino.ingest.merchant

import io.eitheror.casino.ingest.Background.given

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*

/** Research R6: the caller's bound on merchant, which never cancels the call. */
class BoundSuite extends munit.FunSuite:

  private def after[A](millis: Long, a: => A): Future[A] = Future { Thread.sleep(millis); a }

  test("a call answered within the bound is Answered") {
    assertEquals(Bound.race(after(50, 7), 500.millis), Raced.Answered(7))
  }

  test("a call answered after the bound is Unanswered, and its late answer still arrives") {
    val started = System.nanoTime()
    Bound.race(after(800, 7), 500.millis) match
      case Raced.Unanswered(late) =>
        assert((System.nanoTime() - started).nanos < 750.millis, "answered at the bound, not when the call finished")
        assertEquals(Await.result(late, 2.seconds), 7)
      case other => fail(s"expected Unanswered, got $other")
  }

  test("a failed call is Failed at once, not at the bound") {
    val started = System.nanoTime()
    val r       = Bound.race(Future.failed[Int](IllegalStateException("unreachable")), 5.seconds)
    assert(r.isInstanceOf[Raced.Failed[?]], r.toString)
    assert((System.nanoTime() - started).nanos < 1.second)
    assert(Bound.race(Promise[Int]().future, 100.millis).isInstanceOf[Raced.Unanswered[?]])
    assert(Bound.race[Int](throw IllegalStateException("no client"), 100.millis).isInstanceOf[Raced.Failed[?]])
  }
