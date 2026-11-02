package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.Activity
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, Sender, UpdateBrand}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.intake.FactInbox
import io.eitheror.casino.programme.comms.{Notices, Notification}
import io.eitheror.casino.programme.ledger.{CommissionAccount, RecordAdjustment, RecordCpa}

import scala.jdk.CollectionConverters.*

/** The sub-affiliate network (US3): override down the tree, invitations into it, and authority over it. */
class NetworkSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def plan(brand: String = "goldvibe") = kit.plan(brand, kit.fresh("p"), Plan.Cpa, cpa = 5_000)

  private def account(aff: String, brand: String = "goldvibe") =
    kit.components.forEventSourcedEntity(CommissionAccount.entityId(aff, brand, "EUR"))

  private def overrideOf(aff: String, brand: String = "goldvibe") = account(aff, brand).call(CommissionAccount.get).invoke().sums.amount(FactType.Override)

  private def earn(aff: String, amount: Long, brand: String = "goldvibe") =
    account(aff, brand).call(CommissionAccount.recordCpa).invoke(RecordCpa(kit.fresh("cpa"), amount, kit.now, "UTC", kit.now)): Unit

  /** t1 over t2 over t3 at goldvibe; t1 earns 5% on t2's line, t2 3% on t3's. */
  private def tiers(): (String, String, String) =
    val p  = plan()
    val t1 = kit.affiliate(kit.fresh("t1"), "goldvibe", p)
    val t2 = kit.affiliate(kit.fresh("t2"), "goldvibe", p, parent = Some(t1), overrideBp = 500)
    val t3 = kit.affiliate(kit.fresh("t3"), "goldvibe", p, parent = Some(t2), overrideBp = 300)
    (t1, t2, t3)

  test("three tiers: each ancestor's override is its percentage of the source's earnings, recomputed as a delta") {
    val (t1, t2, t3) = tiers()
    earn(t3, 100_000)
    kit.eventually("both overrides")(Option((overrideOf(t2), overrideOf(t1))).filter(_ == (3_000L, 5_000L))): Unit
    account(t3).call(CommissionAccount.recordAdjustment)
      .invoke(RecordAdjustment(kit.fresh("adj"), 50_000, "bonus", "mgr-1", kit.now, None, "UTC", kit.now)): Unit
    kit.eventually("the deltas")(Option((overrideOf(t2), overrideOf(t1))).filter(_ == (4_500L, 7_500L))): Unit
    val facts = account(t1).call(CommissionAccount.get).invoke().unsettled.filter(_.kind == FactType.Override)
    assertEquals(facts.map(f => (f.amount, f.sourceAffiliate)), Vector((5_000L, Some(t3)), (2_500L, Some(t3))))
  }

  test("override at a brand where both hold a membership; none where the sub has none, whose facts are unattributed") {
    val gold   = plan()
    val silver = plan("silvervibe")
    val parent = kit.affiliate(kit.fresh("par"), "goldvibe", gold)
    kit.components.forEventSourcedEntity(Membership.entityId(parent, "silvervibe")).call(Membership.applyFor)
      .invoke(Apply(parent, "silvervibe", Application(kit.now, None, "kit"))): Unit
    kit.components.forEventSourcedEntity(Membership.entityId(parent, "silvervibe")).call(Membership.approve)
      .invoke(Approve(silver, "kit", kit.now)): Unit
    val sub = kit.affiliate(kit.fresh("sub"), "goldvibe", gold, parent = Some(parent), overrideBp = 1_000)
    earn(sub, 10_000)
    kit.eventually("the override at goldvibe")(Option(overrideOf(parent)).filter(_ == 1_000L)): Unit
    val facts = Facts("silvervibe", sub, kit.link(kit.fresh("lnk"), sub, "silvervibe"))
    val reg   = facts.registration(kit.fresh("ps"), kit.now)
    kit.publish(reg)
    val outcome = kit.eventually("the inbox's outcome")(
      kit.components.forKeyValueEntity(EntityId(s"silvervibe/${reg.factId}")).call(FactInbox.get).invoke().outcome)
    assertEquals(outcome, "unattributed:not_a_member")
    Thread.sleep(1_000)
    assertEquals(overrideOf(parent, "silvervibe"), 0L)
  }

  test("a parent chain that forms a cycle: each ancestor is written once and the walk ends") {
    val p = plan()
    val a = kit.affiliate(kit.fresh("a"), "goldvibe", p, overrideBp = 1_000)
    val b = kit.affiliate(kit.fresh("b"), "goldvibe", p, parent = Some(a), overrideBp = 1_000)
    val c = kit.affiliate(kit.fresh("c"), "goldvibe", p, parent = Some(b), overrideBp = 1_000)
    // Corrupted below the routes, which refuse a cycle: a's parent is c.
    kit.components.forEventSourcedEntity(EntityId(a)).call(Affiliate.setParent).invoke(SetParent(Some(c), "kit", kit.now)): Unit
    earn(a, 10_000)
    kit.eventually("both ancestors")(Option((overrideOf(b), overrideOf(c))).filter(_ == (1_000L, 1_000L))): Unit
    assertEquals(overrideOf(a), 0L)
    val cycle = kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$b/approve",
      Some(s"""{"planId":"$p","parentAffiliateId":"$c"}"""), kit.asManager())
    assertEquals(cycle.json.get("error").asText, "parent_cycle")
  }

  test("an email invitation makes the sub active at once on the parent's plan at each brand, and invites it to sign in") {
    val gold   = plan()
    val silver = plan("silvervibe")
    val parent = kit.affiliate(kit.fresh("par"), "goldvibe", gold)
    val pm = kit.components.forEventSourcedEntity(Membership.entityId(parent, "silvervibe"))
    pm.call(Membership.applyFor).invoke(Apply(parent, "silvervibe", Application(kit.now, None, "kit"))): Unit
    pm.call(Membership.approve).invoke(Approve(silver, "kit", kit.now)): Unit
    val brand = kit.components.forKeyValueEntity(EntityId("goldvibe"))
    val was   = brand.call(BrandProgramme.get).invoke().sender
    brand.call(BrandProgramme.update).invoke(UpdateBrand(sender = Some(Sender(messages = Map("AffiliateInvite" -> "tid-invite"))))): Unit
    Secrets(kit.secrets).putBrandSecret("goldvibe", Secrets.CustomerIoKey, "cio-key"): Unit
    try
      val email = s"${kit.fresh("sub")}@example.com"
      val r = kit.request("POST", s"/v1/my/$parent/sub-affiliates/invite",
        Some(s"""{"email":"$email","name":"Sub One","overridePercent":"4","brands":["goldvibe","silvervibe"]}"""), kit.asAffiliate(parent))
      assertEquals(r.status, 201, r.text)
      val sub = r.json.get("affiliateId").asText
      assertEquals(r.json.get("email").asText, email)
      val a = kit.components.forEventSourcedEntity(EntityId(sub)).call(Affiliate.get).invoke()
      assertEquals((a.parent, a.overrideBp), (Some(parent), 400))
      def m(b: String) = kit.components.forEventSourcedEntity(Membership.entityId(sub, b)).call(Membership.get).invoke()
      assertEquals((m("goldvibe").status, m("goldvibe").plan), (Some(Membership.Active), Some(gold)))
      assertEquals((m("silvervibe").status, m("silvervibe").plan), (Some(Membership.Active), Some(silver)))
      val sent = kit.eventually("the invitation")(kit.customerIo.to(email).find(_.transactionalId == "tid-invite"))
      assert(sent.data("set_password_url").contains("/accept-invitation?token="), sent.data.toString)
      kit.eventually("the realm user")(kit.realm.byEmail(email).filter(_.roles == Set("affiliate", "brand_goldvibe", "brand_silvervibe"))): Unit
      assertEquals(kit.request("POST", s"/v1/my/$parent/sub-affiliates/invite", Some(s"""{"email":"$email"}"""), kit.asAffiliate(parent))
        .json.get("error").asText, "email_already_registered")
    finally brand.call(BrandProgramme.update).invoke(UpdateBrand(sender = Some(was))): Unit
  }

  test("an invite link: a person who applies through it is pending under the parent, who is told; 50 an hour") {
    val parent = kit.affiliate(kit.fresh("par"), "goldvibe", plan())
    val made   = kit.request("POST", s"/v1/my/$parent/invite-links?brand=goldvibe", Some("{}"), kit.asAffiliate(parent))
    assertEquals(made.status, 201, made.text)
    val token  = made.json.get("token").asText
    val listed = kit.eventually("the link listed")(Option(kit.request("GET", s"/v1/my/$parent/invite-links", headers = kit.asAffiliate(parent)).json)
      .filter(_.size == 1))
    assertEquals(listed.get(0).get("token").asText, token)
    assertEquals(kit.request("GET", s"/v1/join/$token?brandId=goldvibe", headers = kit.fromInternet).json.get("affiliateName").asText, parent)
    assertEquals(kit.request("GET", s"/v1/join/$token?brandId=silvervibe", headers = kit.fromInternet).status, 404)
    val email  = s"${kit.fresh("j")}@example.com"
    val joined = kit.request("POST", s"/v1/join/$token?brandId=goldvibe", Some(s"""{"email":"$email","name":"Jo"}"""), kit.fromInternet)
    assertEquals((joined.status, joined.json.get("status").asText), (201, "pending"), joined.text)
    val sub = joined.json.get("affiliateId").asText
    assertEquals(kit.components.forEventSourcedEntity(EntityId(sub)).call(Affiliate.get).invoke().parent, Some(parent))
    val m = kit.components.forEventSourcedEntity(Membership.entityId(sub, "goldvibe")).call(Membership.get).invoke()
    val notice = Notices.ofMembership(Membership.Applied(sub, "goldvibe", m.application.get), (sub, "goldvibe"), "")
      .find(_.kind == "SUB_AFFILIATE_PENDING").get
    kit.eventually("the parent told")(Option(kit.components.forKeyValueEntity(EntityId(Notices.idOf(s"$sub/goldvibe", notice, m.application.get.appliedAt)))
      .call(Notification.get).invoke()).filter(_.affiliate == parent)): Unit
    assertEquals(kit.request("POST", s"/v1/join/$token?brandId=goldvibe", Some(s"""{"email":"$email"}"""), kit.fromInternet).json.get("error").asText,
      "already_submitted")
    val replies = (1 to 49).map(i => kit.request("POST", s"/v1/join/$token?brandId=goldvibe", Some(s"""{"email":"n$i-$email"}"""), kit.fromInternet))
    assertEquals(replies.count(_.status == 201), 48)
    assertEquals(replies.last.status, 429)
    val revoked = kit.request("DELETE", s"/v1/my/$parent/invite-links/${listed.get(0).get("linkId").asText}", headers = kit.asAffiliate(parent))
    assertEquals(revoked.status, 200)
    assertEquals(kit.request("GET", s"/v1/join/$token?brandId=goldvibe", headers = kit.fromInternet).status, 404)
  }

  test("a parent acts within their subtree, audited as theirs; an operator's suspension stands over the parent") {
    val (t1, t2, t3) = tiers()
    val p2 = plan()
    def act(what: String, as: String = t1, sub: String = t3) =
      kit.request("POST", s"/v1/my/$as/network/$sub/$what?brand=goldvibe", headers = kit.asAffiliate(as))
    def status(a: String) = kit.components.forEventSourcedEntity(Membership.entityId(a, "goldvibe")).call(Membership.get).invoke().status
    assertEquals(act("suspend").status, 200)
    assertEquals(status(t3), Some(Membership.Suspended))
    assertEquals(act("reactivate").status, 200)
    val patched = kit.request("PATCH", s"/v1/my/$t1/network/$t3?brand=goldvibe", Some(s"""{"planId":"$p2","overridePercent":"2"}"""),
      kit.asAffiliate(t1))
    assertEquals(patched.status, 200, patched.text)
    assertEquals(kit.components.forEventSourcedEntity(EntityId(t3)).call(Affiliate.get).invoke().overrideBp, 200)
    val audited = kit.eventually("the audit")(Option(kit.views.forView(Activity).ask(Activity.ofAffiliate, "affiliate" -> t3)).filter(_.size >= 4))
    assertEquals(audited.map(_.actor).distinct, Vector(t1))
    assertEquals(audited.map(_.action).toSet, Set("SUB_AFFILIATE_SUSPEND", "SUB_AFFILIATE_REACTIVATE", "PLAN_CHANGED", "OVERRIDE_CHANGED"))
    // The operator suspends; the parent sees it and cannot reverse it.
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/network/$t3/suspend", headers = kit.asManager()).status, 200)
    val refused = act("reactivate", as = t2)
    assertEquals((refused.status, refused.json.get("error").asText), (409, "suspended_by_operator"))
    assertEquals(status(t3), Some(Membership.Suspended))
    val seen = kit.request("GET", s"/v1/my/$t2/network/$t3?brand=goldvibe", headers = kit.asAffiliate(t2))
    assertEquals(seen.json.get("memberships").get(0).get("status").asText, "suspended")
  }

  test("an affiliate reads every descendant, and nothing above or beside them") {
    val (t1, t2, t3) = tiers()
    val beside = kit.affiliate(kit.fresh("t2b"), "goldvibe", plan(), parent = Some(t1))
    earn(t3, 10_000)
    val period = kit.now.toString.take(7)
    def tree: JsonNode = kit.request("GET", s"/v1/my/$t2/network?period=$period", headers = kit.asAffiliate(t2)).json
    val n = kit.eventually("t3 below t2 with revenue")(Option(tree).filter(t => t.get("children").size == 1 &&
      t.get("children").get(0).get("revenueForPeriod").elements().asScala.exists(_.get("cpaCommission").asLong == 10_000)))
    val child = n.get("children").get(0)
    assertEquals((child.get("affiliateId").asText, child.get("status").asText, child.get("overridePercent").asDouble), (t3, "active", 3.0))
    val health = kit.request("GET", s"/v1/my/$t1/network-health", headers = kit.asAffiliate(t1)).json
    assertEquals((health.get("totalSubs").asInt, health.get("activeSubs").asInt), (3, 3))
    val subs = kit.request("GET", s"/v1/my/$t1/sub-affiliates", headers = kit.asAffiliate(t1)).json
    assertEquals(subs.elements().asScala.map(_.get("affiliateId").asText).toSet, Set(t2, beside))
    assertEquals(kit.request("GET", s"/v1/my/$t1/network", headers = kit.asAffiliate(t2)).json.get("error").asText, "account_not_found")
    assertEquals(kit.request("GET", s"/v1/my/$t2/network/$beside", headers = kit.asAffiliate(t2)).status, 403)
    assertEquals(kit.request("GET", s"/v1/my/$t2/network/$t1", headers = kit.asAffiliate(t2)).status, 403)
    assertEquals(kit.request("POST", s"/v1/my/$t2/network/$beside/suspend?brand=goldvibe", headers = kit.asAffiliate(t2)).status, 403)
    val operator = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$t1/network-health", headers = kit.asManager()).json
    assertEquals(operator.get("totalSubs").asInt, 3)
  }
