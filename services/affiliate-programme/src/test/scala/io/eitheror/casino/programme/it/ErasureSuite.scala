package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.comms.EmailSend

import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * An affiliate as a data subject (US8, T082 and T083): closing and filing an erasure, who may, a new
 * email, and players read by pseudonym. The hold's lapse moves the clock five years, so it is shown in
 * LeakSuite and DataSubjectSteps, each on a service of its own; here the erasure is only filed and held.
 */
class ErasureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def post(path: String, as: Seq[(String, String)] = kit.asManager(), body: Option[String] = None): Reply =
    kit.request("POST", s"/v1/affiliates$path", body, as)
  private def account(aff: String): AffiliateState = kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke()
  private def silverToo(aff: String): Unit =
    val plan = kit.plan("silvervibe", kit.fresh("p"))
    val m    = kit.components.forEventSourcedEntity(Membership.entityId(aff, "silvervibe"))
    m.call(Membership.applyFor).invoke(Apply(aff, "silvervibe", Application(kit.now, None, "kit", None))): Unit
    m.call(Membership.approve).invoke(Approve(plan, "kit", kit.now)): Unit
    // The rule reads memberships from the view, as every read of an account's brands does.
    kit.eventually("the silvervibe membership seen")(Option(kit.views.forView(Memberships).ask(Memberships.byAffiliate, "affiliate" -> aff))
      .filter(_.exists(_.brand == "silvervibe"))): Unit

  test("an account with a membership at a brand the manager does not hold is closed by none but a holder of both") {
    val s = Scene(kit)
    silverToo(s.affiliate)
    val shared = post(s"/goldvibe/accounts/${s.affiliate}/close")
    assertEquals(shared.status, 409, shared.text)
    assertEquals(shared.json.get("error").asText, "account_shared")
    assert(shared.json.get("message").asText.contains("silvervibe"), shared.text)
    assertEquals(post(s"/goldvibe/accounts/${s.affiliate}/erasure").status, 409)
    assertEquals(account(s.affiliate).closed, None)
    val both = post(s"/goldvibe/accounts/${s.affiliate}/close", kit.asManager(Seq("goldvibe", "silvervibe")))
    assertEquals(both.status, 200, both.text)
    assert(account(s.affiliate).closed.isDefined)
  }

  test("one brand's manager changes the account's membership there, and nothing that is the whole account's") {
    val email = s"${kit.fresh("both")}@example.com"
    val aff   = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"$email"}"""), kit.fromInternet).json.get("affiliateId").asText
    assertEquals(post(s"/goldvibe/accounts/$aff/approve", body = Some(s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""")).status, 200)
    silverToo(aff)
    val parent = kit.affiliate(kit.fresh("par"), "goldvibe", kit.plan("goldvibe", kit.fresh("p")))
    def shared(r: Reply)(using munit.Location) =
      assertEquals(r.status, 409, r.text)
      assertEquals(r.json.get("error").asText, "account_shared")
      // A routine write names no brand the manager does not hold (FR-041); only closing and erasing do (T082).
      assert(!r.text.contains("silvervibe"), r.text)
    shared(post(s"/goldvibe/accounts/$aff/update-email", body = Some(s"""{"email":"${kit.fresh("taken")}@example.com"}""")))
    shared(post(s"/goldvibe/accounts/$aff/reset-password"))
    shared(post(s"/goldvibe/accounts/$aff/resend-invitation"))
    def patch(body: String, as: Seq[(String, String)] = kit.asManager()) =
      kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/$aff", Some(body), as)
    shared(patch("""{"paymentMethod":"IBAN GB00 0000"}"""))
    shared(patch("""{"postbackUrl":"https://example.com/pb"}"""))
    shared(patch("""{"minimumPayout":{"EUR":1}}"""))
    shared(post(s"/goldvibe/accounts/$aff/approve", body = Some(s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}","parentAffiliateId":"$parent"}""")))
    // The brand's own: the plan and the RevShare override at it.
    assertEquals(patch(s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""").status, 200)
    // A holder of both, or a platform administrator, may.
    assertEquals(patch("""{"postbackUrl":"https://example.com/pb"}""", kit.asManager(Seq("goldvibe", "silvervibe"))).status, 200)
    val refused = patch("""{"postbackUrl":"ftp://example.com/pb"}""", kit.asManager(Seq("goldvibe", "silvervibe")))
    assertEquals((refused.status, refused.json.get("error").asText), (400, "postback_url_refused"), refused.text)
    assertEquals(patch("""{"minimumPayout":{"EUR":1}}""", kit.asPlatformAdmin()).status, 200)
    // The brand's own too: its account manager, group and tags for the affiliate, which the other brand does not see.
    assertEquals(patch("""{"managerId":"m-gold","group":"vip","tags":["sport"]}""").status, 200)
    val gold = kit.components.forEventSourcedEntity(Membership.entityId(aff, "goldvibe")).call(Membership.get).invoke()
    assertEquals((gold.managerId, gold.group, gold.tags), (Some("m-gold"), Some("vip"), Vector("sport")))
    val silver = kit.request("GET", s"/v1/affiliates/silvervibe/accounts/$aff", headers = kit.asManager(Seq("silvervibe")))
    assertEquals(silver.status, 200, silver.text)
    assertEquals((Option(silver.json.get("managerId")).forall(_.isNull), silver.json.get("tags").size), (true, 0), silver.text)
    // Nor may a manager give another brand's affiliate a membership, and with it their identity, by email:
    // the affiliate is invited to apply, and the manager is not told where they work.
    val hosted    = Hosted.add(kit)
    val elsewhere = post(s"/${hosted.slug}/accounts", body = Some(s"""{"email":"$email"}"""), as = kit.asManager(Seq(hosted.slug)))
    assertEquals((elsewhere.status, elsewhere.json.get("status").asText), (202, "invited"), elsewhere.text)
    assert(!elsewhere.text.contains(aff) && !elsewhere.text.contains("goldvibe"), elsewhere.text)
    assert(!kit.components.forEventSourcedEntity(Membership.entityId(aff, hosted.slug)).call(Membership.get).invoke().exists)
    val invite = kit.components.forWorkflow(EntityId(s"brand-invite/${hosted.slug}/$aff")).call(EmailSend.status).invoke()
    assertEquals((invite.affiliate, invite.kind), (aff, "AffiliateBrandInvite"))
  }

  test("a platform administrator closes an account wherever it is a member") {
    val s = Scene(kit)
    silverToo(s.affiliate)
    assertEquals(post(s"/goldvibe/accounts/${s.affiliate}/close").status, 409)
    assertEquals(post(s"/goldvibe/accounts/${s.affiliate}/close", kit.asPlatformAdmin()).status, 200)
  }

  test("closing ends sign-in: the realm user loses the affiliate and brand roles; closing again answers the same") {
    val r   = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"${kit.fresh("close")}@close.example"}"""), kit.fromInternet)
    val aff = r.json.get("affiliateId").asText
    assertEquals(post(s"/goldvibe/accounts/$aff/approve", body = Some(s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""")).status, 200)
    val user = kit.eventually("the realm user")(account(aff).keycloakUser)
    kit.eventually("the roles")(kit.realm.user(user).filter(_.roles("affiliate"))): Unit
    val first = post(s"/goldvibe/accounts/$aff/close")
    assertEquals(first.status, 200, first.text)
    assertEquals(kit.realm.user(user).map(_.roles.filter(r => r == "affiliate" || r.startsWith("brand_"))), Some(Set.empty[String]))
    val again = post(s"/goldvibe/accounts/$aff/close")
    assertEquals(again.json.get("closedAt").asText, first.json.get("closedAt").asText)
    assertEquals(kit.request("PATCH", s"/v1/my/$aff/last-login", None, kit.asAffiliate(aff)).status, 409)
    assert(kit.eventually("audited")(Option(kit.views.forView(io.eitheror.casino.programme.activity.Activity)
      .ask(io.eitheror.casino.programme.activity.Activity.byAction, "action" -> "ACCOUNT_CLOSED").exists(_.affiliate == aff)).filter(identity)))
  }

  test("an erasure is filed only for a closed account, held to the filing day plus the retention, and filed once") {
    val s = Scene(kit)
    val open = post(s"/goldvibe/accounts/${s.affiliate}/erasure")
    assertEquals((open.status, open.json.get("error").asText), (409, "account_not_closed"))
    assertEquals(post(s"/goldvibe/accounts/${s.affiliate}/close").status, 200)
    val filed = post(s"/goldvibe/accounts/${s.affiliate}/erasure")
    assertEquals(filed.status, 202, filed.text)
    val filedAt = Instant.parse(filed.json.get("filedAt").asText)
    assertEquals(Instant.parse(filed.json.get("holdUntil").asText), ErasureRules.holdUntil(filedAt, 1825))
    assertEquals(filed.json.get("holdUntil").asText.drop(10), "T00:00:00Z")
    val again = post(s"/goldvibe/accounts/${s.affiliate}/erasure")
    assertEquals((again.status, again.json.get("filedAt").asText), (202, filed.json.get("filedAt").asText))
    // Held: the identity stays readable until the hold ends.
    Thread.sleep(500)
    assertEquals(account(s.affiliate).erased, None)
    val read = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/${s.affiliate}", headers = kit.asManager())
    assertEquals(read.json.get("email").asText, s"${s.affiliate}@example.com")
  }

  test("ErasureRules: a realm user is gone when deleted or not there; a realm not configured deleted nothing") {
    assert(ErasureRules.realmDeleted(Right(())) && ErasureRules.realmDeleted(Left(RealmError.NotFound("u"))))
    assert(!ErasureRules.realmDeleted(Left(RealmError.NotConfigured("no credentials"))))
    assert(!ErasureRules.realmDeleted(Left(RealmError.Failed("500"))))
  }

  test("ErasureRules: the hold ends at the start of the UTC day the retention names") {
    assertEquals(ErasureRules.holdUntil(Instant.parse("2026-11-02T23:59:59Z"), 1825), Instant.parse("2031-11-01T00:00:00Z"))
    assertEquals(ErasureRules.holdUntil(Instant.parse("2026-11-02T00:00:00Z"), 0), Instant.parse("2026-11-02T00:00:00Z"))
  }

  test("O18: a new email is sealed, indexed and given to the realm user; an email another account holds is refused") {
    val old = s"${kit.fresh("old")}@email.example"
    val r   = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"$old"}"""), kit.fromInternet)
    val aff = r.json.get("affiliateId").asText
    assertEquals(post(s"/goldvibe/accounts/$aff/approve", body = Some(s"""{"planId":"${kit.plan("goldvibe", kit.fresh("p"))}"}""")).status, 200)
    val user = kit.eventually("the realm user")(account(aff).keycloakUser)
    val held = s"${kit.fresh("held")}@email.example"
    assertEquals(kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"$held"}"""), kit.fromInternet).status, 201)
    val taken = post(s"/goldvibe/accounts/$aff/update-email", body = Some(s"""{"email":"$held"}"""))
    assertEquals((taken.status, taken.json.get("error").asText), (409, "email_already_registered"))
    val before = account(aff).emailDigest
    val fresh  = s"${kit.fresh("new")}@Email.example"
    assertEquals(post(s"/goldvibe/accounts/$aff/update-email", body = Some(s"""{"email":" $fresh "}""")).status, 200)
    assertNotEquals(account(aff).emailDigest, before)
    assertEquals(kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff", headers = kit.asManager()).json.get("email").asText, fresh.toLowerCase)
    assertEquals(kit.realm.user(user).map(_.email), Some(fresh.toLowerCase))
    // The old address is free again: applying with it makes a new account.
    val reapply = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"$old"}"""), kit.fromInternet)
    assertEquals(reapply.status, 201, reapply.text)
    assertNotEquals(reapply.json.get("affiliateId").asText, aff)
    assertEquals(post(s"/goldvibe/accounts/$aff/update-email", body = Some("{}")).status, 400)
  }

  private def players(r: Reply): Vector[JsonNode] = { assertEquals(r.status, 200, r.text); r.json.elements().asScala.toVector }

  test("O26 and M23 list players by pseudonym with country and figures; a sub-affiliate's players in M23 only; a closed player loses its country") {
    val s   = Scene(kit, planType = Plan.Cpa, cpa = 1_000)
    val sub = kit.affiliate(kit.fresh("sub"), "goldvibe", s.plan, parent = Some(s.affiliate))
    val p   = s.player()
    s.send(s.facts.deposit(p, 1_500, kit.now)): Unit
    val subFacts = Facts("goldvibe", sub, kit.link(kit.fresh("lnk"), sub, "goldvibe"))
    val q = kit.fresh("ps")
    s.send(subFacts.registration(q, kit.now)): Unit
    s.send(subFacts.deposit(q, 700, kit.now)): Unit
    val o26 = kit.eventually("O26")(Option(players(kit.request("GET", s"/v1/affiliates/goldvibe/accounts/${s.affiliate}/players", headers = kit.asManager())))
      .filter(_.exists(_.get("playerRef").asText == p)))
    assert(!o26.exists(_.get("playerRef").asText == q))
    val m23 = kit.eventually("M23")(Option(players(kit.request("GET", s"/v1/my/${s.affiliate}/players", headers = kit.asAffiliate(s.affiliate))))
      .filter(ps => Set(p, q).subsetOf(ps.map(_.get("playerRef").asText).toSet)))
    val mine = m23.find(_.get("playerRef").asText == p).get
    assertEquals((mine.get("country").asText, mine.get("referredByAffiliateId").asText, mine.get("deposits").get("EUR").get("sum").asLong),
      ("ZA", s.affiliate, 1_500L))
    assertEquals(m23.find(_.get("playerRef").asText == q).get.get("referredByAffiliateId").asText, sub)
    assertEquals(kit.request("GET", s"/v1/my/$sub/players", headers = kit.asAffiliate(s.affiliate)).status, 200)
    assertEquals(kit.request("GET", s"/v1/my/${s.affiliate}/players", headers = kit.asAffiliate(sub)).status, 403)
    s.send(s.facts.playerClosed(p, kit.now)): Unit
    for path <- Seq(s"/v1/affiliates/goldvibe/accounts/${s.affiliate}/players", s"/v1/my/${s.affiliate}/players") do
      val as = if path.startsWith("/v1/my") then kit.asAffiliate(s.affiliate) else kit.asManager()
      val closed = kit.eventually(s"closed in $path")(players(kit.request("GET", path, headers = as)).find(r => r.get("playerRef").asText == p && r.get("closed").asBoolean))
      assert(!closed.has("country"), closed.toString)
      assertEquals(closed.get("deposits").get("EUR").get("sum").asLong, 1_500L)
  }
