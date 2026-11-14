package io.eitheror.casino.support.domain

import io.eitheror.casino.support.limits.{Rate, Take, TurnWindowState}
import org.scalacheck.{Gen, Prop}

import java.time.Instant

/** `Rate` (FR-024; data-model.md `TurnWindow`): at most the brand's turns in any minute, and when to try again. */
class RateSuite extends munit.ScalaCheckSuite:
  private val at = Instant.parse("2026-11-11T10:00:00Z")

  private def taken(times: Seq[Instant], limit: Int): (TurnWindowState, Vector[Take]) =
    times.foldLeft((TurnWindowState(), Vector.empty[Take])) { case ((s, out), t) =>
      val (next, take) = Rate.take(s, t, limit)
      (next, out :+ take)
    }

  test("the limit's turns in a minute are taken; the next is refused until the oldest leaves the window"):
    val (_, takes) = taken((0 until 11).map(i => at.plusSeconds(i)), 10)
    assertEquals(takes.take(10).distinct, Vector(Take.Allowed))
    assertEquals(takes.last, Take.Refused(50L))

  test("a refused turn is not counted, and a minute after the oldest the player may send again"):
    val (s, _) = taken((0 until 10).map(i => at.plusSeconds(i)), 10)
    val (after, refused) = Rate.take(s, at.plusSeconds(30), 10)
    assertEquals((refused, after.times.size), (Take.Refused(30L), 10))
    assertEquals(Rate.take(after, at.plusSeconds(60), 10)._2, Take.Allowed)

  test("the wait is at least a second"):
    val (s, _) = taken(Seq(at), 1)
    assertEquals(Rate.take(s, at.plusMillis(59_900), 1)._2, Take.Refused(1L))

  property("no minute ever holds more than the limit's allowed turns"):
    Prop.forAll(Gen.listOf(Gen.choose(0L, 600_000L)), Gen.choose(1, 12)) { (offsets, limit) =>
      val times   = offsets.sorted.map(at.plusMillis)
      val allowed = times.zip(taken(times, limit)._2).collect { case (t, Take.Allowed) => t }
      allowed.forall(t => allowed.count(o => !o.isAfter(t) && o.isAfter(t.minus(Rate.Window))) <= limit)
    }
