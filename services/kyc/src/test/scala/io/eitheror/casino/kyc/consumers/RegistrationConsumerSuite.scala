package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{PlayerClosed, PlayerLoggedIn, PlayerRegistered}
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.{KycEvent, KycRecord}
import io.eitheror.casino.kyc.record.KycEvent.*

import java.time.{Duration, Instant}

/** R10 and FR-031: a registration sets the deadline once, by the policy read with it; a closure closes the record. */
class RegistrationConsumerSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-30T12:00:00Z")

  private def registered(player: String = "p-1") = PlayerRegistered(player, at, None, None, None, Some("MT"), Some("EUR"), None, at)
  private def closed(player: String = "p-1")     = PlayerClosed(player, "active", "closed", "player", None, None, at.plusSeconds(60))

  test("the command carries the registration time and the policy's window"):
    val c = RegistrationConsumer.command(registered(), Profiles.Ukgc)
    assertEquals((c.playerId, c.registeredAt, c.windowDays, c.at), ("p-1", at, Some(3), at))

  test("a registration under a window sets the deadline once; a repeat changes nothing"):
    val kit   = EventSourcedTestKit.of(KycRecord, "p-1")
    val first = kit.call(KycRecord.setDeadline)(RegistrationConsumer.command(registered(), Profiles.Ukgc))
    assertEquals(first.events, Vector[KycEvent](DeadlineSet("p-1", at.plus(Duration.ofDays(3)), false, at)))
    assertEquals(kit.call(KycRecord.setDeadline)(RegistrationConsumer.command(registered(), Profiles.Mga)).events, Vector.empty[KycEvent])

  test("a registration under a policy with no window sets no deadline"):
    val kit = EventSourcedTestKit.of(KycRecord, "p-1")
    val none = Profiles.Ukgc.copy(windowDays = None)
    assertEquals(kit.call(KycRecord.setDeadline)(RegistrationConsumer.command(registered(), none)).events, Vector.empty[KycEvent])

  test("a closure closes the record and its open flags, once"):
    val kit = EventSourcedTestKit.of(KycRecord, "p-1")
    kit.call(KycRecord.setDeadline)(RegistrationConsumer.command(registered(), Profiles.Ukgc)): Unit
    val c = ClosureConsumer.command(closed())
    assertEquals(kit.call(KycRecord.close)(c).events, Vector[KycEvent](Closed(at.plusSeconds(60))))
    assert(kit.currentState.closed)
    assertEquals(kit.call(KycRecord.close)(c).events, Vector.empty[KycEvent])

  test("other facts of casino.players, and a fact that cannot be read, are ignored and call nothing"):
    val other = PlayerLoggedIn("p-1", "s-1", at)
    for kit <- Seq(ConsumerTestKit.of(RegistrationConsumer), ConsumerTestKit.of(ClosureConsumer)) do
      assertEquals(kit.onMessage(Some(other)).effect, ConsumerEffect.Ignore)
      assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
