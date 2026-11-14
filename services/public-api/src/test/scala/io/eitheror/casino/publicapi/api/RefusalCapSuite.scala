package io.eitheror.casino.publicapi.api

import java.time.Instant

/** FR-036's bound on a key's refusals (second review round): recorded up to the cap a minute, the rest named later. */
class RefusalCapSuite extends munit.FunSuite:
  private var at = Instant.parse("2026-11-14T10:00:00Z")
  private def cap = RefusalCap(3, () => at)

  test("the first refusals of a minute are recorded, the ones over the cap counted") {
    val c = cap
    assertEquals((1 to 5).map(_ => c.refused("k1")).toVector, Vector(Some(0), Some(0), Some(0), None, None))
  }

  test("the key's next recorded refusal names how many were not recorded; another key has its own count") {
    at = Instant.parse("2026-11-14T10:00:00Z")
    val c = cap
    (1 to 5).foreach(_ => c.refused("k1"))
    assertEquals(c.refused("k2"), Some(0))
    at = at.plusSeconds(60)
    assertEquals(c.refused("k1"), Some(2))
    assertEquals(c.refused("k1"), Some(0))
  }

  test("refusals over the cap in several minutes add up until one is recorded") {
    at = Instant.parse("2026-11-14T11:00:00Z")
    val c = cap
    (1 to 4).foreach(_ => c.refused("k1"))
    at = at.plusSeconds(60)
    (1 to 3).foreach(_ => c.refused("k1")) // recorded: 1 then names it, 2 more
    assertEquals(c.refused("k1"), None)
    at = at.plusSeconds(60)
    assertEquals(c.refused("k1"), Some(1))
  }
