package io.eitheror.casino.engagement.send

import java.time.Duration

/** Research R5's waits: 500 ms doubling, ±25 %, four attempts, then parked. */
class AttemptsSuite extends munit.FunSuite:
  private val base = Duration.ofMillis(500)

  test("the waits are 500 ms, 1 s and 2 s at no jitter") {
    assertEquals((1 to 3).map(n => Attempts.delay(base, n, 0.0).toMillis), Seq(500L, 1000L, 2000L))
  }

  test("the jitter moves a wait by a quarter at most, either way") {
    assertEquals((Attempts.delay(base, 2, -1.0).toMillis, Attempts.delay(base, 2, 1.0).toMillis), (750L, 1250L))
    assertEquals(Attempts.delay(base, 2, 5.0).toMillis, 1250L)
    for _ <- 1 to 200 do
      val d = Attempts.delay(base, 3).toMillis
      assert(d >= 1500L && d <= 2500L, d.toString)
  }

  test("the fourth failure parks; a retried send parks after one more") {
    assertEquals((1 to 4).map(Attempts.parked(_, 4)), Seq(false, false, false, true))
    assert(Attempts.parked(5, 5))
  }
