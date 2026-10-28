package io.eitheror.casino.wallet.config

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.wallet.entity.{BetSwitches, TurnoverTerms}

import java.time.Instant

class BrandConfigSuite extends munit.FunSuite with LogCapturing:

  private val t0 = Instant.parse("2026-10-09T10:00:00Z")

  test("a brand never configured has the original's defaults (research R7)") {
    val config = KeyValueEntityTestKit.of(BrandConfigEntity, "brand").call(BrandConfigEntity.get).replyValue
    assertEquals(config.switchesFor("slots"), BetSwitches(100L, bonusWageringCounts = true, partialSplitsAllowed = true))
    assertEquals(config.maxConcurrentAwards, None)
    assertEquals(config.turnover, TurnoverTerms(1L, "gross_wagered"))
  }

  test("rewards' switches leave the operator's turnover terms, and the terms leave the switches") {
    val kit = KeyValueEntityTestKit.of(BrandConfigEntity, "brand")
    kit.call(BrandConfigEntity.setTurnoverTerms)(SetTurnoverTerms(TurnoverTerms(3L, "gross_wagered"), "op-1", t0))
    kit.call(BrandConfigEntity.setBetSwitches)(SetBetSwitches(Map("table" -> 10L), bonusWageringCounts = false, partialSplitsAllowed = false, Some(1), "rewards", t0))
    val after = kit.call(BrandConfigEntity.setTurnoverTerms)(SetTurnoverTerms(TurnoverTerms(5L, "gross_wagered"), "op-2", t0)).replyValue
    assertEquals(after.turnover.multiplier, 5L)
    assertEquals(after.switchesFor("table"), BetSwitches(10L, bonusWageringCounts = false, partialSplitsAllowed = false))
    assertEquals(after.switchesFor("slots").contributionRate, 100L)
    assertEquals(after.maxConcurrentAwards, Some(1))
  }

  test("a negative rate or multiplier, or a limit below one, is refused and changes nothing") {
    val kit = KeyValueEntityTestKit.of(BrandConfigEntity, "brand")
    assert(kit.call(BrandConfigEntity.setBetSwitches)(SetBetSwitches(Map("slots" -> -1L), true, true, None, "rewards", t0)).isError)
    assert(kit.call(BrandConfigEntity.setBetSwitches)(SetBetSwitches(Map.empty, true, true, Some(0), "rewards", t0)).isError)
    assert(kit.call(BrandConfigEntity.setTurnoverTerms)(SetTurnoverTerms(TurnoverTerms(-1L, "gross_wagered"), "op", t0)).isError)
    assertEquals(kit.currentState, BrandConfig())
  }
