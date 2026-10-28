package io.eitheror.casino.attribution.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.attribution.{Json, Reasons}
import io.eitheror.casino.attribution.clicks.{Click, ClickRecord}
import io.eitheror.casino.attribution.it.{FakeNetwork, Kit}
import io.eitheror.casino.attribution.money.{DayCloser, Days}
import io.eitheror.casino.attribution.networks.{Documents, NetworkDocuments}
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.settings.{BrandSettings, SettingsEntity}
import io.eitheror.casino.contract.facts.PlayerAttributionFact

import java.time.{Duration as JDuration, Instant}
import java.util.UUID

/**
 * features/attribution/networks.feature (US5, US6): trust from the key document, rotation, the legacy tags'
 * transition, removal, and revocation as the only reverse channel. Kits of its own (build.sbt's `own`): the
 * transition takes a year on the clock, and the 90-day row a second brand.
 */
class NetworksFeatures extends AttributionFeatures("networks.feature"):

  private lazy val main: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k
  private var second: Option[Kit] = None
  private var current: Kit        = null
  override protected def kit: Kit = if current == null then main else current

  override def afterAll(): Unit =
    main.stop()
    second.foreach(_.stop())

  // ── a scenario's state ──────────────────────────────────────────────────────────────────────────

  private var fake: FakeNetwork            = null
  private var tag: String                  = null
  private var link: String                 = null
  private var outcome: Option[String]      = None
  private var clicked: Option[ClickRecord] = None
  private var registered: Record.State     = null
  private var player: String               = null
  private var emitted: Vector[PlayerAttributionFact] = Vector.empty
  private var replies: Vector[(String, Int)] = Vector.empty
  private var held: String                 = null

  override def beforeEach(context: BeforeEach): Unit =
    current = main
    main.rewire()
    super.beforeEach(context)
    if fake != null then fake.stop()
    fake = null; tag = null; link = null; outcome = None; clicked = None; registered = null; player = null
    emitted = Vector.empty; replies = Vector.empty; held = null

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private def backoffice = s"/v1/casino/backoffice/${kit.brand}/attribution"

  private def click(t: String): Option[String] =
    val id = kit.fresh("c")
    val r  = kit.request("POST", "/v1/track/click", Some(Json.render(Json.obj("btag" -> t, "clickId" -> id))), kit.fromInternet)
    assertEquals(r.status, 200, r.text)
    clicked = kit.components.forKeyValueEntity(EntityId(id)).call(Click.get).invoke().click
    clicked.get.refused

  private def register(t: String): Record.State =
    val p = kit.bindPlayer(Some(t))
    player = p
    kit.eventually("registered")(Some(kit.record(p)).filter(_.registeredAt.isDefined))

  private def expire(): Unit =
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit

  private def settings(k: Kit = kit): BrandSettings =
    k.components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke()

  private def today = Days.dayOf(kit.now, kit.zone)

  /** A legacy tag the programme's document lists now. */
  private def listedLegacy(): String =
    val uuid = UUID.randomUUID().toString
    link = kit.fresh("lnk")
    kit.network.legacy = Map(uuid -> ("aff-legacy" -> link))
    expire()
    uuid

  /** Twelve months on the protocol with a legacy tag presented each month, then the close that ends it. */
  private def twelveMonths(): Unit =
    val uuid  = listedLegacy()
    val first = Instant.parse(settings().firstDay.get + "T12:00:00Z")
    for month <- 1 to 12 if settings().transitionEnd.isEmpty do
      kit.moveClock(Vector(kit.now, first.plus(JDuration.ofDays(30L * month))).max.plusSeconds(1))
      kit.refreshDocuments(): Unit
      DayCloser.closing(kit.components, kit.clock).run(): Unit
      if settings().transitionEnd.isEmpty then
        register(uuid): Unit
        kit.eventually("presented")(Option.when(settings().lastLegacyDay.contains(today))(())): Unit
    kit.moveClock(Vector(kit.now, first.plus(JDuration.ofDays(400))).max)
    kit.refreshDocuments(): Unit
    DayCloser.closing(kit.components, kit.clock).run(): Unit

  // ── trust ───────────────────────────────────────────────────────────────────────────────────────

  Given("a network the brand's operator has added with the address of its key document") { () =>
    val id = kit.fresh("net")
    fake = FakeNetwork(id)
    val body = Json.render(Json.obj("id" -> id, "keyDocument" -> fake.keyDocumentUrl, "revocationList" -> fake.revocationsUrl))
    assertEquals(kit.request("POST", s"$backoffice/networks", Some(body), kit.asOperator()).status, 201)
  }
  When("the brand first sees a tag naming a signing key it does not hold") { () =>
    assertEquals(fake.received.count(_.path == "/keys"), 0)
    outcome = click(fake.mint("aff-1", kit.fresh("lnk")))
  }
  Then("the brand fetches the key document") { () => assertEquals(fake.received.count(_.path == "/keys"), 1) }
  Then("the brand caches the signing keys for the time the key document states") { () =>
    val keys = kit.components.forKeyValueEntity(EntityId(fake.id)).call(NetworkDocuments.get).invoke().of(Documents.Kind.Keys).get
    assertEquals(keys.expiresAt, keys.fetchedAt.map(_.plusSeconds(fake.cacheSeconds)))
  }
  Then("the brand verifies the tag") { () => assertEquals(outcome, None) }

  Given("a tag signed with a signing key the network has withdrawn from its key document") { () =>
    tag = kit.network.mint("aff-1", kit.fresh("lnk"))
    kit.network.withdraw(kit.network.current.kid)
  }
  When("a visitor lands with it after the brand's cache has refreshed") { () =>
    expire()
    outcome = click(tag)
  }
  Then("the tag is refused") { () => assert(outcome.isDefined) }
  Then("the reason is kept") { () =>
    assertEquals(clicked.flatMap(_.refused), Some(Reasons.WithdrawnKey))
  }

  // ── the legacy tags ─────────────────────────────────────────────────────────────────────────────

  Given("a legacy tag the programme's legacy tag document lists") { () => tag = listedLegacy() }
  When("a visitor lands with it during the transition period") { () =>
    assert(settings().transitionEnd.isEmpty)
    outcome = click(tag)
    registered = register(tag)
  }
  Then("it is treated as a tag from the programme for that link's affiliate, with no sub-ids") { () =>
    val c = clicked.get
    assertEquals((outcome, c.network, c.affiliate, c.link, c.subIds), (None, Some("programme"), Some("aff-legacy"), Some(link), Vector.empty))
    val b = registered.binding.get
    assertEquals((b.network, b.affiliate, b.link, b.subIds), ("programme", "aff-legacy", link, Vector.empty))
  }

  Given("a brand on the protocol for 12 months and a legacy tag presented every month") { () => twelveMonths() }
  Given("a brand on the protocol for four months and no legacy tag presented for 90 days") { () =>
    val other = second.getOrElse {
      val k = Kit(brand = "silvervibe")
      k.addNetwork(): Unit
      k.makePseudonymKey()
      second = Some(k)
      k
    }
    current = other
    other.rewire()
    other.moveClock(other.now.plus(JDuration.ofDays(120)))
    other.refreshDocuments(): Unit
    DayCloser.closing(other.components, other.clock).run(): Unit
  }
  When("a visitor lands with a legacy tag") { () =>
    tag = listedLegacy()
    registered = register(tag)
  }
  Then("the tag is refused as legacy") { () => assertEquals(registered.refused.map(_.reason), Some(Reasons.Legacy)) }
  private def endedBy(by: String): Unit =
    val end = settings().transitionEnd.getOrElse(fail("the transition has not ended"))
    assertEquals(end.endedBy, by)
    assert(end.day.nonEmpty)
    val doc = kit.request("GET", s"/v1/casino/brands/${kit.brand}/attribution/keys", headers = kit.fromInternet).json
    assertEquals((doc.get("transition").get("endedOn").asText, doc.get("transition").get("endedBy").asText), (end.day, by))
  Then("the brand keeps the day the period ended and that 12 months ended it") { () => endedBy(Days.TwelveMonths) }
  Then("the brand keeps the day the period ended and that 90 days without a legacy tag ended it") { () =>
    endedBy(Days.NinetyDaysWithout)
  }

  Given("the transition period has ended") { () => if settings().transitionEnd.isEmpty then twelveMonths() }
  Then("the player is organic") { () => assertEquals(registered.binding, None) }

  // ── removal ─────────────────────────────────────────────────────────────────────────────────────

  Given("a network the brand's operator has removed from the brand") { () =>
    val id = kit.fresh("net")
    fake = FakeNetwork(id)
    kit.addNetwork(fake, legacy = false, codes = false): Unit
    kit.makePseudonymKey(id)
    assert(register(fake.mint("aff-r", kit.fresh("lnk"), iat = kit.now)).bound)
    emitted = kit.eventually("its registration")(Some(kit.attributionFacts.collect {
      case f: PlayerAttributionFact if f.network == id => f }).filter(_.nonEmpty))
    assertEquals(kit.request("DELETE", s"$backoffice/networks/$id", headers = kit.asOperator()).status, 200)
  }
  When("a visitor lands with its tag") { () => outcome = click(fake.mint("aff-r", kit.fresh("lnk"))) }
  Then("the tag is refused as unknown") { () => assertEquals(outcome, Some(Reasons.UnknownNetwork)) }
  Then("facts already emitted for its affiliates are unchanged") { () =>
    Thread.sleep(1000)
    assertEquals(kit.attributionFacts.collect { case f: PlayerAttributionFact if f.network == fake.id => f }, emitted)
  }

  // ── revocation ──────────────────────────────────────────────────────────────────────────────────

  Given("a network's revocation list names a link") { () =>
    link = kit.fresh("lnk")
    val first = register(kit.network.mint("aff-1", link, iat = kit.now))
    assert(first.bound)
    held = player
    kit.network.revokedLinks = Set(link)
  }
  Given("a network's revocation list names a whole affiliate") { () => kit.network.revokedAffiliates = Set("aff-revoked") }
  When("the brand refreshes the revocation list") { () => expire() }
  Then("a tag for that link binds no new player from then on") { () =>
    val r = register(kit.network.mint("aff-1", link, iat = kit.now))
    assertEquals((r.binding, r.refused.map(_.reason)), (None, Some(Reasons.RevokedLink)))
  }
  Then("players already bound to it stay bound") { () => assertEquals(kit.record(held).binding.map(_.link), Some(link)) }
  Then("every tag naming that affiliate is refused for new bindings") { () =>
    for _ <- 1 to 2 do
      val r = register(kit.network.mint("aff-revoked", kit.fresh("lnk"), iat = kit.now))
      assertEquals((r.binding, r.refused.map(_.reason)), (None, Some(Reasons.RevokedAffiliate)))
  }

  Given("anything else a network sends to the brand") { () =>
    held = register(kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now)).binding.get.link
  }
  When("it arrives") { () =>
    val brands = s"/v1/casino/brands/${kit.brand}/attribution"
    val writes = Vector("POST" -> s"$brands/players/$player/binding", "PUT" -> s"$brands/networks/programme/facts",
      "POST" -> s"$backoffice/networks", "PUT" -> s"$backoffice/networks/programme", "POST" -> s"$brands/players/$player/erasure",
      "PUT" -> s"$backoffice/records/$player", "POST" -> s"$brands/keys")
    val body = Some("""{"affiliate":"aff-evil","link":"lnk-evil","amount":1}""")
    replies = for (m, path) <- writes; headers <- Vector(kit.fromInternet, kit.asMachine("acme", "reader"))
      yield s"$m $path" -> kit.request(m, path, body, headers).status
  }
  Then("it is refused, because the reverse channel carries signing keys and revocations and nothing more") { () =>
    replies.foreach((what, status) => assert(Set(401, 403, 404, 405).contains(status), s"$what answered $status"))
    assertEquals(kit.record(player).binding.map(_.link), Some(held))
  }
