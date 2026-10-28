package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/** The core's pure parts: durations, the seeded generator (FR-004, SC-003) and the schedule (FR-005). */
class CoreSuite extends munit.FunSuite with LogCapturing:

  test("durations read as scenario data writes them") {
    assertEquals(Durations.parse("250ms"), Right(250.millis))
    assertEquals(Durations.parse("30s"), Right(30.seconds))
    assertEquals(Durations.parse("10m"), Right(10.minutes))
    assertEquals(Durations.parse("8h"), Right(8.hours))
    assert(Durations.parse("ten minutes").isLeft)
  }

  test("two generators with one seed agree, step by step, whatever order they are asked in") {
    val a = Seeded(42L)
    val b = Seeded(42L)
    val forwards  = (0 until 20).map(i => (a.amount(i, "0.10", "5.00"), a.id(i), a.pick(i, 7)))
    val backwards = (0 until 20).reverse.map(i => (b.amount(i, "0.10", "5.00"), b.id(i), b.pick(i, 7))).reverse
    assertEquals(forwards, backwards)
  }

  test("a different seed draws differently") {
    assertNotEquals((0 until 10).map(Seeded(1L).id(_)), (0 until 10).map(Seeded(2L).id(_)))
  }

  test("amounts are two decimal places within the range, ids are UUID v4") {
    val s = Seeded(7L)
    (0 until 200).foreach { i =>
      val a = BigDecimal(s.amount(i, "0.10", "1.00"))
      assert(a >= BigDecimal("0.10") && a <= BigDecimal("1.00") && a.scale == 2, a.toString)
      assert(s.id(i).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), s.id(i))
    }
  }

  test("the provider's default schedule: every 30 seconds for ten minutes, then every ten minutes, for eight hours") {
    val schedule = Schedule.providerDefault(5.seconds, Set("error"))
    assertEquals(schedule.nextInterval(Duration.Zero), Some(30.seconds))
    assertEquals(schedule.nextInterval(9.minutes + 59.seconds), Some(30.seconds))
    assertEquals(schedule.nextInterval(10.minutes), Some(10.minutes))
    assertEquals(schedule.nextInterval(8.hours), None)
    // 21 attempts in the first ten minutes (at 0, 30s, ... 10m), then one every ten minutes to 8h
    assertEquals(schedule.maxAttempts, 21 + 47)
  }

  test("no intervals means one attempt, never retried") {
    assertEquals(Schedule.once(5.seconds).nextInterval(Duration.Zero), None)
    assertEquals(Schedule.once(5.seconds).maxAttempts, 1)
  }

  test("a retry never runs past the cap") {
    val s = Schedule(1.second, Vector(Schedule.Interval(400.millis, None)), 1.second, Set("error"))
    assertEquals(s.nextInterval(500.millis), Some(400.millis))
    assertEquals(s.nextInterval(700.millis), None)
  }
