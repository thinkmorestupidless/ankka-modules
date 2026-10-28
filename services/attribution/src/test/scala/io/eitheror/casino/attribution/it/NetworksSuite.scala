package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.{Json, Reasons}
import io.eitheror.casino.attribution.clicks.Click
import io.eitheror.casino.attribution.money.{DayCloser, Days}
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.settings.SettingsEntity
import io.eitheror.casino.contract.facts.{Deposit, PaymentCaptured, PaymentsPayments, PlayerAttributionFact}
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/**
 * Trust (US5; FR-023, FR-025, SC-007, SC-008) and revocation (US6; FR-024): a network's keys come only from
 * its key document, cached for the time it names; a withdrawn key is refused once the cache refreshes; the
 * legacy tags bind during the transition and are refused after it; a removed network's tags are refused
 * and its facts stand; a revoked link or affiliate binds no one new and unbinds no one. A kit of its own:
 * it moves the clock a year, and runs a second brand for the 90-day rule.
 */
class NetworksSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k
  private var second: Option[Kit] = None

  override def afterAll(): Unit =
    kit.stop()
    second.foreach(_.stop())

  override def beforeEach(context: BeforeEach): Unit =
    kit.rewire()
    kit.reset()

  private def backoffice = s"/v1/casino/backoffice/${kit.brand}/attribution"

  /** A click with the tag; answers why it was refused, none when it verified. */
  private def click(tag: String): Option[String] =
    val id = kit.fresh("c")
    val r  = kit.request("POST", "/v1/track/click", Some(Json.render(Json.obj("btag" -> tag, "clickId" -> id))), kit.fromInternet)
    assertEquals(r.status, 200, r.text)
    kit.components.forKeyValueEntity(EntityId(id)).call(Click.get).invoke().click.get.refused

  /** A registration carrying the tag, once recorded. */
  private def register(tag: String): (String, Record.State) =
    val player = kit.bindPlayer(Some(tag))
    player -> kit.eventually("registered")(Some(kit.record(player)).filter(_.registeredAt.isDefined))

  /** Lets every cached document expire and fetches them again, as the minute's timer would. */
  private def expire(): Unit =
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit

  private def settings = kit.components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke()
  private def today = Days.dayOf(kit.now, kit.zone)

  private def status = kit.request("GET", s"$backoffice/status", headers = kit.asOperator())

  private def reachable(network: String): Boolean =
    import scala.jdk.CollectionConverters.*
    status.json.get("networks").elements.asScala.find(_.get("id").asText == network).get.get("reachable").asBoolean

  // ── trust ───────────────────────────────────────────────────────────────────────────────────────

  test("a network added by the operator is trusted on its key document, fetched once at the first click (SC-007)") {
    val fake = FakeNetwork("trusted")
    try
      val body = Json.render(Json.obj("id" -> "trusted", "keyDocument" -> fake.keyDocumentUrl, "revocationList" -> fake.revocationsUrl))
      val made = kit.request("POST", s"$backoffice/networks", Some(body), kit.asOperator())
      assertEquals(made.status, 201, made.text)
      assertEquals(kit.request("POST", s"$backoffice/networks/trusted/pseudonym-key", headers = kit.asOperator()).status, 201)
      assertEquals(kit.request("POST", s"$backoffice/networks/trusted/pseudonym-key", headers = kit.asOperator()).status, 409)
      assertEquals(kit.request("POST", s"$backoffice/networks", Some(body), kit.asOperator()).status, 409)
      kit.eventually("listed")(Option.when(kit.request("GET", s"$backoffice/networks", headers = kit.asOperator()).text.contains("trusted"))(()))
      def keyFetches = fake.received.count(_.path == "/keys")
      assertEquals(keyFetches, 0)

      assertEquals(click(fake.mint("aff-1", kit.fresh("lnk"))), None)
      assertEquals(keyFetches, 1)
      assertEquals(click(fake.mint("aff-1", kit.fresh("lnk"))), None)
      assertEquals(keyFetches, 1, "the second click uses the cache")
    finally fake.stop()
  }

  test("a rotated key: the old one verifies until the cache expires, then is withdrawn_key; the new one verifies") {
    val old = kit.network.mint("aff-1", kit.fresh("lnk"))
    kit.network.rotate(): Unit
    val fresh = kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now)
    assertEquals(click(old), None)
    expire()
    assertEquals(click(old), Some(Reasons.WithdrawnKey))
    assertEquals(click(fresh), None)
    assertEquals(register(fresh)._2.binding.map(_.affiliate), Some("aff-1"))
  }

  test("an unreachable key document: tags verify until it expires, then key_unreachable and the status says so") {
    val tag = kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now)
    assertEquals(click(tag), None)
    kit.network.fail("keys")
    try
      expire()
      assertEquals(click(tag), Some(Reasons.KeyUnreachable))
      assertEquals(register(tag)._2.refused.map(_.reason), Some(Reasons.KeyUnreachable))
      assertEquals(reachable("programme"), false)
    finally kit.network.recover("keys")
    kit.refreshDocuments(): Unit
    assertEquals(click(tag), None)
    assertEquals(reachable("programme"), true)
  }

  // ── revocation ──────────────────────────────────────────────────────────────────────────────────

  test("a revoked link binds no one new; a player bound to it stays bound and keeps being reported") {
    val link = kit.fresh("lnk")
    val (player, before) = register(kit.network.mint("aff-1", link, iat = kit.now))
    val binding = before.binding.get
    kit.network.revokedLinks = Set(link)
    expire()

    val (_, after) = register(kit.network.mint("aff-1", link, iat = kit.now))
    assertEquals((after.binding, after.refused.map(_.reason)), (None, Some(Reasons.RevokedLink)))
    assertEquals(click(kit.network.mint("aff-1", link)), Some(Reasons.RevokedLink))

    val pay = kit.fresh("pay")
    kit.publish(PaymentsPayments, PaymentCaptured(pay, player, "psp", MinorUnits.unsafe(1000), Currency.unsafe("EUR"), kit.now))
    kit.eventually("the deposit")(kit.attributionFacts.collectFirst { case d: Deposit if d.reference == pay => d }): Unit
    val record = kit.request("GET", s"$backoffice/records/$player", headers = kit.asOperator())
    assertEquals(record.status, 200, record.text)
    assertEquals(record.json.get("binding").get("link").asText, binding.link)
  }

  test("a revoked affiliate has every tag naming them refused for new bindings") {
    kit.network.revokedAffiliates = Set("aff-bad")
    expire()
    assertEquals(click(kit.network.mint("aff-bad", kit.fresh("lnk"))), Some(Reasons.RevokedAffiliate))
    assertEquals(register(kit.network.mint("aff-bad", kit.fresh("lnk"), iat = kit.now))._2.refused.map(_.reason),
      Some(Reasons.RevokedAffiliate))
    assertEquals(click(kit.network.mint("aff-good", kit.fresh("lnk"))), None)
  }

  test("nothing else a network sends reaches the brand: every write under no token or a machine's is refused (FR-024)") {
    val player = register(kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now))._1
    val held   = kit.request("GET", s"$backoffice/records/$player", headers = kit.asOperator()).text
    val brands = s"/v1/casino/brands/${kit.brand}/attribution"
    val writes = Vector(
      "PUT" -> s"$backoffice/settings", "POST" -> s"$backoffice/networks", "PUT" -> s"$backoffice/networks/programme",
      "POST" -> s"$backoffice/networks/programme/pseudonym-key", "POST" -> s"$backoffice/signing-keys",
      "PUT" -> s"$backoffice/records/$player", "POST" -> s"$backoffice/records/$player",
      "POST" -> s"/v1/casino/backoffice/${kit.brand}/attribution-webhooks", "POST" -> s"$brands/players/$player/erasure",
      "POST" -> s"$brands/networks/programme/feed", "PUT" -> s"$brands/keys", "POST" -> s"$brands/keys",
      "POST" -> s"$brands/players/$player/binding", "PUT" -> s"$brands/networks/programme/facts")
    val body = Some("""{"affiliate":"aff-evil","link":"lnk-evil","amount":1}""")
    for (method, path) <- writes; (who, headers) <- Vector("no token" -> kit.fromInternet, "a machine" -> kit.asMachine("acme", "reader")) do
      val r = kit.request(method, path, body, headers)
      assert(Set(401, 403, 404, 405).contains(r.status), s"$method $path under $who answered ${r.status}: ${r.text}")
    assertEquals(kit.request("GET", s"$backoffice/records/$player", headers = kit.asOperator()).text, held)
  }

  // ── the legacy tags and the transition ──────────────────────────────────────────────────────────

  test("a legacy tag the programme's document lists binds to its affiliate and link with no sub-ids; one it does not, legacy_unknown") {
    val uuid = java.util.UUID.randomUUID().toString
    val link = kit.fresh("lnk")
    kit.network.legacy = Map(uuid -> ("aff-legacy" -> link))
    expire()
    val b = register(uuid)._2.binding.getOrElse(fail("not bound"))
    assertEquals((b.network, b.affiliate, b.link, b.subIds), ("programme", "aff-legacy", link, Vector.empty))
    assertEquals(kit.eventually("the legacy day")(settings.lastLegacyDay), today)
    assertEquals(register(java.util.UUID.randomUUID().toString)._2.refused.map(_.reason), Some(Reasons.LegacyUnknown))
  }

  test("SC-008: the status counts the legacy tags per day and the key document their last 30 days") {
    def counted = kit.request("GET", s"/v1/casino/brands/${kit.brand}/attribution/keys", headers = kit.fromInternet).json
      .get("transition").get("legacyTagsLast30Days").asLong
    val before = counted
    val uuids  = Vector.fill(3)(java.util.UUID.randomUUID().toString)
    kit.network.legacy = uuids.map(u => u -> ("aff-legacy" -> kit.fresh("lnk"))).toMap
    expire()
    uuids.foreach(u => register(u)._2.binding.getOrElse(fail(s"$u not bound")): Unit)
    register(kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now))._2.binding.getOrElse(fail("not bound")): Unit
    assertEquals(kit.eventually("three more")(Option.when(counted == before + 3)(counted)), before + 3)
    val perDay = status.json.get("legacyPerDay").get(today)
    assert(perDay != null && perDay.asLong >= 3, status.text)
  }

  test("a removed network's tags are refused unknown_network; the facts already emitted stand and it reads removed") {
    val fake = FakeNetwork("parting")
    try
      kit.addNetwork(fake, legacy = false, codes = false): Unit
      kit.makePseudonymKey("parting")
      val (player, r) = register(fake.mint("aff-p", kit.fresh("lnk"), iat = kit.now))
      assert(r.bound)
      def theirs = kit.attributionFacts.collect { case f: PlayerAttributionFact if f.network == "parting" => f }
      val emitted = kit.eventually("the registration")(Option.when(theirs.nonEmpty)(theirs))

      assertEquals(kit.request("DELETE", s"$backoffice/networks/parting", headers = kit.asOperator()).status, 200)
      assertEquals(click(fake.mint("aff-p", kit.fresh("lnk"))), Some(Reasons.UnknownNetwork))
      assertEquals(register(fake.mint("aff-p", kit.fresh("lnk"), iat = kit.now))._2.refused.map(_.reason), Some(Reasons.UnknownNetwork))
      val read = kit.request("GET", s"$backoffice/networks/parting", headers = kit.asOperator())
      assert(read.json.hasNonNull("removed"), read.text)
      Thread.sleep(1000)
      assertEquals(theirs, emitted)
      assertEquals(kit.record(player).binding.map(_.network), Some("parting"))
    finally fake.stop()
  }

  test("a brand with no legacy tag for 90 days ends the transition 90_days_without_legacy") {
    val other = Kit(brand = "silvervibe")
    second = Some(other)
    other.rewire()
    other.moveClock(other.now.plus(JDuration.ofDays(91)))
    DayCloser.closing(other.components, other.clock).run(): Unit
    val s = other.components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke()
    assertEquals(s.transitionEnd.map(_.endedBy), Some(Days.NinetyDaysWithout))
    val doc = other.request("GET", s"/v1/casino/brands/${other.brand}/attribution/keys", headers = other.fromInternet).json
    assertEquals(doc.get("transition").get("endedBy").asText, Days.NinetyDaysWithout)
    kit.rewire()
  }

  test("after 12 months the transition ends 12_months, published, and a legacy tag is refused legacy") {
    val uuid = java.util.UUID.randomUUID().toString
    kit.network.legacy = Map(uuid -> ("aff-legacy" -> kit.fresh("lnk")))
    // A legacy tag presented every month keeps the 90-day rule from ending it first.
    val first = Instant.parse(settings.firstDay.get + "T12:00:00Z")
    for month <- 1 to 12 do
      kit.moveClock(first.plus(JDuration.ofDays(30L * month)).plusSeconds(month))
      kit.refreshDocuments(): Unit
      DayCloser.closing(kit.components, kit.clock).run(): Unit
      if settings.transitionEnd.isEmpty then
        register(uuid): Unit
        kit.eventually("seen")(Option.when(settings.lastLegacyDay.contains(today))(())): Unit
    kit.moveClock(kit.now.plus(JDuration.ofDays(30)))
    kit.refreshDocuments(): Unit
    DayCloser.closing(kit.components, kit.clock).run(): Unit
    assertEquals(settings.transitionEnd.map(_.endedBy), Some(Days.TwelveMonths))
    val doc = kit.request("GET", s"/v1/casino/brands/${kit.brand}/attribution/keys", headers = kit.fromInternet).json
    assertEquals((doc.get("transition").get("endedBy").asText, doc.get("transition").has("endsAt")), (Days.TwelveMonths, false))
    val (_, r) = register(uuid)
    assertEquals((r.binding, r.refused.map(_.reason)), (None, Some(Reasons.Legacy)))
  }

  test("the minute's refresh fetches a key document before it expires, so a registration never finds it lapsed") {
    kit.refreshDocuments(): Unit
    def keyFetches = kit.network.received.count(_.path == "/keys")
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds - 10))
    val before = keyFetches
    kit.refreshDocuments(): Unit
    // The service's own timer may fetch too as the clock moves; before the fix neither did, the copy being current.
    assert(keyFetches > before, "fetched again while still current")
    // Past the first copy's expiry, before the next run: the second copy holds.
    kit.moveClock(kit.now.plusSeconds(30))
    val (player, r) = register(kit.network.mint("aff-1", kit.fresh("lnk"), iat = kit.now))
    assertEquals(r.refused.map(_.reason), None)
    kit.eventually("bound")(kit.record(player).binding): Unit
  }
