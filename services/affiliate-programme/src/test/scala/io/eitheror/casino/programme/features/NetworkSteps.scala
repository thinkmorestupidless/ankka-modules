package io.eitheror.casino.programme.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.Activity
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, Sender, UpdateBrand}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.comms.{Notices, Notification}
import io.eitheror.casino.programme.intake.FactInbox
import io.eitheror.casino.programme.it.{Facts, Reply}
import io.eitheror.casino.programme.ledger.{CommissionAccount, RecordAdjustment, RecordCpa}

import scala.jdk.CollectionConverters.*

/** features/programme/network.feature (US3). Brand "A" is goldvibe and "B" silvervibe. */
class NetworkSteps extends ProgrammeFeatures("network.feature"):

  private def slug(name: String) = name match
    case "A" => "goldvibe"
    case "B" => "silvervibe"
    case other => other

  private var restore: () => Unit = () => ()
  private var parent: String      = null
  private var sub: String         = null
  private var subs: Vector[String] = Vector.empty
  private var plans               = Map.empty[String, String]
  private var reply: Reply        = null
  private var email: String       = null
  private var factId: String      = null
  private var cycle: Vector[String] = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    parent = null; sub = null; subs = Vector.empty; plans = Map.empty; reply = null; email = null; factId = null; cycle = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    try restore() finally restore = () => ()
    super.afterEach(context)

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private def planAt(brand: String): String = plans.getOrElse(brand, {
    val p = kit.plan(brand, kit.fresh("plan"), Plan.Cpa, cpa = 5_000)
    plans += brand -> p
    p
  })

  private def account(aff: String, brand: String = "goldvibe") =
    kit.components.forEventSourcedEntity(CommissionAccount.entityId(aff, brand, "EUR"))

  private def overrideOf(aff: String, brand: String = "goldvibe") =
    account(aff, brand).call(CommissionAccount.get).invoke().sums.amount(FactType.Override)

  private def earn(aff: String, amount: Long, brand: String = "goldvibe") =
    account(aff, brand).call(CommissionAccount.recordCpa).invoke(RecordCpa(kit.fresh("cpa"), amount, kit.now, "UTC", kit.now)): Unit

  private def membership(aff: String, brand: String) =
    kit.components.forEventSourcedEntity(Membership.entityId(aff, brand)).call(Membership.get).invoke()

  private def joinAt(aff: String, brand: String): Unit =
    val m = kit.components.forEventSourcedEntity(Membership.entityId(aff, brand))
    m.call(Membership.applyFor).invoke(Apply(aff, brand, Application(kit.now, None, "kit"))): Unit
    m.call(Membership.approve).invoke(Approve(planAt(brand), "kit", kit.now)): Unit

  private def affiliateOf(aff: String): AffiliateState = kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke()

  // ── override down the tree ──────────────────────────────────────────────────────────────────────

  Given("an affiliate {string} with an override of {string} percent over a sub {string} with an override of {string} percent over a sub {string}") {
    (t1: String, p1: String, t2: String, p2: String, t3: String) =>
      // The share an ancestor earns is the override on the affiliate below it (the original's walk).
      val a = kit.affiliate(id(t1), "goldvibe", planAt("goldvibe"))
      val b = kit.affiliate(id(t2), "goldvibe", planAt("goldvibe"), parent = Some(a), overrideBp = p1.toInt * 100)
      kit.affiliate(id(t3), "goldvibe", planAt("goldvibe"), parent = Some(b), overrideBp = p2.toInt * 100): Unit
  }
  When("{string} earns {string} of commission at brand {string} in a period") { (who: String, amount: String, brand: String) =>
    earn(id(who), amount.toLong, slug(brand))
  }
  Then("{string} is written an override fact of {string} and {string} one of {string}, each with {string} as the source") {
    (a: String, x: String, b: String, y: String, source: String) =>
      kit.eventually("both overrides")(Option((overrideOf(id(a)), overrideOf(id(b)))).filter(_ == (x.toLong, y.toLong))): Unit
      Seq(a, b).foreach { who =>
        val facts = account(id(who), "goldvibe").call(CommissionAccount.get).invoke().unsettled.filter(_.kind == FactType.Override)
        assertEquals(facts.flatMap(_.sourceAffiliate).distinct, Vector(id(source)))
      }
  }
  Then("each is recomputed as a delta when the earnings of {string} change") { (who: String) =>
    val (t2, t1) = (overrideOf(id("T2")), overrideOf(id("T1")))
    account(id(who)).call(CommissionAccount.recordAdjustment)
      .invoke(RecordAdjustment(kit.fresh("adj"), 50_000, "bonus", "mgr-1", kit.now, None, "UTC", kit.now)): Unit
    kit.eventually("the deltas")(Option((overrideOf(id("T2")), overrideOf(id("T1")))).filter(_ == (t2 + 1_500, t1 + 2_500))): Unit
    val written = account(id("T1"), "goldvibe").call(CommissionAccount.get).invoke().unsettled.filter(_.kind == FactType.Override)
    assertEquals(written.map(_.amount), Vector(t1, 2_500L))
  }

  Given("a parent and a sub who both hold memberships at brand {string} and only the parent at brand {string}") { (a: String, b: String) =>
    parent = kit.affiliate(id("parent"), slug(a), planAt(slug(a)))
    joinAt(parent, slug(b))
    sub = kit.affiliate(id("sub"), slug(a), planAt(slug(a)), parent = Some(parent), overrideBp = 1_000)
  }
  When("the sub earns at brand {string}") { (brand: String) => earn(sub, 10_000, slug(brand)) }
  Then("the parent's override is written at brand {string}") { (brand: String) =>
    kit.eventually("the override")(Option(overrideOf(parent, slug(brand))).filter(_ == 1_000L)): Unit
  }
  When("facts for the sub arrive from brand {string}") { (brand: String) =>
    val reg = Facts(slug(brand), sub, kit.link(kit.fresh("lnk"), sub, slug(brand))).registration(kit.fresh("ps"), kit.now)
    factId = reg.factId
    kit.publish(reg)
  }
  Then("they are unattributed at brand {string}") { (brand: String) =>
    val outcome = kit.eventually("the inbox's outcome")(
      kit.components.forKeyValueEntity(EntityId(s"${slug(brand)}/$factId")).call(FactInbox.get).invoke().outcome)
    assertEquals(outcome, "unattributed:not_a_member")
  }
  Then("no override is written") { () =>
    Thread.sleep(1_000)
    assertEquals((overrideOf(parent, "goldvibe"), overrideOf(parent, "silvervibe")), (0L, 0L))
  }

  // ── invitations ─────────────────────────────────────────────────────────────────────────────────

  Given("a parent with active memberships at brands {string} and {string}") { (a: String, b: String) =>
    parent = kit.affiliate(id("parent"), slug(a), planAt(slug(a)))
    joinAt(parent, slug(b))
    val brand = kit.components.forKeyValueEntity(EntityId(slug(a)))
    val was   = brand.call(BrandProgramme.get).invoke().sender
    brand.call(BrandProgramme.update).invoke(UpdateBrand(sender = Some(Sender(messages = Map("AffiliateInvite" -> "tid-invite"))))): Unit
    Secrets(kit.secrets).putBrandSecret(slug(a), Secrets.CustomerIoKey, "cio-key"): Unit
    restore = () => brand.call(BrandProgramme.update).invoke(UpdateBrand(sender = Some(was))): Unit
  }
  When("they invite a sub by email naming brands {string} and {string}") { (a: String, b: String) =>
    email = s"${kit.fresh("sub")}@example.com"
    reply = kit.request("POST", s"/v1/my/$parent/sub-affiliates/invite",
      Some(s"""{"email":"$email","name":"Sub One","brands":["${slug(a)}","${slug(b)}"]}"""), kit.asAffiliate(parent))
    assertEquals(reply.status, 201, reply.text)
    sub = reply.json.get("affiliateId").asText
  }
  Then("the sub's account is created under the parent with active memberships at brands {string} and {string}") { (a: String, b: String) =>
    assertEquals(affiliateOf(sub).parent, Some(parent))
    assertEquals(Seq(a, b).map(x => membership(sub, slug(x)).status), Seq(Some(Membership.Active), Some(Membership.Active)))
  }
  Then("the sub is on the parent's plan at each brand unless the invitation says otherwise") { () =>
    assertEquals(Seq("goldvibe", "silvervibe").map(b => membership(sub, b).plan), Seq("goldvibe", "silvervibe").map(b => Some(planAt(b))))
    val other = kit.plan("goldvibe", kit.fresh("other"), Plan.Cpa, cpa = 7_000)
    val named = kit.request("POST", s"/v1/my/$parent/sub-affiliates/invite",
      Some(s"""{"email":"${kit.fresh("sub")}@example.com","planId":"$other","brands":["goldvibe"]}"""), kit.asAffiliate(parent))
    assertEquals(membership(named.json.get("affiliateId").asText, "goldvibe").plan, Some(other))
  }
  Then("the sub is invited to sign in") { () =>
    val sent = kit.eventually("the invitation")(kit.customerIo.to(email).find(_.transactionalId == "tid-invite"))
    assert(sent.data("set_password_url").contains("/accept-invitation?token="), sent.data.toString)
  }

  Given("a parent's invite link for brand {string}") { (brand: String) =>
    parent = kit.affiliate(id("parent"), slug(brand), planAt(slug(brand)))
    reply = kit.request("POST", s"/v1/my/$parent/invite-links?brand=${slug(brand)}", Some("{}"), kit.asAffiliate(parent))
    assertEquals(reply.status, 201, reply.text)
  }
  When("a person applies through it") { () =>
    val token = reply.json.get("token").asText
    reply = kit.request("POST", s"/v1/join/$token?brandId=goldvibe", Some(s"""{"email":"${kit.fresh("j")}@example.com"}"""), kit.fromInternet)
    assertEquals(reply.status, 201, reply.text)
    sub = reply.json.get("affiliateId").asText
  }
  Then("the sub's account is created under the parent with a pending membership at brand {string}") { (brand: String) =>
    assertEquals((affiliateOf(sub).parent, membership(sub, slug(brand)).status), (Some(parent), Some(Membership.Pending)))
  }
  Then("the parent is told that an approval is pending") { () =>
    val app    = membership(sub, "goldvibe").application.get
    val notice = Notices.ofMembership(Membership.Applied(sub, "goldvibe", app), (sub, "goldvibe"), "").find(_.kind == "SUB_AFFILIATE_PENDING").get
    kit.eventually("the parent told")(Option(kit.components.forKeyValueEntity(EntityId(Notices.idOf(s"$sub/goldvibe", notice, app.appliedAt)))
      .call(Notification.get).invoke()).filter(_.affiliate == parent)): Unit
  }

  // ── authority ───────────────────────────────────────────────────────────────────────────────────

  /** parent over a over b, and c beside parent under root. */
  private def subtree(): Unit =
    val root = kit.affiliate(id("root"), "goldvibe", planAt("goldvibe"))
    parent = kit.affiliate(id("parent"), "goldvibe", planAt("goldvibe"), parent = Some(root), overrideBp = 500)
    val a  = kit.affiliate(id("a"), "goldvibe", planAt("goldvibe"), parent = Some(parent), overrideBp = 300)
    val b  = kit.affiliate(id("b"), "goldvibe", planAt("goldvibe"), parent = Some(a), overrideBp = 200)
    kit.affiliate(id("beside"), "goldvibe", planAt("goldvibe"), parent = Some(root)): Unit
    subs = Vector(a, b)

  private def act(what: String, on: String) =
    kit.request("POST", s"/v1/my/$parent/network/$on/$what?brand=goldvibe", headers = kit.asAffiliate(parent))

  Given("a parent") { () => subtree() }
  When("they approve, suspend or reactivate a sub, or change its plan or override") { () =>
    val email = s"${kit.fresh("p")}@example.com"
    val token = kit.request("POST", s"/v1/my/$parent/invite-links?brand=goldvibe", Some("{}"), kit.asAffiliate(parent)).json.get("token").asText
    sub = kit.request("POST", s"/v1/join/$token?brandId=goldvibe", Some(s"""{"email":"$email"}"""), kit.fromInternet).json.get("affiliateId").asText
    assertEquals(act("approve", sub).status, 200)
    assertEquals(act("suspend", subs(1)).status, 200)
    assertEquals(act("reactivate", subs(1)).status, 200)
    val other = kit.plan("goldvibe", kit.fresh("other"), Plan.Cpa, cpa = 7_000)
    reply = kit.request("PATCH", s"/v1/my/$parent/network/${subs(1)}?brand=goldvibe", Some(s"""{"planId":"$other","overridePercent":"1"}"""),
      kit.asAffiliate(parent))
    assertEquals(reply.status, 200, reply.text)
    plans += "other" -> other
  }
  Then("it applies within their own subtree") { () =>
    assertEquals(membership(sub, "goldvibe").status, Some(Membership.Active))
    assertEquals((membership(subs(1), "goldvibe").status, membership(subs(1), "goldvibe").plan), (Some(Membership.Active), Some(plans("other"))))
    assertEquals(affiliateOf(subs(1)).overrideBp, 100)
    assertEquals(act("suspend", id("beside")).status, 403)
    assertEquals(membership(id("beside"), "goldvibe").status, Some(Membership.Active))
  }
  Then("it is audited as the parent's action") { () =>
    def audited(a: String, n: Int) = kit.eventually(s"$a's audit")(Option(kit.views.forView(Activity).ask(Activity.ofAffiliate, "affiliate" -> a))
      .filter(_.size >= n))
    // A joiner's application by the link is its own row (change 17); the actions on it are the parent's.
    def acted(a: String, n: Int) = audited(a, n).filterNot(_.actor == "invite-link")
    assertEquals(acted(sub, 2).map(r => (r.action, r.actor)), Vector(("SUB_AFFILIATE_APPROVE", parent)))
    assertEquals(acted(subs(1), 4).map(_.actor).distinct, Vector(parent))
  }

  Given("a sub an operator has suspended") { () =>
    subtree()
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/network/${subs(0)}/suspend", headers = kit.asManager()).status, 200)
  }
  When("the parent reactivates the sub") { () => reply = act("reactivate", subs(0)) }
  Then("the operator's action stands") { () =>
    assertEquals((reply.status, reply.json.get("error").asText), (409, "suspended_by_operator"))
    assertEquals(membership(subs(0), "goldvibe").status, Some(Membership.Suspended))
  }
  Then("the parent sees it but cannot reverse it") { () =>
    val seen = kit.request("GET", s"/v1/my/$parent/network/${subs(0)}?brand=goldvibe", headers = kit.asAffiliate(parent))
    assertEquals(seen.json.get("memberships").get(0).get("status").asText, "suspended")
    assertEquals(act("reactivate", subs(0)).status, 409)
  }

  Given("an affiliate with a subtree") { () => subtree() }
  private var tree: JsonNode = null
  When("they read their subtree") { () =>
    earn(subs(1), 10_000)
    val period = kit.now.toString.take(7)
    tree = kit.eventually("the subtree with b's revenue")(Option(kit.request("GET", s"/v1/my/$parent/network?period=$period",
      headers = kit.asAffiliate(parent)).json).filter { t =>
      val b = Option(t.get("children")).flatMap(c => Option(c.get(0))).flatMap(a => Option(a.get("children")))
      b.exists(b => b.size == 1 && b.get(0).get("revenueForPeriod").elements().asScala.exists(_.get("cpaCommission").asLong == 10_000))
    })
  }
  Then("they see every descendant with status, plan, override and revenue for the period") { () =>
    def flat(n: JsonNode): Vector[JsonNode] = n.get("children").elements().asScala.toVector.flatMap(c => c +: flat(c))
    val seen = flat(tree)
    assertEquals(seen.map(_.get("affiliateId").asText), subs)
    assertEquals(seen.map(n => (n.get("status").asText, n.get("planId").asText, n.get("overridePercent").asDouble)),
      Vector(("active", planAt("goldvibe"), 3.0), ("active", planAt("goldvibe"), 2.0)))
    assert(seen.forall(_.has("revenueForPeriod")))
  }
  Then("they see nothing above or beside them") { () =>
    assertEquals(tree.get("affiliateId").asText, parent)
    val ids = tree.toString
    assert(!ids.contains(id("root")) && !ids.contains(id("beside")), ids)
  }
  When("they ask about an affiliate outside their subtree") { () =>
    reply = kit.request("GET", s"/v1/my/$parent/network/${id("beside")}", headers = kit.asAffiliate(parent))
  }
  Then("it is refused") { () =>
    assertEquals(reply.status, 403)
    assertEquals(kit.request("GET", s"/v1/my/$parent/network/${id("root")}", headers = kit.asAffiliate(parent)).status, 403)
    assertEquals(kit.request("GET", s"/v1/my/${id("root")}/network", headers = kit.asAffiliate(parent)).status, 403)
  }

  Given("a corrupted parent chain that forms a cycle") { () =>
    val p = planAt("goldvibe")
    val a = kit.affiliate(id("ca"), "goldvibe", p, overrideBp = 1_000)
    val b = kit.affiliate(id("cb"), "goldvibe", p, parent = Some(a), overrideBp = 1_000)
    val c = kit.affiliate(id("cc"), "goldvibe", p, parent = Some(b), overrideBp = 1_000)
    kit.components.forEventSourcedEntity(EntityId(a)).call(Affiliate.setParent).invoke(SetParent(Some(c), "kit", kit.now)): Unit
    cycle = Vector(a, b, c)
  }
  When("override is computed") { () => earn(cycle(0), 10_000) }
  Then("each ancestor is visited once") { () =>
    kit.eventually("both ancestors")(Option((overrideOf(cycle(1)), overrideOf(cycle(2)))).filter(_ == (1_000L, 1_000L))): Unit
  }
  Then("the computation ends") { () =>
    Thread.sleep(1_000)
    assertEquals((overrideOf(cycle(0)), overrideOf(cycle(1)), overrideOf(cycle(2))), (0L, 1_000L, 1_000L))
  }
