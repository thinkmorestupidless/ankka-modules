package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.Affiliate
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.postbacks.{OutcomeState, PostbackOutcome, PostbackRules}

import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** The programme talks to affiliates (US7): notifications, emails and postbacks. */
class CommunicationsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def items(r: Reply): Vector[JsonNode] = { assertEquals(r.status, 200, r.text); Option(r.json).toVector.flatMap(_.elements().asScala) }
  private def notifications(aff: String): Vector[JsonNode] = Messaging.notifications(kit, aff)
  private def told(aff: String, kind: String): JsonNode     = Messaging.told(kit, aff, kind)
  private def postbackTo(s: Scene): Unit                    = Messaging.postbackTo(kit, s.affiliate)

  test("a postback for a registration, first deposit, deposit and RevShare, the macros filled and encoded") {
    val s     = Scene(kit, planType = Plan.Hybrid, cpa = 5_000, revShareBp = 3_000)
    postbackTo(s)
    val facts = s.facts.copy(clickId = Some("clk 1&payout=9999"))
    val p     = kit.fresh("ps")
    val reg = facts.registration(p, kit.now)
    s.send(reg): Unit
    s.send(facts.firstDeposit(p, 2_000, kit.now)): Unit
    s.send(facts.deposit(p, 2_000, kit.now)): Unit
    s.send(facts.ggrDaily(p, kit.now.toString.take(10), 10_000, 0, kit.now)): Unit
    val calls = kit.eventually("four postbacks")(Option(kit.postbacks.calls).filter(_.size == 4))
    val byEvent = calls.map(c => c.query("event") -> c.query).toMap
    assertEquals(byEvent.keySet, Set("registration", "ftd", "deposit", "revshare"))
    assert(calls.forall(_.query("click") == "clk 1&payout=9999"), calls.map(_.query).mkString("\n"))
    assertEquals((byEvent("registration")("payout"), byEvent("registration")("status")), ("0", "ok"))
    assertEquals((byEvent("ftd")("payout"), byEvent("ftd")("status")), ("50.00", "approved"))
    assert(BigDecimal(byEvent("revshare")("payout")) > 0, byEvent("revshare").toString)
    // A redelivered fact queues the same postback, which is sent once.
    kit.publish(reg)
    Thread.sleep(1_000)
    assertEquals(kit.postbacks.calls.size, 4)
  }

  test("no postback address, no postback; a CPA plan that did not pay sends no first-deposit postback") {
    val s = Scene(kit, planType = Plan.Cpa, cpa = 5_000, minimumDeposit = 10_000)
    val p = s.player()
    s.send(s.facts.firstDeposit(p, 100, kit.now)): Unit
    Thread.sleep(1_000)
    assertEquals(kit.postbacks.calls, Vector.empty)
    postbackTo(s)
    s.send(s.facts.firstDeposit(kit.fresh("ps"), 100, kit.now)): Unit
    Thread.sleep(1_000)
    assert(!kit.postbacks.calls.exists(_.query("event") == "ftd"))
  }

  test("a failing postback is retried 1, 5, 15, 60 minutes then hourly for 24 hours; its last outcome is visible to both") {
    val s = Scene(kit)
    postbackTo(s)
    kit.postbacks.default = 503
    val p = s.player()
    def outcome: OutcomeState =
      kit.components.forKeyValueEntity(PostbackOutcome.entityId(s.affiliate, s.brand, "registration")).call(PostbackOutcome.get).invoke()
    var last = kit.eventually("the first attempt")(Option(outcome).filter(_.attempts == 1))
    val start = kit.now
    while last.status != PostbackRules.Undelivered do
      val attempts = last.attempts
      kit.moveClock(kit.now.plus(PostbackRules.next(attempts)).plusSeconds(1))
      last = kit.eventually(s"attempt ${attempts + 1}")(Option(outcome).filter(_.attempts > attempts))
    assert(!kit.now.isBefore(start.plus(Duration.ofHours(24))), kit.now.toString)
    assertEquals((last.attempts, last.lastStatus), (28, Some(503)))
    assertEquals(kit.postbacks.calls.count(_.query("event") == "registration"), 28)
    def mine = items(kit.request("GET", s"/v1/my/${s.affiliate}/postbacks", headers = kit.asAffiliate(s.affiliate)))
    val seen = kit.eventually("the affiliate sees it", 60.seconds)(mine.find(o => o.get("event").asText == "registration" && o.get("status").asText == "undelivered"))
    assertEquals(seen.get("attempts").asInt, 28)
    val managers = items(kit.request("GET", s"/v1/affiliates/goldvibe/accounts/${s.affiliate}/view/postbacks", headers = kit.asManager()))
    assertEquals(managers.map(_.get("status").asText), Vector("undelivered"))
  }

  private def brand(): String = Messaging.brand(kit)

  private def apply(brand: String, email: String): String =
    val r = Messaging.apply(kit, brand, email)
    assertEquals(r.status, 201, r.text)
    r.json.get("affiliateId").asText

  private def approve(brand: String, aff: String): Unit =
    val r = Messaging.approve(kit, brand, aff)
    assertEquals(r.status, 200, r.text)

  private def emailed(email: String, kind: String) = Messaging.emailed(kit, email, kind)

  test("an application received, approved and rejected: a notification and the brand's email each") {
    val b     = brand()
    val email = s"${kit.fresh("ann")}@example.com"
    val aff   = apply(b, email)
    told(aff, "APPLICATION_SUBMITTED"): Unit
    assertEquals(emailed(email, "AffiliateApplicationReceived").apiKey, "cio-key")
    approve(b, aff)
    told(aff, "APPLICATION_APPROVED"): Unit
    emailed(email, "AffiliateApplicationApproved"): Unit
    val other = s"${kit.fresh("bob")}@example.com"
    val bob   = apply(b, other)
    assertEquals(kit.request("POST", s"/v1/affiliates/$b/accounts/$bob/reject", Some("""{"reason":"no site"}"""), kit.asManager(Seq(b))).status, 200)
    told(bob, "APPLICATION_REJECTED"): Unit
    assertEquals(emailed(other, "AffiliateApplicationRejected").data.get("reason"), Some("no site"))
  }

  test("a payout approved and paid: notifications, and the payment's email with its amount") {
    val b   = brand()
    val email = s"${kit.fresh("pay")}@example.com"
    val aff = kit.affiliate(kit.fresh("aff"), b, kit.plan(b, kit.fresh("p"), Plan.Cpa, cpa = 1_000), email = Some(email))
    val mgr = kit.asManager(Seq(b))
    assertEquals(kit.request("POST", s"/v1/affiliates/$b/accounts/$aff/adjustments",
      Some("""{"type":"CREDIT","amount":12345,"currency":"EUR","reason":"bonus"}"""), mgr).status, 201)
    kit.owed(aff, b, "EUR")
    val period = kit.now.toString.take(7)
    assertEquals(kit.request("POST", s"/v1/affiliates/$b/payouts/close?period=$period", headers = mgr).status, 200)
    val id = s"$aff:EUR:$period"
    for status <- Seq("APPROVED", "PAID") do
      val r = kit.request("PATCH", s"/v1/affiliates/$b/payouts/$id/status", Some(s"""{"status":"$status"}"""), mgr)
      assertEquals(r.status, 200, r.text)
    assert(told(aff, "PAYOUT_APPROVED").get("body").asText.contains("123.45"))
    told(aff, "PAYOUT_PAID"): Unit
    val sent = emailed(email, "AffiliatePaymentProcessed")
    assertEquals((sent.data("amount"), sent.data("currency"), sent.data("period_label")), ("123.45", "EUR", period))
  }

  test("an invitation and a password reset: a notification and an email, each with a link that sets the password once") {
    val b     = brand()
    val email = s"${kit.fresh("inv")}@example.com"
    val aff   = apply(b, email)
    approve(b, aff)
    val user = kit.eventually("the realm user")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser)
    assertEquals(kit.request("POST", s"/v1/affiliates/$b/accounts/$aff/resend-invitation", headers = kit.asManager(Seq(b))).status, 204)
    told(aff, "INVITED"): Unit
    val link = emailed(email, "AffiliateInvite").data("link")
    val token = link.split("token=").last
    def accept(body: String) = kit.request("POST", "/v1/affiliates/accept-invitation", Some(body), kit.fromInternet)
    assertEquals(accept(s"""{"token":"$token","password":"s3cret-one"}""").json.get("status").asText, "accepted")
    assertEquals(kit.realm.user(user).flatMap(_.password), Some("s3cret-one"))
    assertEquals(accept(s"""{"token":"$token","password":"again"}""").status, 400)
    assertEquals(accept("""{"token":"x"}""").status, 400)
    // The forgotten password: always ok, once in five minutes, and a notice of the change after.
    def forgot(e: String) = kit.request("POST", s"/v1/affiliates/$b/forgot-password", Some(s"""{"email":"$e"}"""), kit.fromInternet)
    assertEquals(forgot("nobody@example.com").json.get("ok").asBoolean, true)
    assertEquals(forgot("not-an-email").status, 400)
    assertEquals(forgot(email).status, 200)
    assertEquals(forgot(email).status, 200)
    told(aff, "PASSWORD_RESET_REQUESTED"): Unit
    val reset = emailed(email, "AffiliatePasswordReset").data("link")
    Thread.sleep(500)
    assertEquals(kit.customerIo.to(email).count(_.transactionalId == "tid-AffiliatePasswordReset"), 1)
    assert(reset.contains("/reset-password?token="), reset)
    assertEquals(accept(s"""{"token":"${reset.split("token=").last}","password":"s3cret-two","mode":"reset","brandId":"$b"}""").status, 200)
    assertEquals(kit.realm.user(user).flatMap(_.password), Some("s3cret-two"))
    emailed(email, "AffiliatePasswordChanged"): Unit
  }

  test("a send that fails blocks nothing: the action answers and the notification is written") {
    val b = brand()
    kit.customerIo.default = Left("Customer.io is down")
    val email = s"${kit.fresh("down")}@example.com"
    val aff   = apply(b, email)
    told(aff, "APPLICATION_SUBMITTED"): Unit
    approve(b, aff)
    told(aff, "APPLICATION_APPROVED"): Unit
  }

  test("a parent is told of a sub's application through their invite link") {
    val parent = kit.affiliate(kit.fresh("par"), "goldvibe", kit.plan("goldvibe", kit.fresh("p")))
    val token  = kit.request("POST", s"/v1/my/$parent/invite-links?brand=goldvibe", Some("{}"), kit.asAffiliate(parent)).json.get("token").asText
    val joined = kit.request("POST", s"/v1/join/$token?brandId=goldvibe", Some(s"""{"email":"${kit.fresh("sub")}@example.com"}"""), kit.fromInternet)
    assertEquals(joined.status, 201, joined.text)
    told(parent, "SUB_AFFILIATE_PENDING"): Unit
  }

  test("marking all read marks the affiliate's own only; events, sign-ins and creatives") {
    val b   = brand()
    val one = apply(b, s"${kit.fresh("one")}@example.com")
    val two = apply(b, s"${kit.fresh("two")}@example.com")
    told(one, "APPLICATION_SUBMITTED"): Unit
    told(two, "APPLICATION_SUBMITTED"): Unit
    val marked = kit.request("PATCH", s"/v1/my/$one/notifications/read-all", headers = kit.asAffiliate(one))
    assertEquals(marked.json.get("marked").asInt, 1)
    kit.eventually("read")(Option(notifications(one)).filter(_.forall(_.get("read").asBoolean))): Unit
    assert(notifications(two).forall(!_.get("read").asBoolean))
    approve(b, one)
    val first = kit.request("POST", s"/v1/my/$one/first-login", headers = kit.asAffiliate(one))
    assertEquals(first.json.get("firstLogin").asBoolean, true)
    assertEquals(kit.request("POST", s"/v1/my/$one/first-login", headers = kit.asAffiliate(one)).json.get("firstLogin").asBoolean, false)
    assertEquals(kit.request("PATCH", s"/v1/my/$one/last-login", headers = kit.asAffiliate(one)).status, 200)
    kit.eventually("the first sign-in among the events")(
      items(kit.request("GET", s"/v1/my/$one/events/account", headers = kit.asAffiliate(one))).find(_.get("eventType").asText == "AFFILIATE_FIRST_LOGIN")): Unit
    assertEquals(kit.request("GET", s"/v1/my/$one/events", headers = kit.asAffiliate(one)).status, 200)
    assertEquals(kit.request("GET", s"/v1/my/$one/creatives", headers = kit.asAffiliate(one)).status, 200)
  }
