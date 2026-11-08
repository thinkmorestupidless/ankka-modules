package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{Recorded, ReportingService, Settings}
import io.eitheror.casino.reporting.Facts.*
import io.eitheror.casino.reporting.intake.GrantEvent.*

/** T021: `Grant` over every family, a repeat, free rounds' wins; `rewards-facts` and when it is registered. */
class GrantSuite extends munit.FunSuite:
  private def kit = EventSourcedTestKit.of(Grant, Grant.id(1, "b-1"))
  private def record(k: EventSourcedTestKit[Grant, GrantState, GrantEvent], fact: f.CasinoRewardsFact) =
    k.call(Grant.record)(GrantFact(FactId.of(f.CasinoRewards, fact), fact))

  private val granted = f.BonusGranted("p-1", "b-1", "Welcome", "o-1", "deposit_match", m(1000), eur, None, at)

  test("a funds grant: its offer, name, type, amount and currency; active until it ends") {
    val k = kit
    val e = record(k, granted).events.head
    assertEquals(e, Granted(FactId.of(f.CasinoRewards, granted), "b-1", "p-1", "o-1", "Welcome", "deposit_match", 1000, Some("EUR"), at))
    val s = k.currentState
    assertEquals((s.player, s.offerId, s.name, s.offerType, s.amount, s.currency, s.status), ("p-1", "o-1", "Welcome", "deposit_match", 1000L, Some("EUR"), "active"))
  }

  test("completed is converted; forfeited, cancelled and expired end it as named") {
    Seq(f.BonusCompleted("p-1", "b-1", "Welcome", "o-1", "deposit_match", at) -> "converted",
      f.BonusForfeited("p-1", "b-1", "Welcome", "o-1", "deposit_match", "wagering", at) -> "forfeited",
      f.BonusCancelled("p-1", "b-1", "Welcome", "o-1", "deposit_match", m(1000), eur, at) -> "cancelled",
      f.GrantExpired("p-1", "b-1", "Welcome", "o-1", "deposit_match", at) -> "expired").foreach { (fact, status) =>
      val k = kit
      record(k, granted): Unit
      record(k, fact): Unit
      assertEquals((k.currentState.status, k.currentState.endedAt), (status, Some(at)))
    }
  }

  test("free rounds: granted with no amount; each settlement adds its win in its currency") {
    val k = kit
    val e = record(k, f.FreeSpinsGranted("p-1", "b-1", "Spins", "o-2", "free_spins", 10, Vector("g-1"), at)).events.head
    assertEquals(e.asInstanceOf[Granted].freeRounds, 10)
    record(k, f.FreeSpinsSettled("p-1", "b-1", "Spins", "o-2", "free_spins", m(250), eur, at)): Unit
    record(k, f.FreeSpinsSettled("p-1", "b-1", "Spins", "o-2", "free_spins", m(50), eur, at.plusSeconds(60))): Unit
    assertEquals((k.currentState.freeRoundsWin, k.currentState.currency), (300L, Some("EUR")))
  }

  test("a repeat persists nothing") {
    val k = kit
    record(k, granted): Unit
    assert(!record(k, granted).persisted)
  }

  test("rewards-facts sends a grant's fact to its grant and ignores a code entry") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(RewardsFacts(Settings("goldvibe", modules = Set("casino"))), client = r.client)
    assertEquals(kit.onMessage(Some(granted)).effect, ConsumerEffect.Done)
    assertEquals(kit.onMessage(Some(f.BonusCodeEntered("p-1", "WELCOME", at))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
    assertEquals(r.entityCalls.map(c => (c.component, c.entity, c.method)), Vector(("grant", "g1/b-1", "record")))
  }

  test("rewards-facts is registered only when the brand runs casino") {
    def ids(s: Settings) = ReportingService.descriptors(s).map(_.componentId.toString)
    assert(ids(Settings("goldvibe", modules = Set("casino"))).contains("rewards-facts-g1"))
    assert(!ids(Settings("goldvibe", modules = Set("predictions"))).exists(_.startsWith("rewards-facts")))
  }
