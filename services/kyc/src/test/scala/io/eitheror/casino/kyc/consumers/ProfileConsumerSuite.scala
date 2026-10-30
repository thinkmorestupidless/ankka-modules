package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{PlayerKycSubmitted, PlayerProfileUpdated}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.record.{Category, ProfileField}

import java.time.{Instant, LocalDate}

/** R11: the fact's values become presence and the adult date only; another family or an unreadable fact is ignored. */
class ProfileConsumerSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-30T10:00:00Z")
  private def p(s: String) = Some(Personal(s))

  private def fact(changed: Vector[String] = Vector.empty, dob: Option[Personal[String]] = p("1990-02-28"),
      telephone: Option[Personal[String]] = p("+447700900000"), country: Option[String] = Some("GB")) =
    PlayerProfileUpdated("p-1", changed, p("Ada"), p("Lovelace"), dob, telephone, p("2 New Street"), p("London"), p("N1 1AA"), country, at)

  test("every field with a value is present, and the adult date is the eighteenth birthday"):
    val c = ProfileConsumer.command(fact(), Vector(Category.Identity), at)
    assertEquals(c.present, ProfileField.values.toSet)
    assertEquals(c.adultFrom.map(_.reveal), Some(LocalDate.of(2008, 2, 28)))
    assertEquals((c.playerId, c.policyRequired, c.at), ("p-1", Vector(Category.Identity), at))

  test("a blank or absent field is not present; an unreadable date of birth gives no adult date"):
    val c = ProfileConsumer.command(fact(dob = p("soon"), telephone = p("  "), country = None), Vector.empty, at)
    assertEquals(ProfileField.values.toSet -- c.present, Set(ProfileField.Phone, ProfileField.Country))
    assertEquals(c.adultFrom, None)

  test("a required field changed, named as the fact or the original names it; another field is no change"):
    assert(ProfileConsumer.command(fact(Vector("addressLine")), Vector.empty, at).requiredChanged)
    assert(ProfileConsumer.command(fact(Vector("firstName")), Vector.empty, at).requiredChanged)
    assert(!ProfileConsumer.command(fact(Vector("nickname", "email")), Vector.empty, at).requiredChanged)
    assert(!ProfileConsumer.command(fact(), Vector.empty, at).requiredChanged)

  test("no value is kept: the command holds presence and the adult date only"):
    val text = ProfileConsumer.command(fact(Vector("city")), Vector.empty, at).toString
    Seq("Ada", "Lovelace", "London", "N1 1AA", "+447700900000", "2 New Street", "1990-02-28").foreach(v => assert(!text.contains(v), text))

  test("another family of casino.players, and a fact that cannot be read, are ignored and call nothing"):
    val kit = ConsumerTestKit.of(ProfileConsumer)
    assertEquals(kit.onMessage(Some(PlayerKycSubmitted("p-1", "d-1", "identity_front", at))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
