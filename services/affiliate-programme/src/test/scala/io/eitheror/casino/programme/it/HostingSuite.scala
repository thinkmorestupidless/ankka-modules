package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.intake.{Intake, IntakeCalls}
import io.eitheror.casino.programme.players.PlayerAttribution

import java.time.YearMonth
import scala.jdk.CollectionConverters.*

/** Another company's brand on the programme (US10, contracts/inbound.md, routes.md `/v1/programme`). */
class HostingSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def brandState(slug: String) = kit.components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()

  test("an operator adds a hosted brand: its settings, page, default plan, first manager and a webhook secret shown once") {
    val email = s"${kit.fresh("first")}@hosted.example"
    val h = Hosted.add(kit, managerEmail = Some(email))
    val b = brandState(h.slug)
    assertEquals((b.origin, b.reportingZone, b.payoutCycleDay, b.page.terms), ("hosted", "Europe/Malta", Some(5), Some("Hosted terms")))
    assertEquals(b.defaultPlan, Some(Plan.idOf(h.slug, "Standard")))
    assertEquals(h.reply.json.get("webhookUrl").asText, s"${kit.settings.publicUrl}/v1/programme/inbound/${h.slug}")
    assertEquals(h.secret.length, 64)
    val manager = kit.realm.byEmail(email).getOrElse(fail("no first manager"))
    assertEquals(manager.roles, Set("affiliate_manager", s"brand_${h.slug}"))
    val listed = kit.eventually("listed")(Option(kit.request("GET", "/v1/programme/brands", headers = kit.asPlatformAdmin()))
      .filter(_.json.elements().asScala.exists(_.get("brandId").asText == h.slug)))
    assert(!listed.text.contains(h.secret), "the secret is shown once")
    val again = kit.request("POST", "/v1/programme/brands", Some(s"""{"brandId":"${h.slug}"}"""), kit.asPlatformAdmin())
    assertEquals((again.status, again.json.get("error").asText), (409, "brand_exists"))
    assertEquals(kit.request("POST", "/v1/programme/brands", Some("""{"brandId":"x"}"""), kit.asManager(Seq(h.slug))).status, 403)
  }

  test("a hosted brand's facts by webhook bind the player and write the CPA as a brand here's do") {
    val h = Hosted.add(kit, cpa = 2_500)
    val s = Scene(kit, h.slug, planType = Plan.Cpa, cpa = 2_500)
    val p = kit.fresh("ps")
    val reg = s.facts.registration(p, kit.now)
    val r = Hosted.post(kit, h.slug, h.secret, reg)
    assertEquals((r.status, r.json.get("outcome").asText), (200, "new"), r.text)
    assertEquals(Hosted.inbox(kit, h.slug, reg.factId).channel, s"webhook:${h.slug}")
    assertEquals(Hosted.post(kit, h.slug, h.secret, s.facts.firstDeposit(p, 5_000, kit.now)).status, 200)
    s.rowsWhen("the CPA")(rs => s.sum(rs, FactType.Cpa) == 2_500): Unit
  }

  test("a bad signature, a wrong protocol, a fact id mismatch, an unreadable body and an unknown brand are refused with nothing recorded") {
    val h = Hosted.add(kit)
    val s = Scene(kit, h.slug)
    def refused(r: Reply, status: Int, code: String, factId: String) =
      assertEquals((r.status, r.json.get("error").asText), (status, code), r.text)
      assert(!Hosted.inbox(kit, h.slug, factId).claimed, s"$code recorded $factId")
    val f1 = s.facts.registration(kit.fresh("ps"), kit.now)
    refused(Hosted.post(kit, h.slug, h.secret, f1, signed = Some(Hosted.signature(Hosted.json(f1), "not-the-secret"))), 401, "signature", f1.factId)
    refused(Hosted.post(kit, h.slug, h.secret, f1, signed = Some("sha256=zz")), 401, "signature", f1.factId)
    refused(Hosted.post(kit, h.slug, h.secret, f1, protocol = "2"), 400, "protocol", f1.factId)
    refused(Hosted.post(kit, h.slug, h.secret, f1, factId = Some("another")), 400, "fact_id", f1.factId)
    refused(Hosted.post(kit, h.slug, h.secret, f1, body = Some("""{"kind":"nothing"}""")), 400, "fact", f1.factId)
    refused(Hosted.post(kit, h.slug, h.secret, f1, body = Some(" " * (256 * 1024 + 1))), 413, "too_large", f1.factId)
    val unknown = Hosted.post(kit, "no-such-brand", h.secret, f1)
    assertEquals((unknown.status, unknown.json.get("error").asText), (404, "brand_unknown"))
    // A brand here takes its facts on its topic only.
    assertEquals(Hosted.post(kit, "goldvibe", h.secret, f1).status, 404)
    // Another brand's fact on this brand's webhook: acknowledged, counted, nothing else written.
    val other = Scene(kit).facts.registration(kit.fresh("ps"), kit.now)
    val mismatched = Hosted.post(kit, h.slug, h.secret, other)
    assertEquals((mismatched.status, mismatched.json.get("outcome").asText), (202, "mismatched"))
    assert(!Hosted.inbox(kit, "goldvibe", other.factId).claimed)
    assertEquals(brandState(h.slug).mismatches, 1L)
  }

  test("SC-004: the same fact on the brand's topic channel and by webhook, and by webhook twice, writes once") {
    val h = Hosted.add(kit, cpa = 1_000)
    val s = Scene(kit, h.slug, planType = Plan.Cpa, cpa = 1_000)
    val p = kit.fresh("ps")
    val reg = s.facts.registration(p, kit.now)
    val fd  = s.facts.firstDeposit(p, 3_000, kit.now)
    // The topic channel, as a brand consumer hands a fact to Intake.
    val intake = Intake(IntakeCalls.of(kit.components), () => Wiring.clock.get().instant())
    intake.handle(reg, s"topic:${h.slug}", h.slug): Unit
    intake.handle(fd, s"topic:${h.slug}", h.slug): Unit
    for _ <- 1 to 2 do
      assertEquals(Hosted.post(kit, h.slug, h.secret, fd).json.get("outcome").asText, "duplicate")
    s.rowsWhen("the CPA")(rs => s.sum(rs, FactType.Cpa) == 1_000): Unit
    Thread.sleep(1_000)
    assertEquals(s.rows.count(_.kind == FactType.Cpa), 1)
    assertEquals(Hosted.inbox(kit, h.slug, fd.factId).duplicates, 2)
    assertEquals(brandState(h.slug).duplicates, 2L)
  }

  test("a fact whose handling failed after its claim is handled when the brand sends it again") {
    val h = Hosted.add(kit, cpa = 1_000)
    val s = Scene(kit, h.slug, planType = Plan.Cpa, cpa = 1_000)
    val p = kit.fresh("ps")
    assertEquals(Hosted.post(kit, h.slug, h.secret, s.facts.registration(p, kit.now)).status, 200)
    val fd = s.facts.firstDeposit(p, 3_000, kit.now)
    // The claim made, and nothing after it, as when the service stopped between the two.
    kit.components.forKeyValueEntity(io.eitheror.casino.programme.intake.FactInbox.entityId(h.slug, fd.factId))
      .call(io.eitheror.casino.programme.intake.FactInbox.claim)
      .invoke(io.eitheror.casino.programme.intake.ClaimFact(h.slug, fd.factId, "first-deposit", s"webhook:${h.slug}", Hosted.json(fd), fd.occurredAt, kit.now)): Unit
    assertEquals(Hosted.post(kit, h.slug, h.secret, fd).json.get("outcome").asText, "new")
    s.rowsWhen("the CPA")(rs => s.sum(rs, FactType.Cpa) == 1_000): Unit
    assertEquals(Hosted.post(kit, h.slug, h.secret, fd).json.get("outcome").asText, "duplicate")
  }

  test("SC-010: a hosted brand's manager and a brand here's manager, sharing an affiliate, each see only their own brand") {
    val h    = Hosted.add(kit)
    val gold = Scene(kit, planType = Plan.Cpa, cpa = 1_000)
    val aff  = gold.affiliate
    kit.affiliate(aff, h.slug, kit.plan(h.slug, kit.fresh("p")))
    kit.eventually("both memberships")(Option(kit.views.forView(io.eitheror.casino.programme.accounts.Memberships)
      .ask(io.eitheror.casino.programme.accounts.Memberships.byAffiliate, "affiliate" -> aff)).filter(_.size == 2)): Unit
    gold.send(gold.facts.firstDeposit(gold.player(), 5_000, kit.now)): Unit
    gold.rowsWhen("gold's CPA")(rs => gold.sum(rs, FactType.Cpa) == 1_000): Unit
    val hosted = kit.asManager(Seq(h.slug))
    val read = kit.request("GET", s"/v1/affiliates/${h.slug}/accounts/$aff", headers = hosted)
    assertEquals(read.status, 200, read.text)
    val memberships = read.json.get("memberships").elements().asScala.map(_.get("brandId").asText).toSet
    assertEquals(memberships, Set(h.slug))
    val revenue = kit.request("GET", s"/v1/affiliates/${h.slug}/accounts/$aff/revenue?period=all", headers = hosted)
    assert(!revenue.text.contains("goldvibe"), revenue.text)
    for path <- Seq(s"/goldvibe/accounts/$aff", s"/goldvibe/accounts/$aff/revenue?period=all", s"/goldvibe/accounts/$aff/notes") do
      assertEquals(kit.request("GET", s"/v1/affiliates$path", headers = hosted).status, 403, path)
    val here = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff", headers = kit.asManager())
    assertEquals(here.json.get("memberships").elements().asScala.map(_.get("brandId").asText).toSet, Set("goldvibe"))
  }

  test("FR-042: the usage report gives the brand's month per currency and no fee; a manager is refused") {
    val h = Hosted.add(kit, cpa = 2_500)
    val s = Scene(kit, h.slug, planType = Plan.Cpa, cpa = 2_500)
    val p = kit.fresh("ps")
    Seq(s.facts.registration(p, kit.now), s.facts.firstDeposit(p, 5_000, kit.now), s.facts.deposit(p, 1_000, kit.now, currency = "USD"))
      .foreach(f => assertEquals(Hosted.post(kit, h.slug, h.secret, f).status, 200))
    s.rowsWhen("the CPA")(rs => s.sum(rs, FactType.Cpa) == 2_500): Unit
    val month = YearMonth.from(kit.now.atZone(java.time.ZoneId.of("Europe/Malta"))).toString
    val usage = kit.eventually("the usage")(Option(kit.request("GET", s"/v1/programme/brands/${h.slug}/usage?month=$month", headers = kit.asPlatformAdmin()))
      .filter(r => r.status == 200 && Option(r.json.get("commissionRecorded")).exists(_.has("EUR"))
        && Option(r.json.get("factsReceived")).exists(_.path("EUR").asInt == 1)))
    val u = usage.json
    assertEquals((u.get("factsReceived").get("none").asInt, u.get("factsReceived").get("EUR").asInt, u.get("factsReceived").get("USD").asInt), (1, 1, 1))
    assertEquals(u.get("activeMemberships").get("EUR").asInt, 1)
    assertEquals(u.get("commissionRecorded").get("EUR").asLong, 2_500L)
    assert(!u.has("fee") && !usage.text.toLowerCase.contains("fee"), usage.text)
    val earlier = kit.request("GET", s"/v1/programme/brands/${h.slug}/usage?month=2020-01", headers = kit.asPlatformAdmin())
    assertEquals((earlier.status, earlier.json.path("activeMemberships").path("EUR").asInt(0)), (200, 0))
    assertEquals(kit.request("GET", s"/v1/programme/brands/${h.slug}/usage?month=$month", headers = kit.asManager(Seq(h.slug))).status, 403)
    assertEquals(kit.request("GET", s"/v1/programme/brands/${h.slug}/usage?month=soon", headers = kit.asPlatformAdmin()).status, 400)
  }

  test("PATCH names a brand, sets its key document and retires it; a retired brand's webhook is unknown") {
    val h = Hosted.add(kit)
    val r = kit.request("PATCH", s"/v1/programme/brands/${h.slug}", Some("""{"name":"Renamed","keyDocumentUrl":"https://h.example/keys"}"""),
      kit.asPlatformAdmin())
    assertEquals((r.status, r.json.get("name").asText, r.json.get("keyDocumentUrl").asText), (200, "Renamed", "https://h.example/keys"), r.text)
    assertEquals(kit.request("PATCH", s"/v1/programme/brands/${h.slug}", Some("""{"retired":true}"""), kit.asPlatformAdmin()).status, 200)
    val f = Scene(kit, h.slug).facts.registration(kit.fresh("ps"), kit.now)
    assertEquals(Hosted.post(kit, h.slug, h.secret, f).status, 404)
    assertEquals(kit.request("PATCH", s"/v1/programme/brands/${h.slug}", Some("""{"retired":false}"""), kit.asPlatformAdmin()).status, 409)
  }
