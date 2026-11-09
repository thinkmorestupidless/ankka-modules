package io.eitheror.casino.audit.retention

import java.time.Instant

class RetentionSuite extends munit.FunSuite:

  test("the cutoff is calendar years before now, in UTC") {
    assertEquals(Retention.cutoff(Instant.parse("2026-11-09T10:00:00Z"), 7), Instant.parse("2019-11-09T10:00:00Z"))
    assertEquals(Retention.cutoff(Instant.parse("2028-02-29T00:00:00Z"), 1), Instant.parse("2027-02-28T00:00:00Z"))
  }

  test("retention is raised, and refused below the installation's or the brand's current") {
    assertEquals(Retention.raise(current = 7, asked = 10, floor = 7), Right(10))
    assertEquals(Retention.raise(current = 7, asked = 7, floor = 7), Right(7))
    assert(Retention.raise(current = 7, asked = 5, floor = 7).left.exists(_.startsWith("below_floor: 5 years is below the installation's 7")))
    assert(Retention.raise(current = 10, asked = 8, floor = 7).left.exists(_.startsWith("below_floor: 8 years is below the brand's current 10")))
  }
