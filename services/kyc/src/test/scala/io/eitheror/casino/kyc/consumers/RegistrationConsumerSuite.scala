package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{PlayerClosed, PlayerLoggedIn, PlayerProfileUpdated, PlayerRegistered}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.{KycEvent, KycRecord, ProfileField}
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

  test("a registration's profile is the record's first profile facts; one with no field gives none"):
    val full = registered().copy(givenName = Some(Personal("Ada")), familyName = Some(Personal("Lovelace")),
      dateOfBirth = Some(Personal("1990-01-02")))
    val c = ProfileConsumer.registered(full, Vector.empty, at).get
    assertEquals(c.present, Set(ProfileField.FirstName, ProfileField.LastName, ProfileField.DateOfBirth, ProfileField.Country))
    assertEquals(c.adultFrom.map(_.reveal.toString), Some("2008-01-02"))
    assert(!c.requiredChanged, "a registration changes no required field")
    assertEquals(ProfileConsumer.registered(full, Vector.empty, at.plusSeconds(60)).flatMap(_.changedAt), Some(at))
    assertEquals(ProfileConsumer.registered(registered().copy(country = None), Vector.empty, at), None)

  test("a registration read after an edit changes nothing: the edit carried every field held"):
    val kit  = EventSourcedTestKit.of(KycRecord, "p-1")
    val edit = PlayerProfileUpdated("p-1", Vector("telephone"), Some(Personal("Ada")), Some(Personal("Lovelace")), Some(Personal("1990-01-02")),
      Some(Personal("+447700900123")), None, None, None, Some("MT"), at)
    kit.call(KycRecord.profileChanged)(ProfileConsumer.command(edit, Vector.empty, at)): Unit
    val reg = ProfileConsumer.registered(registered().copy(givenName = Some(Personal("Ada"))), Vector.empty, at).get
    assertEquals(kit.call(KycRecord.registeredProfile)(reg).events, Vector.empty[KycEvent])
    assert(kit.currentState.profile.present.contains(ProfileField.Phone))

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
