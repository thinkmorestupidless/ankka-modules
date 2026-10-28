package io.eitheror.casino.gameprovider.players

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.gameprovider.players.KnownPlayerCodecs.given
import io.eitheror.casino.gameprovider.players.LaunchWindowCodecs.given

import java.time.Instant

/** LaunchWindow (FR-007, research R9), KnownPlayer, and the bet-limits band (FR-018). */
class PlayersSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-09T10:00:00Z")

  test("twenty launches in a minute are admitted and the twenty-first is refused naming the seconds left") {
    val kit = KeyValueEntityTestKit.of(LaunchWindow, "p-1")
    (0 until 20).foreach(i => assert(kit.call(LaunchWindow.launch)(at.plusSeconds(i)).replyValue.admitted, s"launch ${i + 1}"))
    val refused = kit.call(LaunchWindow.launch)(at.plusSeconds(20)).replyValue
    assert(!refused.admitted)
    assertEquals(refused.retryAfterSeconds, 40L)
  }

  test("the refusal changes nothing, and the window opens again after sixty seconds") {
    val kit = KeyValueEntityTestKit.of(LaunchWindow, "p-1")
    (0 until 20).foreach(i => kit.call(LaunchWindow.launch)(at))
    assert(!kit.call(LaunchWindow.launch)(at.plusSeconds(59)).changed)
    assertEquals(kit.call(LaunchWindow.launch)(at.plusSeconds(59)).replyValue.retryAfterSeconds, 1L)
    assert(kit.call(LaunchWindow.launch)(at.plusSeconds(60)).replyValue.admitted)
    assertEquals(kit.currentState.count, 1)
  }

  test("the window's state expires two minutes after its last update") {
    val result = KeyValueEntityTestKit.of(LaunchWindow, "p-1").call(LaunchWindow.launch)(at)
    assert(result.retention.toString.contains("2 minutes") || result.retention.toString.contains("120"), result.retention.toString)
  }

  test("a player made known to a provider stays known, and to a second provider too") {
    val kit = KeyValueEntityTestKit.of(KnownPlayer, "p-1")
    kit.call(KnownPlayer.makeKnown)(MakeKnown("st8", Some("EUR"), at))
    kit.call(KnownPlayer.makeKnown)(MakeKnown("acme", None, at.plusSeconds(5)))
    assert(kit.currentState.knownTo("st8") && kit.currentState.knownTo("acme"))
    assertEquals(kit.currentState.providers("st8"), at, "the first time is kept")
    assertEquals(kit.currentState.currency, Some("EUR"))
    assert(!kit.call(KnownPlayer.makeKnown)(MakeKnown("st8", Some("EUR"), at.plusSeconds(9))).changed)
  }

  test("the bet-limits band is read from the daily deposit limit") {
    val eur = Currency.unsafe("EUR")
    assertEquals(BetLimits.band(None, eur), "medium")
    assertEquals(BetLimits.band(Some(5000), eur), "low")
    assertEquals(BetLimits.band(Some(5001), eur), "medium")
    assertEquals(BetLimits.band(Some(20000), eur), "medium")
    assertEquals(BetLimits.band(Some(20001), eur), "high")
    assertEquals(BetLimits.band(Some(50), Currency.unsafe("JPY")), "low")
  }
