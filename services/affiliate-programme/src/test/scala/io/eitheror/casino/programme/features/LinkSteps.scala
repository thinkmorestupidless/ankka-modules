package io.eitheror.casino.programme.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.attribution.TagOutcome
import io.eitheror.casino.programme.accounts.AffiliateState
import io.eitheror.casino.programme.brands.{AddBrand, BrandProgramme, Plan, UpdateBrand}
import io.eitheror.casino.programme.links.{BonusCode, CreateLink, Link}
import io.eitheror.casino.programme.players.{ClickRecord, PlayerFacts}
import io.eitheror.casino.contract.facts.AttributionFact
import io.eitheror.casino.programme.intake.FactInbox
import io.eitheror.casino.programme.it.{BrandKeys, BrandSide, Facts, Reply}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/programme/links.feature (US5). Brand "A" is goldvibe; "spinvibe" is the original's, whose tags were imported. */
class LinkSteps extends ProgrammeFeatures("links.feature"):

  private def slug(name: String) = if name == "A" then "goldvibe" else name

  private val brandSide = BrandSide(kit)
  private var aff: String          = null
  private var link: String         = null
  private var tag: String          = null
  private var held: Map[String, String] = Map.empty
  private var revoked: (Set[String], Set[String]) = (Set.empty, Set.empty)
  private var outcome: TagOutcome  = null
  private var factId: String       = null
  private var uuid: String         = null
  private var replies: Seq[Reply]  = Nil
  private var keyDoc: BrandKeys    = null
  private var links: Vector[String] = Vector.empty
  private var period: String       = null
  private var zoneBefore: Option[String] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    aff = null; link = null; tag = null; held = Map.empty; revoked = (Set.empty, Set.empty); outcome = null; factId = null; uuid = null
    replies = Nil; keyDoc = null; links = Vector.empty; period = null; zoneBefore = None

  override def afterEach(context: AfterEach): Unit =
    try
      if keyDoc != null then keyDoc.stop()
      zoneBefore.foreach(z => kit.components.forKeyValueEntity(EntityId("goldvibe")).call(BrandProgramme.update)
        .invoke(UpdateBrand(reportingZone = Some(z))): Unit)
    finally super.afterEach(context)

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private def newAffiliate(name: String = "aff"): String =
    kit.affiliate(id(name), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 5_000))

  private def createLink(who: String, body: String = """{"brandId":"goldvibe","campaign":"summer","subIds":["s1","s2"]}"""): JsonNode =
    val r = kit.request("POST", s"/v1/my/$who/tracking-links", Some(body), kit.asAffiliate(who))
    assertEquals(r.status, 201, r.text)
    link = r.json.get("btag").asText
    tag = r.json.get("tag").asText
    r.json

  /** The link as M11 lists it, once the view holds it. */
  private def readLink(): JsonNode =
    kit.eventually(s"$link listed") {
      val r = kit.request("GET", s"/v1/my/$aff/tracking-links?brand=goldvibe", headers = kit.asAffiliate(aff))
      assertEquals(r.status, 200, r.text)
      r.json.elements().asScala.find(_.get("btag").asText == link)
    }

  /** The feature's code text, made this scenario's own; the case the feature wrote is kept. */
  private def code(text: String): String = s"$text-${ids.getOrElseUpdate("code", kit.fresh("c").takeRight(8))}"

  private def createCode(who: String, text: String): Reply =
    if link == null || !ids.contains(s"link:$who") then
      createLink(who): Unit
      ids(s"link:$who") = link
    kit.request("POST", s"/v1/my/$who/bonus-codes", Some(s"""{"brandId":"goldvibe","code":"${code(text)}","btag":"${ids(s"link:$who")}"}"""),
      kit.asAffiliate(who))

  private def click(n: Int = 1): Unit =
    (1 to n).foreach { _ =>
      val f = Facts("goldvibe", aff, link, clickId = Some(kit.fresh("clk"))).click(kit.now)
      factId = f.factId
      kit.publish(f)
    }

  // ── a link and its tag ──────────────────────────────────────────────────────────────────────────

  Given("an affiliate with an active membership at brand {string}") { (brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    aff = newAffiliate()
  }
  When("they create a link for brand {string} with a campaign and sub-ids") { (brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    createLink(aff): Unit
  }
  Then("the link exists with a tag signed by the programme naming the programme, the affiliate, the link and the sub-ids") { () =>
    assertEquals(kit.components.forKeyValueEntity(EntityId(link)).call(Link.get).invoke().tag, Some(tag))
    brandSide.verify(tag, brandSide.keys) match
      case TagOutcome.Valid(named, _, _) =>
        assertEquals((named.network, named.affiliate, named.link, named.subIds), ("programme", aff, link, Vector("s1", "s2")))
      case other => fail(s"refused: $other")
  }
  Then("the brand's landing address carries the tag") { () =>
    assert(readLink().get("landingUrl").asText.contains(s"btag=$tag"), readLink().toString)
  }

  // ── clicks ──────────────────────────────────────────────────────────────────────────────────────

  Given("a brand that recorded a click on the programme's link") { () =>
    aff = newAffiliate()
    createLink(aff): Unit
  }
  When("its {string} fact arrives") { (kind: String) =>
    assertEquals(kind, "click")
    click()
  }
  Then("the programme records the click against the link with the click id, device type and country the fact carries") { () =>
    val c = kit.eventually("the click")(Option(kit.components.forKeyValueEntity(ClickRecord.entityId("goldvibe", factId)).call(ClickRecord.get)
      .invoke()).filter(_.id.nonEmpty))
    assertEquals((c.link, c.affiliate, c.deviceType, c.country), (link, aff, Some("desktop"), Some("ZA")))
    assert(c.clickId.isDefined)
  }
  Then("the link's click count rises by one") { () =>
    kit.eventually("one click")(Option(readLink().get("clicks").asLong).filter(_ == 1L)): Unit
  }

  // ── a brand verifying ───────────────────────────────────────────────────────────────────────────

  Given("a brand holding the programme's key document") { () => held = brandSide.keys }
  When("a player registers with a tag the programme signed") { () =>
    if aff == null then
      aff = newAffiliate()
      createLink(aff): Unit
    outcome = brandSide.verify(tag, if held.nonEmpty then held else brandSide.keys, revoked._1, revoked._2)
  }
  Then("the brand verifies the tag without calling the programme") { () =>
    assert(outcome.isInstanceOf[TagOutcome.Valid], outcome.toString)
  }

  Given("a brand holding the programme's revocation list, which names a link") { () =>
    aff = newAffiliate()
    createLink(aff): Unit
    kit.components.forKeyValueEntity(EntityId(link)).call(Link.revoke).invoke(kit.now): Unit
    revoked = kit.eventually("the link listed")(Option(brandSide.revocations).filter(_._1(link)))
    held = brandSide.keys
  }
  When("a player registers with a tag for that link") { () => outcome = brandSide.verify(tag, held, revoked._1, revoked._2) }
  Then("the brand binds no one") { () => assertEquals(outcome, TagOutcome.Refused(BrandSide.RevokedLink)) }
  Then("the programme receives no fact for the affiliate") { () =>
    assertEquals(kit.views.forView(PlayerFacts).ask(PlayerFacts.ofAffiliate, "affiliate" -> aff, "brand" -> "goldvibe", "from" -> "0000-00",
      "to" -> "9999-99"), Vector.empty)
  }

  // ── bonus codes ─────────────────────────────────────────────────────────────────────────────────

  Given("a bonus code {string} at brand {string}") { (text: String, brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    assertEquals(createCode(newAffiliate("holder"), text).status, 201)
  }
  When("an affiliate creates a bonus code {string} at brand {string}") { (text: String, brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    last = createCode(Option(aff).getOrElse(newAffiliate("creator")), text)
  }
  Then("it is refused as a duplicate") { () =>
    assertEquals((last.status, last.json.get("error").asText), (409, "code_already_exists"), last.text)
  }

  Given("no bonus code {string} at brand {string}") { (text: String, brand: String) =>
    assertEquals(kit.components.forKeyValueEntity(BonusCode.entityId(slug(brand), code(text))).call(BonusCode.get).invoke().live, false)
  }
  When("two affiliates create a bonus code {string} at brand {string} at the same time") { (text: String, brand: String) =>
    val (a, b) = (newAffiliate("one"), newAffiliate("two"))
    Seq(a, b).foreach { who => createLink(who): Unit; ids(s"link:$who") = link }
    val pool = Executors.newFixedThreadPool(2)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try replies = Await.result(Future.sequence(Seq(Future(createCode(a, text)), Future(createCode(b, text)))), 60.seconds)
    finally pool.shutdown()
  }
  Then("one bonus code {string} exists at brand {string}") { (text: String, brand: String) =>
    assertEquals(replies.map(_.status).sorted, Seq(201, 409), replies.map(_.text).mkString("\n"))
    val winner = replies.find(_.status == 201).get.json.get("affiliateId").asText
    val c      = kit.components.forKeyValueEntity(BonusCode.entityId(slug(brand), code(text))).call(BonusCode.get).invoke()
    assertEquals((c.live, c.affiliate), (true, winner))
  }

  Given("a revoked bonus code {string} at brand {string}") { (text: String, brand: String) =>
    val holder = newAffiliate("holder")
    assertEquals(createCode(holder, text).status, 201)
    assertEquals(kit.request("DELETE", s"/v1/my/$holder/bonus-codes/${code(text)}?brand=${slug(brand)}", headers = kit.asAffiliate(holder)).status, 200)
  }
  Then("the bonus code is created") { () => assertEquals(last.status, 201, last.text) }

  // ── the legacy tag document ─────────────────────────────────────────────────────────────────────

  Given("a legacy tag imported from brand {string}") { (brand: String) =>
    aff = newAffiliate()
    uuid = UUID.randomUUID().toString
    link = Link.newId()
    kit.components.forKeyValueEntity(EntityId(link)).call(Link.createLink)
      .invoke(CreateLink(link, aff, brand, None, None, Map.empty, Vector.empty, None, Some(uuid), "import", kit.now)): Unit
  }
  When("the programme publishes its legacy tag document during the transition period") { () =>
    last = kit.eventually("the tag listed") {
      val r = kit.request("GET", "/v1/programme/documents/legacy-tags", headers = kit.fromInternet)
      Option(r).filter(r => r.status == 200 && r.json.get("tags").has(uuid))
    }
  }
  Then("the legacy tag document maps the legacy tag to the affiliate and the link it was imported against") { () =>
    val mapped = last.json.get("tags").get(uuid)
    assertEquals((mapped.get("affiliate").asText, mapped.get("link").asText), (aff, link))
  }

  Given("the transition period has ended") { () =>
    keyDoc = BrandKeys()
    keyDoc.endedOn = Some("2027-01-15")
    // Every brand whose tags were imported reports its transition ended in its own key document.
    val imported = kit.request("GET", "/v1/programme/documents/legacy-tags", headers = kit.fromInternet)
    val brands   = if imported.status != 200 then Set.empty[String]
      else imported.json.get("tags").elements().asScala.map(_.get("link").asText).map(l =>
        kit.components.forKeyValueEntity(EntityId(l)).call(Link.get).invoke().brand).toSet
    (brands + "spinvibe").foreach { b =>
      val entity = kit.components.forKeyValueEntity(EntityId(b))
      if !entity.call(BrandProgramme.get).invoke().exists then
        entity.call(BrandProgramme.add).invoke(AddBrand(b, BrandProgramme.Elsewhere, None, "UTC", "kit", kit.now, Some(keyDoc.url(b)))): Unit
      else
        entity.call(BrandProgramme.update).invoke(io.eitheror.casino.programme.brands.UpdateBrand(keyDocumentUrl = Some(keyDoc.url(b)))): Unit
    }
  }
  When("the programme publishes its documents") { () =>
    val r = kit.request("POST", "/v1/programme/documents/legacy-tags/withdraw", headers = kit.asPlatformAdmin())
    assertEquals(r.status, 200, r.text)
  }
  Then("the legacy tag document is withdrawn") { () =>
    assertEquals(kit.request("GET", "/v1/programme/documents/legacy-tags", headers = kit.fromInternet).status, 404)
  }

  // ── click counts ────────────────────────────────────────────────────────────────────────────────

  Given("an affiliate's link") { () =>
    aff = newAffiliate()
    createLink(aff): Unit
    click(2)
  }
  When("they read the link") { () => last = null }
  Then("its click count comes from the {string} facts received and never from a counter on the account") { (kind: String) =>
    assertEquals(kind, "click")
    kit.eventually("two clicks")(Option(readLink().get("clicks").asLong).filter(_ == 2L)): Unit
    val account: AffiliateState = kit.components.forEventSourcedEntity(EntityId(aff))
      .call(io.eitheror.casino.programme.accounts.Affiliate.get).invoke()
    assert(!account.productElementNames.exists(_.toLowerCase.contains("click")), account.productElementNames.mkString(","))
  }

  // ── traffic (spec 030 A3) ───────────────────────────────────────────────────────────────────────

  /** Publishes a fact on goldvibe's topic and waits for the inbox's outcome, so the next fact finds the player bound. */
  private def send(fact: AttributionFact): String =
    kit.publish(fact)
    kit.eventually(s"the outcome of ${fact.factId}")(
      kit.components.forKeyValueEntity(FactInbox.entityId("goldvibe", fact.factId)).call(FactInbox.get).invoke().outcome)

  /** A player registered through `on` with a first deposit of `amount`, as a brand emits it: `first-deposit`, then `deposit`. */
  private def depositor(on: String, amount: Long, at: Instant): Unit =
    val facts = Facts("goldvibe", aff, on)
    val p     = kit.fresh("ps")
    send(facts.registration(p, at)): Unit
    send(facts.firstDeposit(p, amount, at.plusSeconds(60))): Unit
    send(facts.deposit(p, amount, at.plusSeconds(60))): Unit

  private def clicksOn(on: String, n: Int, at: Instant): Unit =
    (1 to n).foreach(_ => send(Facts("goldvibe", aff, on, clickId = Some(kit.fresh("clk"))).click(at)): Unit)

  private def traffic(p: String): com.fasterxml.jackson.databind.JsonNode =
    val r = kit.request("GET", s"/v1/affiliates/goldvibe/traffic?period=$p", headers = kit.asManager())
    assertEquals(r.status, 200, r.text)
    r.json

  private def counts(n: com.fasterxml.jackson.databind.JsonNode): (Long, Long, Long, Long) =
    (n.get("clickCount").asLong, n.get("registrationCount").asLong, n.get("ftdCount").asLong, n.get("qftdCount").asLong)

  /** This scenario's affiliate in a traffic answer, once it is there. */
  private def mine(answer: com.fasterxml.jackson.databind.JsonNode): Option[com.fasterxml.jackson.databind.JsonNode] =
    answer.get("affiliates").elements().asScala.find(_.get("affiliateId").asText == aff)

  /** Polls the read until this scenario's affiliate counts `expected`. */
  private def readUntil(expected: (Long, Long, Long, Long)): com.fasterxml.jackson.databind.JsonNode =
    kit.eventually(s"the affiliate's traffic $expected")(Option(traffic(period)).filter(a => mine(a).map(counts).contains(expected)))

  Given("an affiliate with two links at brand {string}, each with clicks, registrations and first deposits in one period") { (brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    aff = newAffiliate()
    links = Vector(createLink(aff).get("btag").asText, createLink(aff).get("btag").asText)
    period = "2026-09"
    val at = Instant.parse("2026-09-10T12:00:00Z")
    clicksOn(links(0), 2, at)
    depositor(links(0), 2_000, at)
    clicksOn(links(1), 1, at)
    send(Facts("goldvibe", aff, links(1)).registration(kit.fresh("ps"), at)): Unit
  }
  When("a manager reads the brand's traffic for that period") { () => assert(period != null) }
  Then("the traffic counts the clicks, registrations, first deposits and qualified first deposits of the affiliate and of each link") { () =>
    val answer = readUntil((3L, 2L, 1L, 1L))
    assertEquals(answer.get("period").asText, period)
    val byLink = mine(answer).get.get("links").elements().asScala.map(l => l.get("btag").asText -> counts(l)).toMap
    assertEquals(byLink, Map(links(0) -> (2L, 1L, 1L, 1L), links(1) -> (1L, 1L, 0L, 0L)))
  }
  Then("the brand's counts include the affiliate's") { () =>
    val answer = traffic(period)
    val (c, r, f, q) = counts(answer)
    assert(c >= 3 && r >= 2 && f >= 1 && q >= 1, answer.toString)
    val sum = answer.get("affiliates").elements().asScala.map(counts).foldLeft((0L, 0L, 0L, 0L))((a, b) => (a._1 + b._1, a._2 + b._2, a._3 + b._3, a._4 + b._4))
    assertEquals(sum, (c, r, f, q))
  }

  Given("brand {string} with the reporting zone {string}") { (brand: String, zone: String) =>
    assertEquals(slug(brand), "goldvibe")
    val entity = kit.components.forKeyValueEntity(EntityId("goldvibe"))
    zoneBefore = Some(entity.call(BrandProgramme.get).invoke().reportingZone)
    entity.call(BrandProgramme.update).invoke(UpdateBrand(reportingZone = Some(zone))): Unit
  }
  Given("a click late on the last day of a month in that zone") { () =>
    aff = newAffiliate()
    links = Vector(createLink(aff).get("btag").asText)
    // 23:30 on 31 October in São Paulo is 02:30 on 1 November in UTC.
    clicksOn(links(0), 1, Instant.parse("2026-11-01T02:30:00Z"))
  }
  When("a manager reads the brand's traffic for that month and for the next") { () => period = "2026-10" }
  Then("the click counts in that month and not in the next") { () =>
    readUntil((1L, 0L, 0L, 0L)): Unit
    assertEquals(mine(traffic("2026-11")), None)
  }

  Given("an affiliate on a plan that pays CPA with a minimum deposit at brand {string}") { (brand: String) =>
    assertEquals(slug(brand), "goldvibe")
    aff = kit.affiliate(id("aff"), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 5_000, minimumDeposit = 2_000))
    links = Vector(createLink(aff).get("btag").asText)
    period = "2026-09"
  }
  Given("one player whose first deposit is below the minimum deposit and one whose first deposit meets it") { () =>
    val at = Instant.parse("2026-09-12T12:00:00Z")
    depositor(links(0), 1_999, at)
    depositor(links(0), 2_000, at)
  }
  Then("the traffic counts two first deposits and one qualified first deposit") { () =>
    readUntil((0L, 2L, 2L, 1L)): Unit
  }
