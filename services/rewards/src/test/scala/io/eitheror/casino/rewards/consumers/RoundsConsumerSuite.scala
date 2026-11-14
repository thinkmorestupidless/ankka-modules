package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{RoundOpened, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.rewards.reward

import java.time.Instant

/** data-model.md's `RoundsConsumer`: a settled round naming free rounds goes to that reward; any other is not read. */
class RoundsConsumerSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")

  private def settled(grant: Option[String], win: Long = 150L) =
    RoundSettled("p-1", Eur, "rd-1", "st8", "prd-1", "g-1", "s-1", MinorUnits.unsafe(0), MinorUnits.unsafe(win), SignedMinorUnits(win),
      RoundSettled.Outcome.Win, Vector.empty, Vector.empty, Vector.empty, grant, at)

  test("a round naming free rounds is counted on the reward named, with its win, at its time"):
    val f = settled(Some("fr-1"))
    assertEquals(RoundsConsumer.round(f), Some(("fr-1", reward.RoundSettled("rd-1", 150L, at), f)))

  test("a round of the player's own money, or naming no grant, is not read"):
    assertEquals(RoundsConsumer.round(settled(None)), None)
    assertEquals(RoundsConsumer.round(settled(Some("  "))), None)
    assertEquals(ConsumerTestKit.of(RoundsConsumer).onMessage(Some(settled(None))).effect, ConsumerEffect.Ignore)

  test("an opened round is not read, even one naming free rounds"):
    val opened = RoundOpened("p-1", Eur, "rd-1", "st8", "prd-1", "g-1", "s-1", Some("fr-1"), at)
    assertEquals(RoundsConsumer.round(opened), None)
    assertEquals(ConsumerTestKit.of(RoundsConsumer).onMessage(Some(opened)).effect, ConsumerEffect.Ignore)

  test("a fact that cannot be read is ignored"):
    assertEquals(ConsumerTestKit.of(RoundsConsumer).onMessage(None).effect, ConsumerEffect.Ignore)
