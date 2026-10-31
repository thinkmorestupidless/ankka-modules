package io.eitheror.casino.markets.facts

import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import com.thinkmorestupidless.predictions.markets.*
import io.eitheror.casino.markets.{MarketsService, Settings}
import io.eitheror.casino.markets.it.Kit

import java.time.{Instant, LocalDate}
import scala.jdk.CollectionConverters.*

/** The mirror (FR-007, T038): a question with a hypothesis per outcome and its market, and only when the brand names a reasoning service. */
class MarketMirrorSuite extends munit.FunSuite:
  private val definition = Definition("Will it rain?", "In Cape Town", "weather", Vector(OutcomeId("yes"), OutcomeId("no")), "EUR", None,
    Instant.parse("2026-11-30T12:00:00Z"), LocalDate.parse("2026-12-01"), "The weather service's record", "SAWS")

  test("an approved market is a question with one hypothesis per outcome, then a market with the brand as its venue") {
    val writes = MarketMirror.writes(Market(MarketId("m1"), Some(definition), MarketState.Approved), "goldvibe")
    assertEquals(writes.map(_._1), Vector("/questions", "/markets"))
    val (question, market) = (writes(0)._2, writes(1)._2)
    assertEquals(question.get("id").asText, "m1")
    assertEquals(question.get("hypotheses").elements.asScala.map(_.get("id").asText).toVector, Vector("m1.yes", "m1.no"))
    assertEquals((market.get("question").asText, market.get("venue").asText, market.get("closesAt").asText),
      ("m1", "goldvibe", "2026-11-30T12:00:00Z"))
    assertEquals(market.get("outcomes").elements.asScala.map(o => o.get("outcome").asText -> o.get("hypothesis").asText).toVector,
      Vector("yes" -> "m1.yes", "no" -> "m1.no"))
    assertEquals(MarketMirror.writes(Market.empty, "goldvibe"), Vector.empty)
  }

  test("the mirror is registered only when the brand's values name a reasoning service") {
    def ids(s: Settings) = MarketsService(TimerRuntime(), s).descriptors.map(_.componentId.toString).toSet
    assert(!ids(Kit.Base).contains("market-mirror"))
    assert(ids(Kit.Base.copy(reasoningService = Some("reasoner/reasoning"))).contains("market-mirror"))
    val env = Map("CASINO_BRAND" -> "goldvibe", "PREDICTIONS_CATEGORIES" -> "weather", "PREDICTIONS_CURRENCIES" -> "EUR",
      "PREDICTIONS_MAX_ORDER_COST" -> "100", "PREDICTIONS_MAX_POSITION" -> "10")
    assertEquals(Settings.fromEnvironment(env).map(_.reasoningService), Right(None))
    assertEquals(Settings.fromEnvironment(env + ("REASONING_SERVICE" -> " reasoner/reasoning ")).map(_.reasoningService),
      Right(Some("reasoner/reasoning")))
  }
