package io.eitheror.casino.programme.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, UpdateBrand}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.comms.Notification
import io.eitheror.casino.programme.it.{Facts, Reply, Scene}

import java.time.Instant
import scala.jdk.CollectionConverters.*

/** features/programme/memberships.feature (US1). Brand "A" is goldvibe and "B" silvervibe. */
class MembershipSteps extends ProgrammeFeatures("memberships.feature"):

  private def slug(name: String) = name match
    case "A" => "goldvibe"
    case "B" => "silvervibe"
    case other => other

  // ── a scenario's state ──────────────────────────────────────────────────────────────────────────

  private var aff: String          = null
  private var email: String        = null
  private var plan: String         = null
  private var approvedAt: Instant  = null
  private var before: MembershipState = null
  private var reply: Reply         = null
  private var gold: Scene          = null
  private var silver: Facts        = null
  private var player: String       = null
  private var restore: () => Unit  = () => ()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    aff = null; email = null; plan = null; approvedAt = null; before = null; reply = null; gold = null; silver = null; player = null

  override def afterEach(context: AfterEach): Unit =
    try restore() finally restore = () => ()
    super.afterEach(context)

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private def membership(brand: String) =
    kit.components.forEventSourcedEntity(Membership.entityId(aff, brand)).call(Membership.get).invoke()

  private def applyTo(brand: String): Reply =
    reply = kit.request("POST", s"/v1/affiliates/$brand/apply", Some(s"""{"email":"$email","name":"Ada Lovelace"}"""), kit.fromInternet)
    if reply.status == 201 then aff = reply.json.get("affiliateId").asText
    reply

  private def applicant(): Unit = email = s"${kit.fresh("ada")}@example.com"

  private def approved(brand: String): Unit =
    val p = kit.plan(brand, kit.fresh("plan"), Plan.Cpa, cpa = 5_000)
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/approve", Some(s"""{"planId":"$p"}"""), kit.asManager(Seq(brand))).status, 200)

  private def rejected(brand: String): Unit =
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/reject", Some("{}"), kit.asManager(Seq(brand))).status, 200)

  private def figures(n: JsonNode) = n.get("figures").elements().asScala.toVector

  private def revenue(query: String): JsonNode =
    kit.request("GET", s"/v1/my/$aff/revenue?period=${kit.now.toString.take(7)}$query", headers = kit.asAffiliate(aff)).json

  // ── a second brand ──────────────────────────────────────────────────────────────────────────────

  Given("an affiliate with an active membership at brand {string}") { (brand: String) =>
    applicant()
    applyTo(slug(brand)): Unit
    approved(slug(brand))
    before = membership(slug(brand))
  }
  When("they apply to brand {string}") { (brand: String) => applyTo(slug(brand)): Unit }
  Then("brand {string} shows one pending application") { (brand: String) =>
    kit.eventually("the pending application listed")(Option(kit.views.forView(Memberships)
      .ask(Memberships.byBrandAndStatus, "brand" -> slug(brand), "status" -> Membership.Pending).filter(_.affiliate == aff)).filter(_.size == 1)): Unit
  }
  Then("the membership at brand {string} is unchanged") { (brand: String) => assertEquals(membership(slug(brand)), before) }
  Then("the affiliate's portal lists brand {string} as active and brand {string} as pending") { (a: String, b: String) =>
    val listed = kit.eventually("both memberships")(Option(kit.request("GET", s"/v1/my/$aff/account", headers = kit.asAffiliate(aff)))
      .flatMap(r => Option(r.json.get("memberships"))).filter(_.size == 2))
    assertEquals(listed.elements().asScala.map(m => m.get("brandId").asText -> m.get("status").asText).toMap,
      Map(slug(a) -> "active", slug(b) -> "pending"))
  }

  // ── approval ────────────────────────────────────────────────────────────────────────────────────

  Given("a pending application at brand {string}") { (brand: String) =>
    applicant()
    applyTo(slug(brand)): Unit
  }
  When("a manager for brand {string} approves it on the default plan of brand {string}") { (manager: String, brand: String) =>
    val b      = slug(brand)
    plan       = kit.plan(b, kit.fresh("default"), Plan.Cpa, cpa = 5_000)
    val entity = kit.components.forKeyValueEntity(EntityId(b))
    val was    = entity.call(BrandProgramme.get).invoke().defaultPlan
    entity.call(BrandProgramme.update).invoke(UpdateBrand(defaultPlan = Some(plan))): Unit
    restore = () => was.foreach(p => entity.call(BrandProgramme.update).invoke(UpdateBrand(defaultPlan = Some(p))): Unit)
    approvedAt = kit.now
    reply = kit.request("POST", s"/v1/affiliates/$b/accounts/$aff/approve", Some("{}"), kit.asManager(Seq(slug(manager))))
    assertEquals(reply.status, 200, reply.text)
  }
  Then("the membership is active on that plan") { () =>
    val m = membership("silvervibe")
    assertEquals((m.status, m.plan), (Some(Membership.Active), Some(plan)))
  }
  Then("the affiliate is told") { () =>
    val id = Notification.idFor(s"membership/$aff/silvervibe/$aff/APPLICATION_APPROVED/$approvedAt")
    kit.eventually("the approval notice")(Option(kit.components.forKeyValueEntity(EntityId(id)).call(Notification.get).invoke())
      .filter(_.kind == "APPLICATION_APPROVED")): Unit
  }
  Then("a manager for brand {string} only can neither see nor act on it") { (brand: String) =>
    val elsewhere = kit.asManager(Seq(slug(brand)))
    assertEquals(kit.request("GET", s"/v1/affiliates/silvervibe/accounts/$aff", headers = elsewhere).status, 403)
    assertEquals(kit.request("POST", s"/v1/affiliates/silvervibe/accounts/$aff/reject", Some("{}"), elsewhere).status, 403)
    assertEquals(kit.request("GET", s"/v1/affiliates/${slug(brand)}/accounts/$aff", headers = elsewhere).status, 404)
  }

  // ── revenue across brands ───────────────────────────────────────────────────────────────────────

  /** The affiliate active at goldvibe (CPA 5,000 EUR) and silvervibe (CPA 7,000 USD), a first deposit at each. */
  private def atBoth(): Unit =
    gold = Scene(kit, planType = Plan.Cpa, cpa = 5_000, minimumDeposit = 0)
    aff  = gold.affiliate
    val p = kit.plan("silvervibe", kit.fresh("silver"), Plan.Cpa, cpa = 7_000, currency = "USD")
    val m = kit.components.forEventSourcedEntity(Membership.entityId(aff, "silvervibe"))
    m.call(Membership.applyFor).invoke(Apply(aff, "silvervibe", Application(kit.now, None, "kit"))): Unit
    m.call(Membership.approve).invoke(Approve(p, "kit", kit.now)): Unit
    silver = Facts("silvervibe", aff, kit.link(kit.fresh("lnk"), aff, "silvervibe"))

  Given("active memberships at brands {string} and {string} with facts at each") { (_: String, _: String) =>
    atBoth()
    val p1 = gold.player()
    gold.send(gold.facts.firstDeposit(p1, 2_000, kit.now)): Unit
    gold.send(gold.facts.deposit(p1, 2_000, kit.now)): Unit
    val p2 = kit.fresh("ps")
    kit.publish(silver.registration(p2, kit.now))
    kit.publish(silver.firstDeposit(p2, 3_000, kit.now, "USD"))
    kit.publish(silver.deposit(p2, 3_000, kit.now, "USD"))
    kit.eventually("both CPAs")(Option(revenue("")).filter(n => figures(n).count(_.get("cpaCommission").asLong > 0) == 2)): Unit
  }
  private var read: JsonNode = null
  When("the affiliate reads revenue for brand {string}") { (brand: String) => read = revenue(s"&brand=${slug(brand)}") }
  When("the affiliate reads revenue with no brand filter") { () => read = revenue("") }
  Then("only the facts of brand {string} are folded") { (brand: String) =>
    assertEquals(figures(read).map(f => (f.get("brandId").asText, f.get("cpaCommission").asLong, f.get("depositCount").asLong)),
      Vector((slug(brand), 5_000L, 1L)))
  }
  Then("each brand's figures are listed separately") { () =>
    assertEquals(figures(read).map(_.get("brandId").asText), Vector("goldvibe", "silvervibe"))
  }
  Then("totals are given per currency and never summed across currencies") { () =>
    assertEquals(figures(read).map(f => (f.get("currency").asText, f.get("totalCommission").asLong)), Vector(("EUR", 5_000L), ("USD", 7_000L)))
  }

  // ── suspension ──────────────────────────────────────────────────────────────────────────────────

  Given("an affiliate suspended at brand {string} and active at brand {string}") { (_: String, _: String) =>
    atBoth()
    player = gold.player()
    gold.suspend()
  }
  When("facts arrive from brand {string} for players attributed to them") { (_: String) =>
    gold.send(gold.facts.firstDeposit(player, 2_000, kit.now)): Unit
    gold.send(gold.facts.ggrDaily(player, kit.now.toString.take(10), 1_000, 0, kit.now)): Unit
  }
  Then("the facts are recorded in the ledger") { () => gold.rowsWhen("the GGR")(_.exists(_.kind == FactType.Ggr)): Unit }
  Then("no new payable fact is written while the membership is suspended") { () =>
    Thread.sleep(2_000)
    assertEquals(gold.rows.filter(_.kind.payable), Vector.empty)
  }
  Then("brand {string} is unaffected") { (brand: String) =>
    assertEquals(membership(slug(brand)).status, Some(Membership.Active))
    val p = kit.fresh("ps")
    kit.publish(silver.registration(p, kit.now))
    kit.publish(silver.firstDeposit(p, 3_000, kit.now, "USD"))
    kit.eventually("silvervibe's CPA")(Option(revenue("&brand=silvervibe")).filter(n => figures(n).exists(_.get("cpaCommission").asLong == 7_000))): Unit
  }

  // ── rejection and duplicates ────────────────────────────────────────────────────────────────────

  Given("an affiliate rejected at brand {string}") { (brand: String) =>
    applicant()
    applyTo(slug(brand)): Unit
    rejected(slug(brand))
  }
  When("they apply to brand {string} again") { (brand: String) => applyTo(slug(brand)): Unit }
  Then("the application is accepted") { () =>
    assertEquals(reply.status, 201, reply.text)
    assertEquals(membership("silvervibe").status, Some(Membership.Pending))
  }

  Given("a rejected membership at brand {string}") { (brand: String) =>
    applicant()
    applyTo(slug(brand)): Unit
    rejected(slug(brand))
  }
  When("a manager sets it to suspended") { () =>
    // The only way a manager changes a status is a transition; O19's raw route is not served (change 13).
    reply = kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/$aff/status", Some("""{"status":"SUSPENDED"}"""), kit.asManager())
    assertEquals(reply.status, 404)
    reply = try
      kit.components.forEventSourcedEntity(Membership.entityId(aff, "goldvibe")).call(Membership.suspend)
        .invoke(Suspend("mgr-1", Membership.Operator, kit.now)): Unit
      null
    catch case e: com.thinkmorestupidless.ankka.core.CommandError => Reply(409, e.getMessage)
  }
  Then("it is refused, naming the membership's status") { () =>
    assert(reply != null, "the suspension was not refused")
    assertEquals(reply.text, "must be active, currently rejected")
    assertEquals(membership("goldvibe").status, Some(Membership.Rejected))
  }

  Given("an applicant whose email already has a pending or active membership at brand {string}") { (brand: String) =>
    applicant()
    applyTo(slug(brand)): Unit
  }
  Then("the application is refused as a duplicate") { () =>
    assertEquals((reply.status, reply.json.get("error").asText), (409, "email_already_registered"))
  }
