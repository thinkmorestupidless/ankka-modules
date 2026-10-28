package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.{Duration, Instant}

class BackoffSuite extends munit.FunSuite with LogCapturing:
  test("1, 5, 15, 60 minutes, then hourly") {
    assertEquals((1 to 7).map(Backoff.next).map(_.toMinutes).toVector, Vector(1L, 5L, 15L, 60L, 60L, 60L, 60L))
  }

  test("exhausted at 24 hours from the first attempt, not at 23 h 59 m") {
    val first = Instant.parse("2026-10-25T10:00:00Z")
    assert(!Backoff.exhausted(first, first.plus(Duration.ofHours(23).plusMinutes(59))))
    assert(Backoff.exhausted(first, first.plus(Duration.ofHours(24))))
  }
