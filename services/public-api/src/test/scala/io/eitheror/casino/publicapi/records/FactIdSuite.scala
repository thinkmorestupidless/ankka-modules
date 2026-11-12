package io.eitheror.casino.publicapi.records

import io.eitheror.casino.contract.facts.*

import java.time.Instant

class FactIdSuite extends munit.FunSuite:
  private val fact: CasinoSessionsFact = GameSessionStarted("p-1", "s-1", "g-1", "ps-1", Instant.parse("2026-11-12T10:00:00Z"))

  test("a fact's id is reporting's: the same fact gives the id reporting's FactId gave it") {
    // Computed with services/reporting's intake.FactId on 2026-11-12, for the same fact.
    assertEquals(FactId.of(CasinoSessions, fact), "939a00e6cafa364272620e765acbc26c")
  }

  test("a redelivery is the same id; another time is another") {
    assertEquals(FactId.of(CasinoSessions, fact), FactId.of(CasinoSessions, fact))
    val later = GameSessionStarted("p-1", "s-1", "g-1", "ps-1", Instant.parse("2026-11-12T10:00:01Z"))
    assertNotEquals(FactId.of(CasinoSessions, later: CasinoSessionsFact), FactId.of(CasinoSessions, fact))
  }

  test("a record remembers the last 64 ids") {
    val seen = (1 to 70).foldLeft(Vector.empty[String])((s, i) => FactId.remember(s, i.toString))
    assertEquals(seen.size, 64)
    assertEquals(seen.head, "7")
    assertEquals(seen.last, "70")
  }

  test("every family's time is occurredAt") {
    assertEquals(FactId.occurredAt(fact), Instant.parse("2026-11-12T10:00:00Z"))
  }
