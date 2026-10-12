package io.eitheror.casino.gameprovider.facts

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, TestTransport}
import io.eitheror.casino.gameprovider.catalogue.{CountRound, GameRounds, RoundCounter}
import io.eitheror.casino.gameprovider.catalogue.GameRoundsCodecs.given
import com.thinkmorestupidless.ankka.core.Serializers.given
import io.eitheror.casino.gameprovider.rounds.{RoundEvent, RoundOpening}

import java.time.Instant
import scala.collection.mutable

/** Popularity counts rounds played (FR-023, Clarifications 2026-10-09), one per round opened with a game. */
class RoundCounterSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-09T10:00:00Z")

  private def kit(counted: mutable.Buffer[CountRound]) =
    val transport = TestTransport().stub(GameRounds.increment)((c: CountRound) => { counted += c; counted.size.toLong })
    ConsumerTestKit.of(RoundCounter, transport.client)

  test("a round opened for a game counts one round for that provider's game") {
    val counted = mutable.Buffer.empty[CountRound]
    kit(counted).onMessage(RoundEvent.RoundOpened(RoundOpening("st8", "r-1", "p-1", "EUR", Some("book-of-ra"), None, at)), "st8/r-1", 1)
    assertEquals(counted.toVector, Vector(CountRound("st8", "book-of-ra")))
  }

  test("a round with no game, and every other event, counts nothing") {
    val counted = mutable.Buffer.empty[CountRound]
    val k       = kit(counted)
    k.onMessage(RoundEvent.RoundOpened(RoundOpening("st8", "buyin-1", "p-1", "EUR", None, None, at)), "st8/buyin-1", 1)
    k.onMessage(RoundEvent.FreeRoundsTagged("g"), "st8/r-1", 2)
    assertEquals(counted.toVector, Vector.empty)
  }

  test("delivered twice, a round is counted twice: the at-least-once limit research R10 accepts for a ranking") {
    val counted = mutable.Buffer.empty[CountRound]
    val k       = kit(counted)
    val opened  = RoundEvent.RoundOpened(RoundOpening("st8", "r-1", "p-1", "EUR", Some("g"), None, at))
    k.onMessage(opened, "st8/r-1", 1); k.onMessage(opened, "st8/r-1", 1)
    assertEquals(counted.size, 2)
  }
