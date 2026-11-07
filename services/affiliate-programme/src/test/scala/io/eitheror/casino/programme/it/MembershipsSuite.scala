package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.accounts.{Affiliate, EmailIndex, Membership, Secrets}
import io.eitheror.casino.programme.sealing.Digest
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, UpdateBrand}
import io.eitheror.casino.programme.links.Link

import scala.jdk.CollectionConverters.*

/** One account, a membership per brand (US1), over the routes, the realm and the ledger. */
class MembershipsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def apply(brand: String, email: String, name: String = "Ada Lovelace") =
    kit.request("POST", s"/v1/affiliates/$brand/apply", Some(s"""{"email":"$email","name":"$name","website":"https://ada.example"}"""), kit.fromInternet)

  private def approve(brand: String, aff: String, body: String = "{}", as: Seq[(String, String)] = Nil) =
    kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/approve", Some(body), if as.isEmpty then kit.asManager(Seq(brand)) else as)

  /** A default plan of the brand's for this test, put back after it. */
  private def withDefaultPlan[A](brand: String)(body: String => A): A =
    val plan   = kit.plan(brand, kit.fresh("default"), Plan.Cpa, cpa = 5_000, minimumDeposit = 1_000)
    val entity = kit.components.forKeyValueEntity(EntityId(brand))
    val before = entity.call(BrandProgramme.get).invoke().defaultPlan
    entity.call(BrandProgramme.update).invoke(UpdateBrand(defaultPlan = Some(plan))): Unit
    try body(plan) finally before.foreach(p => entity.call(BrandProgramme.update).invoke(UpdateBrand(defaultPlan = Some(p))): Unit)

  test("an address whose index entry names an account never made registers all the same") {
    val email  = s"${kit.fresh("orphan")}@example.com"
    val key    = kit.eventually("the email index key")(Secrets(kit.secrets).emailIndexKey.toOption)
    val digest = Digest.of(email, key)
    kit.components.forKeyValueEntity(EntityId(digest)).call(EmailIndex.claim).invoke("aff-never-made"): Unit
    val r = apply("goldvibe", email)
    assertEquals(r.status, 201, r.text)
    val aff = r.json.get("affiliateId").asText
    assertEquals(kit.components.forKeyValueEntity(EntityId(digest)).call(EmailIndex.get).invoke().affiliate, Some(aff))
  }

  private def status(aff: String, brand: String) =
    kit.components.forEventSourcedEntity(Membership.entityId(aff, brand)).call(Membership.get).invoke().status

  test("an application makes an account and a pending membership; a second brand's leaves the first as it was") {
    val email = s"${kit.fresh("ada")}@example.com"
    val first = apply("goldvibe", email)
    assertEquals(first.status, 201, first.text)
    val aff = first.json.get("affiliateId").asText
    assertEquals(first.json.get("status").asText, "pending")
    assertEquals(approve("goldvibe", aff, s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""").status, 200)
    val second = apply("silvervibe", email)
    assertEquals((second.status, second.json.get("affiliateId").asText), (201, aff))
    assertEquals((status(aff, "goldvibe"), status(aff, "silvervibe")), (Some(Membership.Active), Some(Membership.Pending)))
    val account = kit.eventually("both memberships listed")(Option(kit.request("GET", s"/v1/my/$aff/account", headers = kit.asAffiliate(aff)))
      .filter(r => Option(r.json.get("memberships")).exists(_.size == 2)))
    val listed = account.json.get("memberships").elements().asScala.map(m => m.get("brandId").asText -> m.get("status").asText).toMap
    assertEquals(listed, Map("goldvibe" -> "active", "silvervibe" -> "pending"))
    assertEquals(account.json.get("email").asText, email)
  }

  test("a manager approves on the brand's default plan; the realm user holds the roles; a default link is made") {
    withDefaultPlan("goldvibe") { plan =>
      val email = s"${kit.fresh("bo")}@example.com"
      val aff   = apply("goldvibe", email).json.get("affiliateId").asText
      assertEquals(approve("goldvibe", aff).text, """{"ok":true}""")
      assertEquals(kit.components.forEventSourcedEntity(Membership.entityId(aff, "goldvibe")).call(Membership.get).invoke().plan, Some(plan))
      val user = kit.eventually("the realm user")(kit.realm.byEmail(email).filter(_.roles == Set("affiliate", "brand_goldvibe")))
      assertEquals(user.attributes.get("eitheror_scope_id"), Some(aff))
      kit.eventually("the user on the account")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser)
      val link = kit.eventually("the default link")(
        Option(kit.components.forKeyValueEntity(EntityId(Link.defaultIdOf(aff, "goldvibe"))).call(Link.get).invoke()).filter(_.exists))
      assertEquals((link.affiliate, link.campaign), (aff, Some("default")))
    }
  }

  test("a realm user deleted under the account stalls nothing: a suspension removes no role, an approval makes the user again") {
    val email = s"${kit.fresh("gone")}@example.com"
    val aff   = apply("goldvibe", email).json.get("affiliateId").asText
    assertEquals(approve("goldvibe", aff, s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""").status, 200)
    val first = kit.eventually("the realm user")(kit.realm.byEmail(email).filter(_.roles("brand_goldvibe")))
    kit.eventually("the user on the account")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser): Unit
    kit.realm.deleteUser(first.id): Unit
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/network/$aff/suspend", None, kit.asManager()).status, 200)
    assertEquals(apply("silvervibe", email).status, 201)
    assertEquals(approve("silvervibe", aff, s"""{"planId":"${kit.plan("silvervibe", kit.fresh("p"))}"}""").status, 200)
    val again = kit.eventually("the user made again")(kit.realm.byEmail(email).filter(_.roles("brand_silvervibe")))
    assertNotEquals(again.id, first.id)
    kit.eventually("the account names the new user")(
      Option(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser).filter(_.contains(again.id))): Unit
  }

  test("a manager of another brand can neither see nor act on an application") {
    val aff = apply("goldvibe", s"${kit.fresh("cy")}@example.com").json.get("affiliateId").asText
    val elsewhere = kit.asManager(Seq("silvervibe"))
    val read = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff", headers = elsewhere)
    assertEquals(read.status, 403)
    assertEquals(approve("goldvibe", aff, as = elsewhere).status, 403)
    assertEquals(kit.request("GET", s"/v1/affiliates/silvervibe/accounts/$aff", headers = elsewhere).json.get("error").asText, "account_not_found")
    assertEquals(status(aff, "goldvibe"), Some(Membership.Pending))
  }

  test("a rejected applicant may apply again; a pending or active one is a duplicate") {
    val email = s"${kit.fresh("di")}@example.com"
    val aff   = apply("silvervibe", email).json.get("affiliateId").asText
    assertEquals(apply("silvervibe", email).json.get("error").asText, "email_already_registered")
    val rejected = kit.request("POST", s"/v1/affiliates/silvervibe/accounts/$aff/reject", Some("""{"reason":"no traffic"}"""), kit.asManager(Seq("silvervibe")))
    assertEquals(rejected.status, 200)
    val again = apply("silvervibe", email)
    assertEquals((again.status, again.json.get("affiliateId").asText), (201, aff))
    assertEquals(status(aff, "silvervibe"), Some(Membership.Pending))
  }

  test("a transition outside the membership's is refused naming its status; the raw status route is not served") {
    val aff = apply("goldvibe", s"${kit.fresh("ed")}@example.com").json.get("affiliateId").asText
    kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/reject", Some("{}"), kit.asManager()): Unit
    val again = approve("goldvibe", aff, s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""")
    assertEquals((again.status, again.json.get("error").asText), (409, "must be pending, currently rejected"))
    assertEquals(kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/$aff/status", Some("""{"status":"ACTIVE"}"""), kit.asManager()).status, 404)
  }

  test("the operator's patch sets the plan and the RevShare override at that brand only") {
    val s    = Scene(kit)
    val gold = kit.plan("goldvibe", kit.fresh("gold"), Plan.RevShare, revShareBp = 2_500)
    val r = kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/${s.affiliate}", Some(s"""{"planId":"$gold","revShareOverride":"35"}"""), kit.asManager())
    assertEquals(r.status, 200, r.text)
    assertEquals((r.json.get("planId").asText, r.json.get("memberships").get(0).get("revShareOverride").asDouble), (gold, 35.0))
    val cleared = kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/${s.affiliate}", Some("""{"revShareOverride":null}"""), kit.asManager())
    assert(!cleared.json.get("memberships").get(0).has("revShareOverride"), cleared.text)
  }

  test("revenue for one brand folds that brand's facts; with no filter, each brand per currency, never summed") {
    val gold = Scene(kit, planType = Plan.Cpa, cpa = 5_000, minimumDeposit = 0)
    val aff  = gold.affiliate
    val silverPlan = kit.plan("silvervibe", kit.fresh("silver"), Plan.Cpa, cpa = 7_000, currency = "USD")
    val m = kit.components.forEventSourcedEntity(Membership.entityId(aff, "silvervibe"))
    m.call(Membership.applyFor).invoke(io.eitheror.casino.programme.accounts.Apply(aff, "silvervibe",
      io.eitheror.casino.programme.accounts.Application(kit.now, None, "kit"))): Unit
    m.call(Membership.approve).invoke(io.eitheror.casino.programme.accounts.Approve(silverPlan, "kit", kit.now)): Unit
    val silverLink = kit.link(kit.fresh("lnk"), aff, "silvervibe")
    val silver = Facts("silvervibe", aff, silverLink)
    val p1 = gold.player()
    gold.send(gold.facts.firstDeposit(p1, 2_000, kit.now)): Unit
    gold.send(gold.facts.deposit(p1, 2_000, kit.now)): Unit
    val p2 = kit.fresh("ps")
    kit.publish(silver.registration(p2, kit.now))
    kit.publish(silver.firstDeposit(p2, 3_000, kit.now, "USD"))
    kit.publish(silver.deposit(p2, 3_000, kit.now, "USD"))
    val period = kit.now.toString.take(7)
    def read(q: String): JsonNode = kit.request("GET", s"/v1/my/$aff/revenue?period=$period$q", headers = kit.asAffiliate(aff)).json
    def figures(n: JsonNode) = n.get("figures").elements().asScala.toVector
    val one = kit.eventually("goldvibe's CPA")(Option(read("&brand=goldvibe")).filter(n => figures(n).exists(_.get("cpaCommission").asLong == 5_000)))
    assertEquals(figures(one).map(_.get("brandId").asText).distinct, Vector("goldvibe"))
    val all = kit.eventually("both brands' figures")(Option(read("")).filter(n => figures(n).count(_.get("cpaCommission").asLong > 0) == 2))
    val byBrand = figures(all).map(f => (f.get("brandId").asText, f.get("currency").asText) -> f.get("cpaCommission").asLong).toMap
    assertEquals(byBrand, Map(("goldvibe", "EUR") -> 5_000L, ("silvervibe", "USD") -> 7_000L))
    assertEquals(figures(all).map(f => f.get("depositCount").asLong).sum, 2L)
    assertEquals(kit.request("GET", s"/v1/my/$aff/revenue?period=$period&brand=nobrand", headers = kit.asAffiliate(aff)).json.get("error").asText, "not_a_member")
    assertEquals(kit.request("GET", s"/v1/my/$aff/revenue?period=junk", headers = kit.asAffiliate(aff)).json.get("error").asText, "invalid_period")
  }
