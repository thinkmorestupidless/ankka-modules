package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.{Binding, Reasons}
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.attribution.views.Records
import io.eitheror.casino.attribution.{Touch}
import io.eitheror.casino.attribution.record.Touches
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, BonusCodeEntered, CasinoRewards, Registration}

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/** The binding (US1 tag cases, US2 code cases; FR-002 to FR-009, FR-023): once, with the pseudonym, or organic with the reason. */
class BindingSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def pseudonym(network: String, player: String) =
    Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName(network)).get), player)

  private def registrations(p: String) =
    kit.attributionMessages.filter(_.message.metadata.get("ce-id").contains(s"registration/$p"))

  private def recorded(player: String): Record.State =
    kit.eventually(s"$player's record")(Some(kit.record(player)).filter(_.registeredAt.isDefined))

  private def organic(player: String): Record.Refusal =
    val r = recorded(player)
    assertEquals(r.binding, None)
    r.refused.getOrElse(fail(s"$player is organic with no reason"))

  /** Lets every cached document expire and fetches them again, as the minute's timer would. */
  private def refresh(): Unit =
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit

  test("a tag minted a day ago binds once, with the pseudonym, and emits one registration fact") {
    val link   = kit.fresh("lnk")
    val tag    = kit.network.mint("aff-1", link, Some("autumn"), Vector("s1"), iat = kit.now.minus(JDuration.ofDays(1)))
    val player = kit.bindPlayer(Some(tag), clickId = Some("c-9"), country = Some("ZA"))
    val p      = pseudonym("programme", player)

    val row = kit.eventually("the bound row")(kit.views.forView(Records).ask(Records.byPlayer, "player" -> player).headOption.filter(_.bound))
    assertEquals((row.network, row.affiliate), (Some("programme"), Some("aff-1")))
    val b = recorded(player).binding.get
    assertEquals((b.link, b.campaign, b.subIds, b.clickId, b.pseudonym, b.touch), (link, Some("autumn"), Vector("s1"), Some("c-9"), p,
      Binding.Tag))

    val message = kit.eventually("the registration fact")(registrations(p).headOption)
    assertEquals(message.message.key, Some(p))
    AffiliatesAttribution.serializer.fromBytes(message.message.payload) match
      case r: Registration => assertEquals((r.pseudonym, r.country, r.link), (p, Some("ZA"), link))
      case other           => fail(s"not a registration: $other")

    kit.bindPlayer(Some(tag), player = player)
    Thread.sleep(1500)
    assertEquals(registrations(p).size, 1)
  }

  test("a tag minted 31 days ago is stale: organic, the tag kept, no fact") {
    val tag    = kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now.minus(JDuration.ofDays(31)))
    val player = kit.bindPlayer(Some(tag))
    assertEquals(organic(player), Record.Refusal(tag, Reasons.Stale, recorded(player).registeredAt.get))
    Thread.sleep(1000)
    assertEquals(registrations(pseudonym("programme", player)), Vector.empty)
  }

  test("a bad signature, an unknown network, a revoked link and a revoked affiliate leave the player organic with each reason") {
    val good   = kit.network.mint("aff-1", kit.fresh("lnk"))
    val forged = good.dropRight(4) + (if good.endsWith("AAAA") then "BBBB" else "AAAA")
    assertEquals(organic(kit.bindPlayer(Some(forged))).reason, Reasons.BadSignature)

    val stranger = FakeNetwork(kit.fresh("stranger"))
    try assertEquals(organic(kit.bindPlayer(Some(stranger.mint("aff-1", "lnk-1")))).reason, Reasons.UnknownNetwork)
    finally stranger.stop()

    val link = kit.fresh("lnk")
    val affiliate = kit.fresh("aff")
    kit.network.revokedLinks = Set(link)
    kit.network.revokedAffiliates = Set(affiliate)
    refresh()
    assertEquals(organic(kit.bindPlayer(Some(kit.network.mint("aff-1", link)))).reason, Reasons.RevokedLink)
    assertEquals(organic(kit.bindPlayer(Some(kit.network.mint(affiliate, kit.fresh("lnk"))))).reason, Reasons.RevokedAffiliate)
  }

  test("a key the network withdrew, once the cache has refreshed, is withdrawn_key") {
    val old = kit.network.mint("aff-1", kit.fresh("lnk"))
    kit.network.rotate(): Unit
    refresh()
    assertEquals(organic(kit.bindPlayer(Some(old))).reason, Reasons.WithdrawnKey)
  }

  test("a kid the cached key document lacks is withdrawn_key, and registration fetches nothing (FR-023)") {
    kit.network.rotate(withdraw = false): Unit
    val tag    = kit.network.mint("aff-1", kit.fresh("lnk"))
    val before = kit.network.received.size
    assertEquals(organic(kit.bindPlayer(Some(tag))).reason, Reasons.WithdrawnKey)
    assertEquals(kit.network.received.size, before)
    refresh()
  }

  test("a network whose key document was never fetched is key_unreachable, fetching nothing; one with no pseudonym key is network_not_ready") {
    val down = FakeNetwork(kit.fresh("down"))
    val bare = FakeNetwork(kit.fresh("bare"))
    try
      down.fail("keys")
      kit.addNetwork(down, legacy = false, codes = false)
      val before = down.received.size
      assertEquals(organic(kit.bindPlayer(Some(down.mint("aff-1", "lnk-1")))).reason, Reasons.KeyUnreachable)
      assertEquals(down.received.size, before)

      kit.addNetwork(bare, legacy = false, codes = false)
      assertEquals(organic(kit.bindPlayer(Some(bare.mint("aff-1", "lnk-1")))).reason, Reasons.NetworkNotReady)
    finally
      down.stop(); bare.stop()
  }

  test("a bound player's second player_registered with another tag changes nothing") {
    val first  = kit.network.mint("aff-1", kit.fresh("lnk"))
    val player = kit.bindPlayer(Some(first))
    val bound  = kit.eventually("bound")(recorded(player).binding)
    kit.bindPlayer(Some(kit.network.mint("aff-2", kit.fresh("lnk"))), player = player)
    Thread.sleep(1500)
    assertEquals(kit.record(player).binding, Some(bound))
    assertEquals(registrations(bound.pseudonym).size, 1)
  }

  test("the same player's pseudonym differs under a second network's key") {
    val other = kit.fresh("net")
    kit.testKit.secrets.put(Pseudonyms.secretName(other), Pseudonyms.newKey())
    assertNotEquals(pseudonym("programme", "p-same"), pseudonym(other, "p-same"))
  }

  // ── codes (US2, research R7) ───────────────────────────────────────────────────────────────────

  /** A code the network's document lists for this brand, with a tag for a fresh link; answers the code and the link. */
  private def listCode(affiliate: String = "aff-7"): (String, String) =
    val code = kit.fresh("SUMMER").toUpperCase
    val link = kit.fresh("lnk")
    kit.network.codes = kit.network.codes.updated(code, "offer-1" -> kit.network.mint(affiliate, link))
    kit.network.codesVersion += 1
    refresh()
    code -> link

  private def enter(player: String, code: String, at: java.time.Instant = kit.now): Unit =
    kit.publish(CasinoRewards, BonusCodeEntered(player, code, at))

  test("a code at registration with no tag binds with touch registration_code and no click id, and emits one registration") {
    val (code, link) = listCode()
    val player = kit.bindPlayer(None, clickId = Some("c-ignored"), bonusCode = Some(code.toLowerCase))
    val b = kit.eventually("bound by the code")(recorded(player).binding)
    assertEquals((b.affiliate, b.link, b.touch, b.clickId), ("aff-7", link, Binding.RegistrationCode, None))
    kit.eventually("the registration fact")(registrations(b.pseudonym).headOption)
  }

  test("a code beside a valid tag: bound by the tag, the code a later touch; a code nobody lists: organic, touch unknown") {
    val (code, _) = listCode()
    val tagLink = kit.fresh("lnk")
    val player = kit.bindPlayer(Some(kit.network.mint("aff-1", tagLink)), bonusCode = Some(code))
    val r = kit.eventually("bound")(Some(recorded(player)).filter(_.bound))
    assertEquals(r.binding.map(_.link), Some(tagLink))
    assertEquals(r.touches, Vector(Touch(Touch.Code, code.toLowerCase, r.registeredAt.get, Touches.Later)))

    val nobody = kit.bindPlayer(None, bonusCode = Some("NOBODY99"))
    val o = recorded(nobody)
    assertEquals((o.binding, o.touches.map(t => (t.value, t.outcome))), (None, Vector("nobody99" -> Touches.Unknown)))
  }

  test("a code entered 10 days after an organic registration binds with later_code, the registration dated to the entry") {
    val (code, link) = listCode()
    val player = kit.bindPlayer(None, at = kit.now.minus(JDuration.ofDays(10)))
    recorded(player)
    val entry = kit.now
    enter(player, code, entry)
    val b = kit.eventually("bound later")(kit.record(player).binding)
    assertEquals((b.link, b.touch, b.boundAt), (link, Binding.LaterCode, entry))
    val message = kit.eventually("the registration fact")(registrations(b.pseudonym).headOption)
    AffiliatesAttribution.serializer.fromBytes(message.message.payload) match
      case r: Registration => assertEquals(r.occurredAt, entry)
      case other           => fail(other.toString)
  }

  test("a code entered 31 days after registration is a touch; for a bound player, a touch and no change") {
    val (code, _) = listCode()
    val late = kit.bindPlayer(None, at = kit.now.minus(JDuration.ofDays(31)))
    recorded(late)
    enter(late, code)
    val t = kit.eventually("the touch")(Some(kit.record(late)).filter(_.touches.nonEmpty))
    assertEquals((t.binding, t.touches.map(_.outcome)), (None, Vector(Touches.Later)))

    val bound = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
    val before = kit.eventually("bound")(recorded(bound).binding)
    enter(bound, code)
    val after = kit.eventually("the touch")(Some(kit.record(bound)).filter(_.touches.nonEmpty))
    assertEquals(after.binding, Some(before))
    assertEquals(after.touches.map(_.outcome), Vector(Touches.Later))
  }
