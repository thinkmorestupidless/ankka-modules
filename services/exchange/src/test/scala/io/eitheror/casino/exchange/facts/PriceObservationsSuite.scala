package io.eitheror.casino.exchange.facts

import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import io.eitheror.casino.exchange.{ExchangeService, Settings}

import java.time.Instant

/** The observations (FR-007, T038): an outcome at most once a minute, and only when the brand names a reasoning service. */
class PriceObservationsSuite extends munit.FunSuite:
  private val t0 = Instant.parse("2026-10-31T12:00:00Z")

  test("five price changes within one minute are one observation; the first a minute later is the next") {
    val changes = Vector(0, 10, 25, 40, 59).map(s => t0.plusSeconds(s.toLong)) :+ t0.plusSeconds(60) :+ t0.plusSeconds(61)
    val observed = changes.foldLeft((Option.empty[Instant], Vector.empty[Instant])) { case ((last, kept), at) =>
      if Observed.due(last, at) then (Some(at), kept :+ at) else (last, kept)
    }._2
    assertEquals(observed, Vector(t0, t0.plusSeconds(60)))
  }

  test("the observations are registered only when the brand's values name a reasoning service") {
    def ids(s: Settings) = ExchangeService(TimerRuntime(), s).descriptors.map(_.componentId.toString).toSet
    assert(!ids(Settings("goldvibe")).contains("exchange-price-observations"))
    assert(ids(Settings("goldvibe", reasoningService = Some("reasoner/reasoning"))).contains("exchange-price-observations"))
    assertEquals(Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe", "REASONING_SERVICE" -> "reasoner/reasoning")).map(_.reasoningService),
      Right(Some("reasoner/reasoning")))
  }
