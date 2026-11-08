package io.eitheror.casino.programme.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.it.{Hosted, Reply, Scene}

import java.time.{YearMonth, ZoneId}
import scala.jdk.CollectionConverters.*

/** features/programme/hosting.feature (US10). Brand "H" is a hosted brand of the scenario's own; brand "A" is goldvibe. */
class HostingSteps extends ProgrammeFeatures("hosting.feature"):
  private var hosted: Hosted.Added = null
  private var scene: Scene         = null
  private var player: String       = null
  private var reply: Reply         = null
  private var managerEmail: String = null
  private var factId: String       = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    hosted = null; scene = null; player = null; reply = null; managerEmail = null; factId = null

  private def slug = hosted.slug
  private def brandState = kit.components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()

  private def addHosted(): Unit =
    managerEmail = s"${kit.fresh("first")}@hosted.example"
    hosted = Hosted.add(kit, cpa = 2_500, managerEmail = Some(managerEmail))
    scene = Scene(kit, slug, planType = Plan.Cpa, cpa = 2_500)

  // ── adding one ──────────────────────────────────────────────────────────────────────────────────

  Given("a contract signed with another company for brand {string}") { (_: String) => () }
  When("an operator of the installation adds hosted brand {string} with its configuration, programme page, default plan and first manager") {
    (_: String) => addHosted()
  }
  Then("brand {string} is in the programme") { (_: String) =>
    assertEquals((brandState.origin, brandState.live, brandState.page.terms.isDefined, brandState.defaultPlan.isDefined), ("hosted", true, true, true))
    kit.eventually("listed")(Option(kit.request("GET", "/v1/programme/brands", headers = kit.asPlatformAdmin()))
      .filter(_.json.elements().asScala.exists(_.get("brandId").asText == slug))): Unit
  }
  Then("its first manager is provisioned with the manager role and the role of brand {string}") { (_: String) =>
    assertEquals(kit.realm.byEmail(managerEmail).map(_.roles), Some(Set("affiliate_manager", s"brand_$slug")))
  }
  Then("brand {string} is given the address and shared secret of its network webhook") { (_: String) =>
    assertEquals(hosted.reply.json.get("webhookUrl").asText, s"${kit.settings.publicUrl}/v1/programme/inbound/$slug")
    assert(hosted.secret.length >= 32)
  }

  // ── facts by webhook ────────────────────────────────────────────────────────────────────────────

  Given("hosted brand {string} and an affiliate with an active membership at brand {string}") { (_: String, _: String) => addHosted() }
  When("brand {string} posts a {string} fact and a {string} fact to the programme's network webhook") { (_: String, first: String, second: String) =>
    assertEquals((first, second), ("registration", "first-deposit"))
    player = kit.fresh("ps")
    for f <- Seq(scene.facts.registration(player, kit.now), scene.facts.firstDeposit(player, 5_000, kit.now)) do
      assertEquals(Hosted.post(kit, slug, hosted.secret, f).json.get("outcome").asText, "new")
  }
  Then("the player is attributed and CPA is written exactly as for a brand in the installation") { () =>
    assertEquals(scene.state(player).binding.map(_.affiliate), Some(scene.affiliate))
    val rows = scene.rowsWhen("the CPA")(rs => scene.sum(rs, FactType.Cpa) == 2_500)
    assertEquals(rows.filter(_.kind == FactType.Cpa).map(r => (r.brand, r.currency)), Vector((slug, "EUR")))
  }

  // ── a bad signature ─────────────────────────────────────────────────────────────────────────────

  Given("hosted brand {string}") { (_: String) => addHosted() }
  When("a fact for brand {string} is posted with a signature that does not verify under its shared secret") { (_: String) =>
    val f = scene.facts.registration(kit.fresh("ps"), kit.now)
    factId = f.factId
    reply = Hosted.post(kit, slug, hosted.secret, f, signed = Some(Hosted.signature(Hosted.json(f), "another-secret")))
  }
  // A post whose signature does not verify (401 signature), or a manager asking for the usage report (403).
  Then("it is refused") { () =>
    if factId != null then assertEquals((reply.status, reply.json.get("error").asText), (401, "signature"))
    else assertEquals(reply.status, 403, reply.text)
  }
  Then("nothing is recorded") { () =>
    assert(!Hosted.inbox(kit, slug, factId).claimed)
    assertEquals((brandState.duplicates, brandState.mismatches), (0L, 0L))
  }

  // ── another company's brand ─────────────────────────────────────────────────────────────────────

  Given("an affiliate with active memberships at hosted brand {string} and at brand {string}") { (_: String, _: String) =>
    addHosted()
    val gold = Scene(kit, planType = Plan.Cpa, cpa = 1_000)
    ids("aff") = gold.affiliate
    kit.affiliate(gold.affiliate, slug, scene.plan)
    gold.send(gold.facts.firstDeposit(gold.player(), 5_000, kit.now)): Unit
    gold.rowsWhen("A's CPA")(rs => gold.sum(rs, FactType.Cpa) == 1_000): Unit
    kit.request("POST", s"/v1/affiliates/goldvibe/accounts/${gold.affiliate}/notes", Some("""{"text":"A's note"}"""), kit.asManager()): Unit
  }
  When("a manager of brand {string} reads the affiliate") { (_: String) =>
    reply = kit.eventually("the account at H")(Option(kit.request("GET", s"/v1/affiliates/$slug/accounts/${ids("aff")}", headers = kit.asManager(Seq(slug))))
      .filter(_.status == 200))
  }
  Then("they see the affiliate's membership, figures and payouts at brand {string}") { (_: String) =>
    assertEquals(reply.json.get("memberships").elements().asScala.map(_.get("brandId").asText).toSet, Set(slug))
    val h = kit.asManager(Seq(slug))
    for path <- Seq(s"/accounts/${ids("aff")}/revenue?period=all", s"/payouts?period=${kit.now.toString.take(7)}", s"/accounts/${ids("aff")}/notes") do
      assertEquals(kit.request("GET", s"/v1/affiliates/$slug$path", headers = h).status, 200, path)
  }
  Then("nothing of the affiliate at brand {string}") { (_: String) =>
    val h = kit.asManager(Seq(slug))
    val seen = Seq(s"/$slug/accounts/${ids("aff")}", s"/$slug/accounts/${ids("aff")}/revenue?period=all", s"/$slug/accounts/${ids("aff")}/notes")
      .map(p => kit.request("GET", s"/v1/affiliates$p", headers = h).text)
    seen.foreach(t => assert(!t.contains("goldvibe") && !t.contains("A's note"), t))
    for path <- Seq(s"/goldvibe/accounts/${ids("aff")}", s"/goldvibe/accounts/${ids("aff")}/notes", "/goldvibe/fraud-flags") do
      assertEquals(kit.request("GET", s"/v1/affiliates$path", headers = h).status, 403, path)
  }

  // ── the usage report ────────────────────────────────────────────────────────────────────────────

  private def month = YearMonth.from(kit.now.atZone(ZoneId.of("Europe/Malta"))).toString

  Given("facts, memberships and payouts at hosted brand {string} during a month") { (_: String) =>
    addHosted()
    player = kit.fresh("ps")
    Seq(scene.facts.registration(player, kit.now), scene.facts.firstDeposit(player, 5_000, kit.now))
      .foreach(f => assertEquals(Hosted.post(kit, slug, hosted.secret, f).status, 200))
    scene.rowsWhen("the CPA")(rs => scene.sum(rs, FactType.Cpa) == 2_500): Unit
    kit.owed(scene.affiliate, slug, "EUR")
    val admin = kit.asManager(Seq(slug), "admin-1", "brand_admin")
    val period = kit.now.toString.take(7)
    val closed = kit.request("POST", s"/v1/affiliates/$slug/payouts/close?period=$period", headers = admin)
    assertEquals(closed.status, 200, closed.text)
    for status <- Seq("APPROVED", "PAID") do
      val r = kit.request("PATCH", s"/v1/affiliates/$slug/payouts/${scene.affiliate}:EUR:$period/status", Some(s"""{"status":"$status"}"""), admin)
      assertEquals(r.status, 200, s"$status: ${r.text}")
  }
  When("an operator of the installation reads the usage report for brand {string} and that month") { (_: String) =>
    reply = kit.eventually("the usage")(Option(kit.request("GET", s"/v1/programme/brands/$slug/usage?month=$month", headers = kit.asPlatformAdmin()))
      .filter(r => r.status == 200 && r.json.path("commissionPaid").path("EUR").asLong(0) == 2_500 && r.json.path("factsReceived").path("EUR").asInt(0) == 1))
  }
  Then("it gives the facts received, the active memberships, the commission recorded and the commission paid, each per currency") { () =>
    val u = reply.json
    assertEquals((u.path("factsReceived").path("none").asInt, u.path("factsReceived").path("EUR").asInt), (1, 1))
    assertEquals(u.path("activeMemberships").path("EUR").asInt, 1)
    assertEquals((u.path("commissionRecorded").path("EUR").asLong, u.path("commissionPaid").path("EUR").asLong), (2_500L, 2_500L))
  }
  Then("it gives no fee") { () => assert(!reply.text.toLowerCase.contains("fee"), reply.text) }

  // ── a manager asks ──────────────────────────────────────────────────────────────────────────────

  Given("a manager of hosted brand {string}") { (_: String) => addHosted() }
  When("they ask for the usage report for brand {string}") { (_: String) =>
    reply = kit.request("GET", s"/v1/programme/brands/$slug/usage?month=$month", headers = kit.asManager(Seq(slug), "admin-1", "brand_admin"))
  }
