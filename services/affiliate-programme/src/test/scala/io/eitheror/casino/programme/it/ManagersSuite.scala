package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.activity.{Activity, FraudFlag}
import io.eitheror.casino.programme.brands.{AddBrand, BrandProgramme, Plan}

import scala.jdk.CollectionConverters.*

/** Managers run the programme per brand (US6, contracts/routes.md `/v1/affiliates`). */
class ManagersSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def get(path: String, as: Seq[(String, String)] = kit.asManager()): Reply = kit.request("GET", s"/v1/affiliates$path", headers = as)
  private def send(method: String, path: String, body: String, as: Seq[(String, String)] = kit.asManager()): Reply =
    kit.request(method, s"/v1/affiliates$path", Some(body), as)
  private def items(r: Reply): Vector[JsonNode] = { assertEquals(r.status, 200, r.text); r.json.elements().asScala.toVector }
  private def admin(brands: Seq[String] = Seq("goldvibe")) = kit.asManager(brands, "admin-1", "brand_admin")
  private def audited(action: String, brand: String): Boolean =
    kit.views.forView(Activity).ask(Activity.byAction, "action" -> action).exists(_.brand.contains(brand))

  test("a manager of one brand reads only that brand; naming another is refused brand_not_held") {
    val gold   = Scene(kit)
    val silver = Scene(kit, "silvervibe")
    val mine = kit.eventually("gold listed")(Option(items(get("/goldvibe/accounts"))).filter(_.exists(_.get("account").get("affiliateId").asText == gold.affiliate)))
    assert(!mine.exists(_.get("account").get("affiliateId").asText == silver.affiliate))
    for path <- Seq("/silvervibe/accounts", "/silvervibe/revenue?period=all", "/silvervibe/payouts", "/silvervibe/fraud-flags") do
      val r = get(path)
      assertEquals(r.status, 403, s"$path: ${r.text}")
      assert(r.json.get("error").asText.startsWith("brand_not_held"), r.text)
    val both = kit.asManager(Seq("goldvibe", "silvervibe"))
    for (brand, s) <- Seq("goldvibe" -> gold, "silvervibe" -> silver) do
      s.send(s.facts.deposit(s.player(), 2_000, kit.now)): Unit
      val r = get(s"/$brand/accounts/${s.affiliate}/revenue?period=all", both)
      assertEquals(r.status, 200, r.text)
      val figures = r.json.get("figures").elements().asScala.toVector
      assert(figures.forall(_.get("brandId").asText == brand), r.text)
  }

  test("a plan is made once by name, changed and listed, and both are audited") {
    val name = kit.fresh("Plan")
    val made = send("POST", "/goldvibe/commission-plans", s"""{"planName":"$name","planType":"HYBRID","cpaAmount":5000,"cpaCurrency":"EUR","revSharePercent":"25"}""")
    assertEquals(made.status, 201, made.text)
    val id = made.json.get("planId").asText
    assertEquals(send("POST", "/goldvibe/commission-plans", s"""{"planName":"$name"}""").json.get("error").asText, "plan_already_exists")
    assertEquals(send("PATCH", s"/goldvibe/commission-plans/$id", """{"cpaAmount":7000}""").status, 200)
    assertEquals(send("PATCH", s"/goldvibe/commission-plans/$id", "{}").status, 400)
    val p = kit.eventually("listed")(items(get("/goldvibe/commission-plans")).find(p => p.get("planId").asText == id && p.get("cpaAmount").asLong == 7000))
    assertEquals((p.get("planType").asText, p.get("revSharePercent").asDouble), (Plan.Hybrid, 25.0))
    kit.eventually("audited")(Option(audited("PLAN_CREATED", "goldvibe") && audited("PLAN_UPDATED", "goldvibe")).filter(identity)): Unit
  }

  test("settings and the programme page are read and changed; an applicant reads the new page") {
    val brand = fresh()
    assertEquals(send("PUT", s"/$brand/settings", """{"adminFeePercent":"12.5","chargebackThreshold":4,"reportingZone":"Africa/Johannesburg"}""",
      kit.asManager(Seq(brand))).status, 200)
    val s = get(s"/$brand/settings", kit.asManager(Seq(brand))).json
    assertEquals((s.get("adminFeePercent").asDouble, s.get("chargebackThreshold").asInt, s.get("reportingZone").asText),
      (12.5, 4, "Africa/Johannesburg"))
    assertEquals(send("PUT", s"/$brand/settings", """{"paymentFeePercent":"140"}""", kit.asManager(Seq(brand))).status, 400)
    assertEquals(send("PUT", s"/$brand/settings", """{"ggrSource":"rounds"}""", kit.asManager(Seq(brand))).status, 400)
    assertEquals(send("PUT", s"/$brand/programme", """{"terms":"Version two","termsVersion":"2"}""", kit.asManager(Seq(brand))).status, 200)
    val applied = kit.request("POST", s"/v1/affiliates/$brand/apply", Some(s"""{"email":"${kit.fresh("app")}@example.com"}"""), kit.fromInternet)
    assertEquals(applied.status, 201, applied.text)
    val aff  = applied.json.get("affiliateId").asText
    val page = kit.request("GET", s"/v1/my/$aff/programme?brand=$brand", headers = kit.asAffiliate(aff))
    assertEquals((page.status, page.json.get("terms").asText), (200, "Version two"), page.text)
  }

  test("a manager makes an account active at the brand, finds it by search, notes it and reads its audit") {
    val email = s"${kit.fresh("made")}@example.com"
    val plan  = kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 1_000)
    val made  = send("POST", "/goldvibe/accounts", s"""{"email":"$email","name":"Made By Manager","planId":"$plan"}""")
    assertEquals(made.status, 201, made.text)
    val aff = made.json.get("affiliateId").asText
    assertEquals(send("POST", "/goldvibe/accounts", s"""{"email":"$email","planId":"$plan"}""").json.get("error").asText, "email_already_registered")
    val found = kit.eventually("found")(items(get(s"/goldvibe/accounts?search=${email.take(10)}&status=ACTIVE")).find(_.get("account").get("affiliateId").asText == aff))
    assertEquals(found.get("account").get("status").asText, "active")
    assertEquals(send("POST", s"/goldvibe/accounts/$aff/notes", """{"text":"called them"}""").status, 201)
    kit.eventually("noted")(items(get(s"/goldvibe/accounts/$aff/notes")).find(_.get("text").asText == "called them")): Unit
    val trail = kit.eventually("audited")(Option(items(get(s"/goldvibe/accounts/$aff/audit"))).filter(_.exists(_.get("event").asText == "NOTE_ADDED")))
    assert(trail.exists(_.get("event").asText == "ACCOUNT_CREATED"), trail.mkString)
  }

  test("a fraud flag is raised once per player, brand and reason; dismissing it tells the affiliate") {
    val s = Scene(kit, cpa = 0)
    val p = s.player()
    (1 to 3).foreach(_ => s.send(s.facts.chargeback(p, 500, kit.now)): Unit)
    val flagged = kit.eventually("flagged")(Option(items(get("/goldvibe/fraud-flags")).filter(_.get("playerId").asText == p)).filter(_.nonEmpty))
    assertEquals(flagged.map(_.get("reason").asText), Vector(FraudFlag.Chargeback))
    val id = flagged.head.get("flagId").asText
    assertEquals(send("PATCH", s"/goldvibe/fraud-flags/$id/dismiss", "{}").status, 400)
    assertEquals(send("PATCH", s"/goldvibe/fraud-flags/$id/dismiss", """{"reason":"known player"}""").status, 200)
    kit.eventually("told")(items(get(s"/goldvibe/accounts/${s.affiliate}/view/notifications")).find(_.get("type").asText == "FRAUD_DISMISSED")): Unit
    assertEquals(send("PATCH", "/goldvibe/fraud-flags/none/dismiss", """{"reason":"x"}""").status, 404)
  }

  test("the read-only view answers each page the affiliate sees, and takes no change") {
    val s = Scene(kit)
    for page <- Seq("dashboard?period=all", "stats?period=all", "commission?period=all", "links", "payouts", "notifications") do
      assertEquals(get(s"/goldvibe/accounts/${s.affiliate}/view/$page").status, 200, page)
    assertEquals(send("POST", s"/goldvibe/accounts/${s.affiliate}/view/links", "{}").status / 100, 4)
    assertEquals(get(s"/goldvibe/accounts/${s.affiliate}/view/commission").status, 400)
  }

  test("messages, creatives, and the facts that paid no one") {
    val s = Scene(kit)
    assertEquals(send("POST", "/goldvibe/messages", s"""{"body":"hello","targetAffiliateId":"${s.affiliate}"}""").json.get("sent").asInt, 1)
    kit.eventually("messaged")(items(get(s"/goldvibe/accounts/${s.affiliate}/view/notifications")).find(_.get("body").asText == "hello")): Unit
    val c = send("POST", "/goldvibe/creatives", """{"name":"Banner","fileUrl":"https://cdn.example/b.png"}""")
    assertEquals(c.status, 201, c.text)
    val id = c.json.get("creativeId").asText
    kit.eventually("listed")(items(get("/goldvibe/creatives")).find(_.get("creativeId").asText == id)): Unit
    assertEquals(get(s"/goldvibe/creatives/$id").status / 100, 4)
    assertEquals(send("DELETE", s"/goldvibe/creatives/$id", "").status, 200)
    kit.eventually("gone")(Option(items(get("/goldvibe/creatives"))).filter(!_.exists(_.get("creativeId").asText == id))): Unit
    val dep = s.facts.deposit(kit.fresh("ps"), 1_000, kit.now)
    assertEquals(s.send(dep), "unattributed:no_attribution")
    val un = get("/goldvibe/unattributed")
    assertEquals(un.status, 200, un.text)
    kit.eventually("listed")(Option(get("/goldvibe/unattributed").json.get("facts")).flatMap(_.elements().asScala.find(_.get("factId").asText == dep.factId))): Unit
  }

  test("a brand or platform administrator adds, lists, resets and revokes a brand's managers") {
    assertEquals(get("/goldvibe/managers").status, 403)
    val email = s"${kit.fresh("mgr")}@example.com"
    val made  = send("POST", "/goldvibe/managers", s"""{"email":"$email","name":"Pat Manager"}""", admin())
    assertEquals(made.status, 201, made.text)
    val user = made.json.get("keycloakUserId").asText
    assertEquals(kit.realm.user(user).get.roles, Set("affiliate_manager", "brand_goldvibe"))
    assertEquals(send("POST", "/goldvibe/managers", s"""{"email":"$email"}""", admin()).status, 409)
    assertEquals(items(get("/goldvibe/managers", admin())).map(_.get("keycloakUserId").asText), Vector(user))
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/managers/$user/reset-password", headers = admin()).status, 204)
    assertEquals(kit.request("POST", "/v1/affiliates/goldvibe/managers/nobody/reset-password", headers = admin()).status, 404)
    // A manager of two brands revoked at one keeps the manager role for the other.
    kit.realm.assignRole(user, "brand_silvervibe"): Unit
    assertEquals(kit.request("DELETE", s"/v1/affiliates/goldvibe/managers/$user", headers = admin()).status, 204)
    assertEquals(kit.realm.user(user).get.roles, Set("affiliate_manager", "brand_silvervibe"))
    assertEquals(kit.request("DELETE", s"/v1/affiliates/silvervibe/managers/$user", headers = admin(Seq("silvervibe"))).status, 204)
    assertEquals(kit.realm.user(user).get.roles, Set.empty[String])
    kit.eventually("audited")(Option(Seq("MANAGER_ADDED", "MANAGER_PASSWORD_RESET", "MANAGER_REVOKED").forall(audited(_, "goldvibe"))).filter(identity)): Unit
  }

  test("a retired brand takes no application and changes no status; its reads go on") {
    val brand = fresh()
    val entity = kit.components.forKeyValueEntity(EntityId(brand))
    entity.call(BrandProgramme.retire).invoke(kit.now): Unit
    val applied = kit.request("POST", s"/v1/affiliates/$brand/apply", Some("""{"email":"late@example.com"}"""), kit.fromInternet)
    assertEquals((applied.status, applied.json.get("error").asText), (409, "brand_retired"))
    assertEquals(get(s"/$brand/settings", kit.asManager(Seq(brand))).status, 200)
  }

  /** A brand of this suite's own, elsewhere, so changing it touches no other suite. */
  private def fresh(): String =
    val slug = kit.fresh("b").toLowerCase
    kit.components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.add)
      .invoke(AddBrand(slug, BrandProgramme.Elsewhere, Some(slug), "UTC", "kit", kit.now)): Unit
    slug
