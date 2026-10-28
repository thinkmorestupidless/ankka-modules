package io.eitheror.casino.attribution.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.attribution.{Binding, Json, Reasons}
import io.eitheror.casino.attribution.clicks.Click
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.attribution.views.Records
import io.eitheror.casino.attribution.it.FakeNetwork
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact, Click as ClickFact, Registration}

import java.time.Duration as JDuration

/** features/attribution/landing.feature (US1): the click, the binding, and the tags that bind nothing. */
class LandingFeatures extends AttributionFeatures("landing.feature"):

  private var tag: String      = null
  private var link: String     = null
  private var player: String   = null
  private var reason: String   = null
  private val address          = "198.51.100.23"
  private var bound: Option[Binding] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tag = null; link = null; player = null; reason = null; bound = None

  private def clickId = id("click")
  private def land(): Unit =
    last = kit.request("POST", "/v1/track/click",
      Some(Json.render(Json.obj("btag" -> tag, "clickId" -> clickId, "campaign" -> "autumn"))),
      kit.fromInternet ++ Seq("X-Forwarded-For" -> address, "User-Agent" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)"))
  private def mint(iat: java.time.Instant = kit.now): Unit =
    link = id("lnk")
    tag = kit.network.mint("aff-1", link, Some("autumn"), Vector("s1", "s2"), iat = iat)
  private def record: Record.State =
    kit.eventually("the record")(Some(kit.record(player)).filter(_.registeredAt.isDefined))
  private def pseudonym = Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)
  private def facts(kind: String): Vector[AttributionFact] = kit.factsOn("programme").filter {
    case c: ClickFact    => kind == "click" && c.clickId.contains(clickId)
    case r: Registration => kind == "registration" && player != null && r.pseudonym == pseudonym
    case _               => false
  }
  private def refresh(): Unit =
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit

  Given("a network the brand knows") { () => assert(kit.network.id == "programme") }

  // ── the click ──────────────────────────────────────────────────────────────────────────────────

  Given("a link whose tag is valid and issued by the network") { () => mint() }
  When("a visitor lands on the link") { () => land() }
  Then("the brand records one click for that link in this browser session") { () =>
    assertEquals(last.status, 200, last.text)
    land()
    val c = kit.components.forKeyValueEntity(EntityId(clickId)).call(Click.get).invoke().click.getOrElse(fail("no click"))
    assertEquals(c.link, Some(link))
    kit.eventually("the click fact")(facts("click").headOption)
    Thread.sleep(1000)
    assertEquals(facts("click").size, 1)
  }
  Then("the click holds the device type and a digest of the visitor's address and never the address itself") { () =>
    val c = kit.components.forKeyValueEntity(EntityId(clickId)).call(Click.get).invoke().click.get
    assertEquals(c.deviceType, "mobile")
    assert(c.addressDigest.exists(d => d.nonEmpty && !d.contains(address)))
    assert(!c.toString.contains(address))
  }
  Then("a {string} fact is emitted naming the network, affiliate, link, campaign, sub-ids and click id") { (kind: String) =>
    kit.eventually(s"a $kind fact")(facts(kind).headOption) match
      case c: ClickFact =>
        assertEquals((c.network, c.affiliate, c.link, c.campaign, c.subIds, c.clickId),
          ("programme", "aff-1", link, Some("autumn"), Vector("s1", "s2"), Some(clickId)))
      case other => fail(s"not a $kind: $other")
  }

  // ── the binding ────────────────────────────────────────────────────────────────────────────────

  Given("a visitor who landed with a valid tag") { () => mint(); land() }
  When("they register before the attribution window has passed") { () =>
    player = kit.bindPlayer(Some(tag), clickId = Some(clickId))
  }
  Then("the player is bound to that network, affiliate, link and sub-ids with the click id") { () =>
    val b = kit.eventually("bound")(Some(record).flatMap(_.binding))
    assertEquals((b.network, b.affiliate, b.link, b.subIds, b.clickId), ("programme", "aff-1", link, Vector("s1", "s2"), Some(clickId)))
  }
  Then("a {string} fact is emitted naming all of them and the player's pseudonym for that network") { (kind: String) =>
    kit.eventually(s"a $kind fact")(facts(kind).headOption) match
      case r: Registration =>
        assertEquals((r.network, r.affiliate, r.link, r.subIds, r.clickId, r.pseudonym),
          ("programme", "aff-1", link, Vector("s1", "s2"), Some(clickId), pseudonym))
      case other => fail(s"not a $kind: $other")
  }

  Given("a visitor whose tag is older than the brand's attribution window") { () => mint(kit.now.minus(JDuration.ofDays(31))) }
  When("they register") { () => player = kit.bindPlayer(Some(tag)) }
  Then("the player is recorded as organic") { () => assertEquals(record.binding, None) }
  Then("the stale tag is kept on the attribution record for reporting") { () =>
    assertEquals(record.refused.map(r => (r.tag, r.reason)), Some((tag, Reasons.Stale)))
  }
  Then("no {string} fact is emitted for the affiliate") { (kind: String) =>
    Thread.sleep(1000)
    assertEquals(facts(kind), Vector.empty)
  }

  Given("a player already bound to an affiliate") { () =>
    mint()
    player = kit.bindPlayer(Some(tag))
    bound = Some(kit.eventually("bound")(Some(record).flatMap(_.binding)))
  }
  When("they arrive with a different tag, sign in and play") { () =>
    kit.bindPlayer(Some(kit.network.mint("aff-2", id("other-lnk"))), player = player): Unit
  }
  Then("the binding does not change") { () =>
    kit.eventually("the touch")(Some(kit.record(player)).filter(_.touches.nonEmpty))
    assertEquals(kit.record(player).binding, bound)
  }
  Then("the later touch is recorded on the player's attribution record") { () =>
    val row = kit.eventually("the touch in the view")(
      kit.views.forView(Records).ask(Records.byPlayer, "player" -> player).headOption.filter(_.touches > 0))
    assertEquals(row.touches, 1)
    assertEquals(kit.record(player).touches.map(_.value), Vector(id("other-lnk")))
  }

  // ── tags the brand cannot verify ──────────────────────────────────────────────────────────────

  Given("a visitor who landed with a tag the brand cannot verify because of a bad signature") { () =>
    mint()
    tag = tag.dropRight(4) + (if tag.endsWith("AAAA") then "BBBB" else "AAAA")
    reason = Reasons.BadSignature
  }
  Given("a visitor who landed with a tag the brand cannot verify because of an unknown network") { () =>
    val stranger = FakeNetwork(id("stranger"))
    try tag = stranger.mint("aff-1", id("lnk")) finally stranger.stop()
    reason = Reasons.UnknownNetwork
  }
  Given("a visitor who landed with a tag the brand cannot verify because of a key the network has withdrawn") { () =>
    mint()
    // The key the tag was minted under, whatever keys an earlier suite left the network holding.
    kit.network.withdraw(kit.network.current.kid)
    refresh()
    reason = Reasons.WithdrawnKey
  }
  Then("the registration succeeds") { () => assert(record.registeredAt.isDefined) }
  Then("the player is organic") { () => assertEquals(record.binding, None) }
  Then("the refused tag is kept with the reason") { () =>
    assertEquals(record.refused.map(r => (r.tag, r.reason)), Some((tag, reason)))
  }

  Given("a visitor who landed with a tag for a link the network has revoked") { () =>
    mint()
    kit.network.revokedLinks = Set(link)
    refresh()
  }
  Then("the attribution record says the link was revoked") { () =>
    assertEquals(record.refused.map(_.reason), Some(Reasons.RevokedLink))
  }
