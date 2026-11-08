package io.eitheror.casino.programme.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.activity.Activity
import io.eitheror.casino.programme.brands.{AddBrand, BrandProgramme, Plan}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.it.{Reply, Scene}
import io.eitheror.casino.programme.ledger.Ledger

import scala.jdk.CollectionConverters.*

/**
 * features/programme/managers.feature (US6). Brand "A" is goldvibe and "B" silvervibe, except where a
 * scenario changes a brand's settings or closes it: it is then a brand of the scenario's own.
 */
class ManagerSteps extends ProgrammeFeatures("managers.feature"):
  private var brands: Seq[String]   = Nil
  private var replies: Map[String, Reply] = Map.empty
  private var scenes: Map[String, Scene] = Map.empty
  private var player: String       = null
  private var flagId: String       = null
  private var roles: Set[String]   = Set.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    brands = Nil; replies = Map.empty; scenes = Map.empty; player = null; flagId = null; roles = Set.empty

  private def slug(name: String): String = ids.getOrElse(s"brand:$name", if name == "A" then "goldvibe" else "silvervibe")

  /** The brand named, made a brand of this scenario's own. */
  private def own(name: String): String =
    val s = kit.fresh("b").toLowerCase
    kit.components.forKeyValueEntity(EntityId(s)).call(BrandProgramme.add).invoke(AddBrand(s, BrandProgramme.Elsewhere, Some(s), "UTC", "kit", kit.now)): Unit
    ids(s"brand:$name") = s
    s

  private def manager = kit.asManager(brands.map(slug))
  private def admin   = kit.asManager(brands.map(slug), "admin-1", "brand_admin")
  private def get(path: String): Reply = kit.request("GET", s"/v1/affiliates$path", headers = manager)
  private def send(method: String, path: String, body: String, as: Seq[(String, String)] = manager): Reply =
    kit.request(method, s"/v1/affiliates$path", Some(body), as)
  private def items(r: Reply, field: Option[String] = None): Vector[JsonNode] =
    assertEquals(r.status, 200, r.text)
    field.fold(Option(r.json))(f => Option(r.json.get(f))).toVector.flatMap(_.elements().asScala)
  private def audited(action: String, brand: String): Boolean =
    kit.views.forView(Activity).ask(Activity.byAction, "action" -> action).exists(_.brand.contains(brand))
  private def scene(brand: String, cpa: Long = 0): Scene =
    scenes.getOrElse(brand, { val s = Scene(kit, brand, if cpa > 0 then Plan.Hybrid else Plan.RevShare, cpa); scenes += brand -> s; s })

  // ── one brand's, and several ────────────────────────────────────────────────────────────────────

  Given("a manager for brand {string} only") { (b: String) => brands = Seq(b) }
  Given("a manager for brand {string}") { (b: String) => brands = Seq(b) }
  Given("a manager for brands {string} and {string}") { (a: String, b: String) => brands = Seq(a, b) }

  When("they list accounts, revenue, payouts or fraud flags") { () =>
    Seq(slug("A"), slug("B")).foreach(b => scene(b))
    val gold = slug("A")
    kit.eventually("listed")(Option(get(s"/$gold/accounts")).filter(r => items(r).exists(_.get("account").get("affiliateId").asText == scene(gold).affiliate))): Unit
    replies = Map("accounts" -> get(s"/$gold/accounts"), "revenue" -> get(s"/$gold/revenue?period=all"), "payouts" -> get(s"/$gold/payouts?period=2026-10"),
      "flags" -> get(s"/$gold/fraud-flags"))
  }
  Then("only the memberships and facts of brand {string} are listed") { (b: String) =>
    val brand = slug(b)
    val other = scene(slug("B")).affiliate
    assert(!items(replies("accounts")).exists(_.get("account").get("affiliateId").asText == other))
    assert(items(replies("accounts")).forall(_.get("account").get("memberships").elements().asScala.forall(_.get("brandId").asText == brand)))
    assert(items(replies("revenue"), Some("figures")).forall(_.get("brandId").asText == brand))
    assert(items(replies("payouts")).forall(_.get("brandId").asText == brand))
    assert(items(replies("flags")).forall(_.get("brandId").asText == brand))
  }

  When("they list the accounts of brand {string}") { (b: String) => last = get(s"/${slug(b)}/accounts") }
  Then("it is refused") { () =>
    assertEquals(last.status, 403, last.text)
    assert(last.json.get("error").asText.startsWith("brand_not_held"), last.text)
  }

  When("they read revenue with no brand filter") { () =>
    brands.map(slug).foreach { b =>
      val s = scene(b)
      s.send(s.facts.deposit(s.player(), 2_000, kit.now)): Unit
      replies += b -> kit.eventually(s"$b's figures")(Option(get(s"/$b/revenue?period=all")).filter(r =>
        items(r, Some("figures")).exists(f => Option(f.get("currency")).isDefined && f.get("depositCount").asLong > 0)))
    }
  }
  Then("figures are listed per brand and per currency") { () =>
    brands.map(slug).foreach { b =>
      val figures = items(replies(b), Some("figures"))
      assert(figures.forall(_.get("brandId").asText == b), replies(b).text)
      assert(figures.exists(_.get("currency").asText == "EUR"), replies(b).text)
    }
  }

  // ── plans ───────────────────────────────────────────────────────────────────────────────────────

  When("they create or change a plan for brand {string}") { (b: String) =>
    val brand = slug(b)
    val made  = send("POST", s"/$brand/commission-plans", s"""{"planName":"${id("Plan")}","planType":"CPA","cpaAmount":5000,"cpaCurrency":"EUR"}""")
    assertEquals(made.status, 201, made.text)
    ids("plan") = made.json.get("planId").asText
    val aff  = kit.affiliate(id("aff"), brand, ids("plan"))
    val link = kit.link(id("lnk"), aff, brand)
    val s    = io.eitheror.casino.programme.it.Facts(brand, aff, link)
    def firstDeposit(): Unit =
      val p = kit.fresh("ps")
      kit.publish(s.registration(p, kit.now))
      kit.publish(s.firstDeposit(p, 2_000, kit.now))
    firstDeposit()
    kit.eventually("the first CPA")(Option(cpas(aff, brand)).filter(_ == Vector(5_000L))): Unit
    assertEquals(send("PATCH", s"/$brand/commission-plans/${ids("plan")}", """{"cpaAmount":7000}""").status, 200)
    firstDeposit()
    ids("aff") = aff
  }
  private def cpas(aff: String, brand: String): Vector[Long] =
    kit.views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> aff, "brand" -> brand, "from" -> "0000-00", "to" -> "9999-99")
      .filter(_.kind == FactType.Cpa).map(_.amount).sorted
  Then("it applies to the memberships at brand {string} that name it, from the next fact onward") { (b: String) =>
    kit.eventually("the second CPA")(Option(cpas(ids("aff"), slug(b))).filter(_ == Vector(5_000L, 7_000L))): Unit
  }
  Then("the change is audited") { () =>
    kit.eventually("audited")(Option(audited("PLAN_CREATED", slug("A")) && audited("PLAN_UPDATED", slug("A"))).filter(identity)): Unit
  }

  // ── adjustments ─────────────────────────────────────────────────────────────────────────────────

  When("they enter a credit or debit adjustment for an affiliate at brand {string} in a currency with a reason") { (b: String) =>
    val brand = own(b)
    val aff   = kit.affiliate(id("aff"), brand, kit.plan(brand, kit.fresh("p"), Plan.Cpa, cpa = 1_000))
    ids("aff") = aff
    for (kind, amount) <- Seq("CREDIT" -> 1_000, "DEBIT" -> 300) do
      val r = send("POST", s"/$brand/accounts/$aff/adjustments", s"""{"type":"$kind","amount":$amount,"currency":"EUR","reason":"goodwill"}""")
      assertEquals(r.status, 201, r.text)
  }
  Then("an adjustment fact is written, negative for a debit") { () =>
    val rows = kit.eventually("both adjustments")(Option(kit.views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> ids("aff"),
      "brand" -> slug("A"), "from" -> "0000-00", "to" -> "9999-99").filter(_.kind == FactType.Adjustment)).filter(_.size == 2))
    assertEquals(rows.map(_.amount).sorted, Vector(-300L, 1_000L))
  }
  Then("it is swept by the next close like any payable fact") { () =>
    val brand = slug("A")
    kit.owed(ids("aff"), brand, "EUR")
    val period = io.eitheror.casino.programme.periods.Periods.periodOf(kit.now, java.time.ZoneOffset.UTC)
    val closed = kit.request("POST", s"/v1/affiliates/$brand/payouts/close?period=$period", headers = manager)
    assertEquals(closed.status, 200, closed.text)
    val mine = closed.json.get("payouts").elements().asScala.find(_.get("affiliateId").asText == ids("aff"))
    assertEquals(mine.map(_.get("totalPayable").asLong), Some(700L), closed.text)
  }

  // ── fraud flags ─────────────────────────────────────────────────────────────────────────────────

  private def flagged(): JsonNode =
    val s = scene("goldvibe")
    player = s.player()
    val threshold = Scene.brand(kit, "goldvibe").thresholds.chargeback
    (1 to threshold + 1).foreach(_ => s.send(s.facts.chargeback(player, 500, kit.now)): Unit)
    val flags = kit.eventually("flagged")(Option(items(get("/goldvibe/fraud-flags")).filter(_.get("playerId").asText == player)).filter(_.nonEmpty))
    Thread.sleep(1_000)
    assertEquals(items(get("/goldvibe/fraud-flags")).count(_.get("playerId").asText == player), 1)
    flags.head

  Given("the programme's fraud thresholds for brand {string}") { (b: String) => brands = Seq(b) }
  When("a player's bonus cost or chargebacks cross them") { () => flagId = flagged().get("flagId").asText }
  Then("a fraud flag is raised once for that player, brand and reason for the manager to review") { () =>
    val f = items(get("/goldvibe/fraud-flags")).filter(_.get("playerId").asText == player)
    assertEquals(f.map(f => (f.get("reason").asText, f.get("dismissed").asBoolean, f.get("affiliateId").asText)),
      Vector(("chargeback", false, scene("goldvibe").affiliate)))
  }

  Given("a fraud flag raised at brand {string}") { (b: String) =>
    brands = Seq(b)
    flagId = flagged().get("flagId").asText
  }
  When("a manager dismisses it") { () =>
    last = send("PATCH", s"/goldvibe/fraud-flags/$flagId/dismiss", """{"reason":"reviewed: a known player"}""")
    assertEquals(last.status, 200, last.text)
  }
  Then("the affiliate is told the fraud flag was reviewed and dismissed") { () =>
    val told = kit.eventually("told")(items(get(s"/goldvibe/accounts/${scene("goldvibe").affiliate}/view/notifications"))
      .find(_.get("type").asText == "FRAUD_DISMISSED"))
    assert(told.get("body").asText.contains("reviewed and dismissed"), told.toString)
  }

  // ── the read-only view ──────────────────────────────────────────────────────────────────────────

  When("they open an affiliate's read-only view") { () =>
    val s = scene("goldvibe")
    kit.request("POST", s"/v1/my/${s.affiliate}/tracking-links", Some("""{"brandId":"goldvibe"}"""), kit.asAffiliate(s.affiliate)): Unit
    replies = Map("dashboard" -> "dashboard?period=all", "stats" -> "stats?period=all", "commission" -> "commission?period=all", "links" -> "links",
      "payouts" -> "payouts", "notifications" -> "notifications").map((k, p) => k -> get(s"/goldvibe/accounts/${s.affiliate}/view/$p"))
  }
  Then("they see the dashboard, stats, commission, links, payouts and notifications the affiliate would see") { () =>
    val aff = scene("goldvibe").affiliate
    replies.foreach((page, r) => assertEquals(r.status, 200, s"$page: ${r.text}"))
    def portal(path: String) = kit.request("GET", s"/v1/my/$aff/$path", headers = kit.asAffiliate(aff)).json
    assertEquals(replies("dashboard").json, portal("revenue?brand=goldvibe&period=all"))
    kit.eventually("the same links")(Option(get(s"/goldvibe/accounts/$aff/view/links").json).filter(_ == portal("tracking-links?brand=goldvibe"))): Unit
    assertEquals(replies("payouts").json, portal("payouts?brand=goldvibe"))
  }
  Then("they can change nothing from there") { () =>
    val aff = scene("goldvibe").affiliate
    for method <- Seq("POST", "PUT", "PATCH", "DELETE"); page <- Seq("links", "payouts", "notifications") do
      val r = send(method, s"/goldvibe/accounts/$aff/view/$page", "{}")
      assert(r.status / 100 == 4, s"$method $page: ${r.status} ${r.text}")
  }

  // ── managers ────────────────────────────────────────────────────────────────────────────────────

  Given("a manager with the right role") { () => brands = Seq("A") }
  When("they add a manager for brand {string}, reset their password or revoke them") { (b: String) =>
    val brand = slug(b)
    val made  = send("POST", s"/$brand/managers", s"""{"email":"${id("mgr")}@example.com","name":"New Manager"}""", admin)
    assertEquals(made.status, 201, made.text)
    val user = made.json.get("keycloakUserId").asText
    roles = kit.realm.user(user).get.roles
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/managers/$user/reset-password", headers = admin).status, 204)
    assertEquals(kit.request("DELETE", s"/v1/affiliates/$brand/managers/$user", headers = admin).status, 204)
    assertEquals(kit.realm.user(user).get.roles, Set.empty[String])
  }
  Then("the manager is provisioned with the manager role and the role for brand {string}") { (b: String) =>
    assertEquals(roles, Set("affiliate_manager", s"brand_${slug(b)}"))
  }
  Then("the action is audited") { () =>
    kit.eventually("audited")(Option(Seq("MANAGER_ADDED", "MANAGER_PASSWORD_RESET", "MANAGER_REVOKED").forall(audited(_, slug("A")))).filter(identity)): Unit
  }

  // ── the programme page ──────────────────────────────────────────────────────────────────────────

  Given("the programme page of brand {string}") { (b: String) =>
    brands = Seq(b)
    own(b): Unit
  }
  When("a manager changes it") { () =>
    last = send("PUT", s"/${slug("A")}/programme", s"""{"terms":"${id("terms")}","termsVersion":"2","description":"New text"}""")
    assertEquals(last.status, 200, last.text)
  }
  Then("affiliates applying to brand {string} see the new text") { (b: String) =>
    val brand   = slug(b)
    val applied = kit.request("POST", s"/v1/affiliates/$brand/apply", Some(s"""{"email":"${id("applicant")}@example.com"}"""), kit.fromInternet)
    assertEquals(applied.status, 201, applied.text)
    val aff  = applied.json.get("affiliateId").asText
    val page = kit.request("GET", s"/v1/my/$aff/programme?brand=$brand", headers = kit.asAffiliate(aff))
    assertEquals((page.json.get("terms").asText, page.json.get("description").asText), (ids("terms"), "New text"), page.text)
  }
